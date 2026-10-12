package dev.rehan.passthrough.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.rehan.passthrough.Config;
import dev.rehan.passthrough.Passthrough;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Native blocks: every real block in the render area baked with vanilla's own renderers (ModelBlockRenderer, FluidRenderer)
 * into one mesh, published with the block atlas and lightmap (BlockMeshProtocol) and drawn by the host's compositor with the
 * host camera at its own Present, so blocks are never re-projected. Once the host acknowledges a revision covering the whole
 * vanilla view, chunk geometry is left out of the exported world picture (suppressChunks) and each frame names that revision;
 * if anything goes stale or wrong, chunks come back in the picture. Bounded work per frame, never waits for the GPU. Adapted
 * from EldenCraft's BlockMeshClient (MIT, Copyright (c) 2026 Adam Grzegorzewski, github.com/SocketByte/EldenCraft).
 */
public final class BlockMeshClient {
	/** The revision a frame's world picture left out (its blocks are the host's to draw), or NONE. */
	public record Exclusion(long mesh, long atlas, long session) {
		public static final Exclusion NONE = new Exclusion(0, 0, 0);

		public boolean active() {
			return this.mesh > 0 && this.atlas > 0 && this.session > 0;
		}
	}

	/** What a mesh belongs to: the host process, this world, this dimension. */
	private record Context(long hostPid, long epoch, long map) {
	}

	private static final BlockMeshMailbox MAILBOX = new BlockMeshMailbox();
	private static final BlockMeshHandoff HANDOFF = new BlockMeshHandoff();
	private static final BlockMeshChanges CHANGES = new BlockMeshChanges();
	private static final BlockMeshAnimation ANIMATION = new BlockMeshAnimation();
	private static final ThreadPoolExecutor PAYLOAD_WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
		Thread thread = new Thread(task, "passthrough mesh payload");
		thread.setDaemon(true);
		return thread;
	});
	private static final long PID = ProcessHandle.current().pid();
	private static final int[] QUAD_TRIANGLES = {0, 1, 2, 0, 2, 3};

	/** Animated blocks whose sprites can't be animated natively, rendered live in the world picture instead. */
	static final int MAX_LIVE = 768;

	private static final Map<Long, MovingBlockRenderState> liveStates = new HashMap<>();
	private static long fullGeneration = 1, resourceGeneration = 1, lightContext = 1, revision, atlasRevision, lightRevision,
		sessionCounter = System.currentTimeMillis(), epochCounter = System.currentTimeMillis();
	private static final Set<Long> dirtySections = new HashSet<>();
	private static final Object LIGHT_LOCK = new Object();
	private static final Set<Long> pendingLightSections = new HashSet<>();
	private static long nextLight, nextGeometryLight, reportTime, retryBuild;
	/** Visible-section coverage is checked several times per frame; one scan suffices. */
	private static long frameStamp;
	private static boolean initialized, failed, closed, atlasPending, lightPending, reportedExclusion;
	private static Context context;
	private static BlockMeshProtocol.Identity identity;
	private static ClientLevel level, epochLevel;
	private static long levelEpoch;
	private static Object models;
	private static GpuTexture atlasTexture;
	/** The block atlas texture location that baked block sprites report. */
	private static volatile Identifier atlasLocation;
	private static GpuTexture reportedLightTexture;
	private static byte[] atlasPixels, lightPixels, publishedPayload;
	private static int atlasWidth, atlasHeight, atlasMips = 1;
	private static boolean atlasPublished, relight, allLightSections;
	/** installed: the latest published candidate; displayed: the exact acknowledged snapshot. */
	private static Build build, installed, displayed;
	private static int[] publishedCounts;
	private static Exclusion exclusion = Exclusion.NONE;
	private static String fallback = "waiting for the host";

	private BlockMeshClient() {
	}

	/** Whether native blocks may run: a host is attached (and said which process it is), and the option is on. */
	static boolean enabled() {
		return Passthrough.active && Config.nativeBlocks && !Boolean.getBoolean("passthrough.noNativeBlocks") && HostState.hostPid() > 0;
	}

	/** GameRenderer.renderLevel HEAD: no exclusion until this frame's beginFrame establishes one. */
	public static void resetFrame() {
		exclusion = Exclusion.NONE;
	}

	public static Exclusion exclusion() {
		return exclusion;
	}

	/** The identity the host draws this frame's blocks with (null while the world picture still has its chunks). */
	static BlockMeshProtocol.Identity presentationIdentity() {
		return exclusion.active() ? identity : null;
	}

	public static boolean suppressChunks() {
		return exclusion.active();
	}

	/** Resource or option invalidation may change both faces and their shading. */
	public static void dirty() {
		CHANGES.geometryChanged();
		fullGeneration++;
		dirtySections.clear();
		retryBuild = 0;
	}

	public static void dirty(final BlockPos pos, final BlockState before, final BlockState after) {
		if (before == after || (!real(before) && !real(after))) {
			return;
		}

		CHANGES.geometryChanged();
		retryBuild = 0;
		// neighbour culling and ambient occlusion sample across section edges
		for (int x = (pos.getX() >> 4) - 1; x <= (pos.getX() >> 4) + 1; x++) {
			for (int y = (pos.getY() >> 4) - 1; y <= (pos.getY() >> 4) + 1; y++) {
				for (int z = (pos.getZ() >> 4) - 1; z <= (pos.getZ() >> 4) + 1; z++) {
					dirtySections.add(SectionPos.asLong(x, y, z));
				}
			}
		}

		if (dirtySections.size() > 4096) {
			dirty();
		}
	}

	/**
	 * Streaming one chunk must not discard every cached section in the render area. A chunk holding only air and the host's
	 * ground (barriers, which neither render nor occlude) changes no face, so the render distance edge streams freely.
	 */
	public static void chunkChanged(final ChunkPos pos, final LevelChunk chunk) {
		Build owner = build != null ? build : installed;
		if (owner == null) {
			return;
		}

		BlockMeshCoverage area = owner.window;
		if (!area.intersectsChunkNeighborhood(pos.x(), pos.z())) {
			return;
		}

		if (!hasReal(chunk) && !owner.hasGeometryIn(pos.x(), pos.z())) {
			return;
		}

		CHANGES.geometryChanged();
		retryBuild = 0;
		// neighbour sections can expose new faces when the adjacent chunk arrives or leaves
		for (int x = pos.x() - 1; x <= pos.x() + 1; x++) {
			for (int z = pos.z() - 1; z <= pos.z() + 1; z++) {
				for (int y = area.minY(); y <= area.maxY(); y++) {
					if (area.contains(x, y, z)) {
						dirtySections.add(SectionPos.asLong(x, y, z));
					}
				}
			}
		}

		if (dirtySections.size() > 4096) {
			dirty();
		}
	}

	/** Propagated block/sky light changes bypass ClientLevel.setBlocksDirty in 26.3. May come from a lighting worker. */
	public static void lightDirty(final SectionPos section) {
		synchronized (LIGHT_LOCK) {
			if (allLightSections) {
				return;
			}

			for (int x = section.x() - 1; x <= section.x() + 1; x++) {
				for (int y = section.y() - 1; y <= section.y() + 1; y++) {
					for (int z = section.z() - 1; z <= section.z() + 1; z++) {
						pendingLightSections.add(SectionPos.asLong(x, y, z));
					}
				}
			}

			if (pendingLightSections.size() > 4096) {
				pendingLightSections.clear();
				allLightSections = true;
			}
		}
	}

	public static void tintDirty() {
		synchronized (LIGHT_LOCK) {
			pendingLightSections.clear();
			allLightSections = true;
		}
	}

	private static void clearLightPending() {
		synchronized (LIGHT_LOCK) {
			pendingLightSections.clear();
			allLightSections = false;
		}
	}

	private static void refreshLightSections(final long now) {
		// let initial coverage finish; then refresh affected cached vertex-light UVs at most 2 Hz
		if (installed == null || build != null || now < nextGeometryLight) {
			return;
		}

		nextGeometryLight = now + 500_000_000L;
		boolean changed = false;
		synchronized (LIGHT_LOCK) {
			for (long key : installed.geometry.keySet()) {
				if (allLightSections || pendingLightSections.contains(key)) {
					dirtySections.add(key);
					changed = true;
				}
			}

			pendingLightSections.clear();
			allLightSections = false;
		}

		if (changed) {
			CHANGES.lightingChanged();
			retryBuild = 0;
		}
	}

	private static Context currentContext(final Minecraft client) {
		if (client.level != epochLevel) {
			epochLevel = client.level;
			levelEpoch = ++epochCounter;
		}

		return new Context(HostState.hostPid(), levelEpoch, client.level.dimension().toString().hashCode() & 0x7fffffffL);
	}

	/** LevelRenderer.render, right after vanilla repositioned its ViewArea and before any chunk or transient submission. */
	public static void beginFrame() {
		exclusion = Exclusion.NONE;
		frameStamp++;
		if (failed || closed) {
			return;
		}

		String phase = "host readiness";
		try {
			Minecraft client = Minecraft.getInstance();
			if (!enabled() || client.level == null) {
				// quiet while no host is attached; otherwise say why
				if (initialized && Passthrough.active) {
					reportFallback(HostState.hostPid() == 0 ? "the host didn't say which process it is (an older GTA side)"
						: !Config.nativeBlocks ? "render.nativeBlocks=false" : "-Dpassthrough.noNativeBlocks or no world");
				}

				pause();
				return;
			}

			var target = client.gameRenderer.mainRenderTarget();
			// the host draws the mesh at its own resolution; only the exclusion needs an exported frame
			if (!FrameExporter.exporting() || target.width < 1 || target.height < 1 || target.width > GpuTransport.MAX_W || target.height > GpuTransport.MAX_H) {
				reportFallback("frame export unavailable or oversized: " + target.width + "x" + target.height);
				pause();
				return;
			}

			phase = "mapping initialization";
			if (!initialized) {
				MAILBOX.initialize();
				initialized = true;
			}

			phase = "mesh identity";
			Context next = currentContext(client);
			if (!next.equals(context) || level != client.level) {
				context = next;
				level = client.level;
				identity = new BlockMeshProtocol.Identity(PID, next.hostPid(), next.epoch(), next.map(), ++sessionCounter, 1);
				installed = null;
				clearBuild();
				CHANGES.geometryChanged();
				atlasPublished = false;
				retryBuild = 0;
				resetLightContext();
				invalidateHandoff();
			}

			phase = "block atlas lookup";
			// AtlasManager indexes definition IDs; texture paths belong to TextureManager instead
			var atlas = client.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
			GpuTexture texture = atlas.getTexture();
			atlasLocation = atlas.location();
			Object nextModels = client.getModelManager().getBlockStateModelSet();
			if (texture != atlasTexture || models != nextModels) {
				atlasTexture = texture;
				models = nextModels;
				resourceGeneration++;
				atlasPixels = null;
				ANIMATION.clear();
				resetLightContext();
				atlasPublished = false;
				installed = null;
				clearBuild();
				CHANGES.geometryChanged();
				retryBuild = 0;
				invalidateHandoff();
				Passthrough.LOG.info("block mesh atlas: {}, texture {}, {}x{}, {}, layers {}, mips {}", AtlasIds.BLOCKS, atlasLocation, texture.getWidth(0),
					texture.getHeight(0), texture.getFormat(), texture.getDepthOrLayers(), texture.getMipLevels());
			}

			long now = System.nanoTime();
			phase = "texture readback";
			requestTextures(client, now);
			refreshLightSections(now);
			phase = "render-area coverage";
			var area = client.levelRenderer.viewArea();
			if (area == null) {
				reportFallback("vanilla ViewArea unavailable");
				pause();
				return;
			}

			var center = area.getCameraSectionPos();
			BlockMeshCoverage visibleWindow = new BlockMeshCoverage(center.x(), center.z(), area.getViewDistance(), area.minSectionY(), area.maxSectionY());
			BlockMeshCoverage window = visibleWindow.withStreamingMargin();
			boolean current = installed != null && installed.valid(window);
			// edits, streaming chunks and reloaded chunk objects rebuild in place while the displayed snapshot stays; only a
			// lost context or a view beyond the prebuilt border (a teleport) switches the world back to the RGB-D picture
			if (installed != null && !installed.containsWindow(visibleWindow)) {
				invalidateHandoff();
			}

			phase = "atlas publication";
			if (atlasPixels != null && !atlasPublished) {
				MAILBOX.atlas(BlockMeshProtocol.header(BlockMeshProtocol.ATLAS_MAGIC, identity, MAILBOX.now(), atlasRevision, atlasRevision, atlasWidth,
					atlasHeight, atlasPixels.length, 0, 0, 0, true, atlasMips), ByteBuffer.wrap(atlasPixels));
				atlasPublished = true;
			}

			phase = "mesh acknowledgement";
			if (atlasPublished && relight && lightPixels != null) {
				MAILBOX.lighting(BlockMeshProtocol.header(BlockMeshProtocol.LIGHT_MAGIC, identity, MAILBOX.now(), ++lightRevision, atlasRevision, 16, 16,
					1024, 0, 0, 0, true), lightPixels);
				relight = false;
			}

			BlockMeshHandoff.Revision resident = HANDOFF.select(MAILBOX.acknowledgement(identity));
			if (resident == null) {
				displayed = null;
			} else if (resident.mesh() == revision) {
				displayed = installed;
			}

			if (displayed != null) {
				CHANGES.displayed(displayed.generation);
			}

			// light propagation and tint refreshes never make known faces incomplete; only an actual block change left
			// undisplayed past the grace revokes the mesh
			boolean retained = displayed != null && displayed.containsWindow(visibleWindow) && CHANGES.withinGrace(displayed.generation, now);
			if (displayed != null && !retained) {
				invalidateHandoff();
				resident = null;
			}

			if (!current && build != null && !build.valid(window)) {
				build.cancelPayload();
				build = null;
			}

			phase = "block geometry";
			if ((!current || !HANDOFF.hasLatest()) && build == null && atlasPublished && lightPixels != null && now >= retryBuild) {
				build = new Build(client, window, installed);
			}

			if (build != null) {
				try {
					MeshPayload prepared = build.step(client) ? build.payload() : null;
					if (prepared != null && (!HANDOFF.hasLatest() || HANDOFF.canPublishColor())) {
						byte[] payload = prepared.bytes();
						int[] counts = prepared.counts();
						boolean changed = !HANDOFF.hasLatest() || prepared.changed();
						if (changed) {
							boolean replacing = HANDOFF.hasLatest();
							MAILBOX.mesh(BlockMeshProtocol.header(BlockMeshProtocol.MESH_MAGIC, identity, MAILBOX.now(), ++revision, atlasRevision,
								payload.length / BlockMeshProtocol.LIT_STRIDE, BlockMeshProtocol.LIT_STRIDE, payload.length, counts[0], counts[1], counts[2], true), payload);
							BlockMeshHandoff.Revision update = new BlockMeshHandoff.Revision(revision, atlasRevision, identity.session());
							if (replacing) {
								HANDOFF.update(update);
							} else {
								HANDOFF.geometry(update);
							}

							publishedPayload = payload;
							publishedCounts = counts;
							Passthrough.LOG.info("block mesh published: revision {}, atlas {}, blocks {}, vertices {}, chunks {}, retained {}", revision,
								atlasRevision, build.blocks, payload.length / BlockMeshProtocol.LIT_STRIDE, build.chunks.size(), resident != null);
						} else {
							// propagated-light dirtiness can leave every actually shaded vertex unchanged
							displayed = build;
							CHANGES.displayed(build.generation);
						}

						installed = build;
						build = null;
						current = true;
						dirtySections.clear();
						relight = false;
						fallback = "waiting for the host to acknowledge the mesh";
					}
				} catch (UnsupportedOperationException unsupported) {
					fallback = unsupported.getMessage();
					clearBuild();
					installed = null;
					invalidateHandoff();
					resident = null;
					retryBuild = now + 1_000_000_000L;
				}
			}

			current = installed != null && installed.valid(window) && installed.coversVisible(client);
			retained = resident != null && displayed != null && displayed.containsWindow(visibleWindow) && CHANGES.withinGrace(displayed.generation, now);
			// a pending upload can itself become dirty before its first acknowledgement: keep its bounded heartbeat alive so
			// the host can acknowledge it and unblock the newer build; otherwise neither side could advance
			boolean publishedUsable = HANDOFF.hasLatest() && installed != null && installed.containsWindow(visibleWindow)
				&& (current || retained || CHANGES.withinGrace(installed.generation, now));
			MAILBOX.heartbeat(atlasPublished && publishedUsable);
			// water, lava, fire and portals advance with the game tick inside the host's atlas
			if (atlasPublished && installed != null && !ANIMATION.isEmpty()) {
				ANIMATION.publish(MAILBOX, identity, atlasRevision, atlasWidth, atlasHeight, atlasMips, client.level.getGameTime(), MAILBOX.now(), publishedUsable);
			}

			if (retained) {
				exclusion = new Exclusion(resident.mesh(), resident.atlas(), resident.session());
			}

			if (exclusion.active() != reportedExclusion) {
				reportedExclusion = exclusion.active();
				Passthrough.LOG.info("native blocks {}: {}", reportedExclusion ? "on" : "off",
					reportedExclusion ? "the host holds this exact mesh and atlas; chunks leave the exported picture" : fallback);
			}

			if (!current && !retained) {
				reportFallback(fallback);
			}
		} catch (IllegalArgumentException bounds) {
			reportFallback(phase + " rejected: " + bounds.getMessage());
			pause();
		} catch (Throwable failure) {
			failed = true;
			exclusion = Exclusion.NONE;
			MAILBOX.close();
			Passthrough.LOG.warn("native blocks disabled, chunks stay in the exported picture: {}", failure.toString());
		}
	}

	private static void reportFallback(final String reason) {
		fallback = reason;
		long now = System.nanoTime();
		if (now - reportTime < 5_000_000_000L) {
			return;
		}

		reportTime = now;
		Passthrough.LOG.info("native blocks waiting: {}; atlas ready {} (pending {}), lightmap ready {} (pending {}), building {}, resident {}, candidate {}",
			reason, atlasPixels != null, atlasPending, lightPixels != null, lightPending, build != null, displayed != null, HANDOFF.hasLatest());
	}

	private static void invalidateHandoff() {
		HANDOFF.invalidate();
		MAILBOX.clearAcknowledgement();
		publishedPayload = null;
		publishedCounts = null;
		displayed = null;
	}

	private static void resetLightContext() {
		lightContext++;
		lightPixels = null;
		relight = false;
		nextLight = nextGeometryLight = 0;
		clearLightPending();
	}

	private static void clearBuild() {
		if (build != null) {
			build.cancelPayload();
		}

		build = null;
	}

	private static void pause() {
		exclusion = Exclusion.NONE;
		if (context != null) {
			resetLightContext();
		}

		context = null;
		identity = null;
		clearBuild();
		installed = null;
		atlasPublished = false;
		invalidateHandoff();
		clearLightPending();
		try {
			if (initialized) {
				MAILBOX.heartbeat(false);
			}
		} catch (Throwable ignored) {
			// next frame
		}
	}

	private static void requestTextures(final Minecraft client, final long now) {
		if (atlasPixels == null && !atlasPending) {
			GpuTexture texture = atlasTexture;
			int width = texture.getWidth(0), height = texture.getHeight(0);
			if (width < 1 || height < 1 || width > 4096 || height > 4096 || texture.getFormat() != GpuFormat.RGBA8_UNORM || texture.getDepthOrLayers() != 1) {
				throw new IllegalArgumentException("unsupported block atlas: " + width + "x" + height + " " + texture.getFormat() + " layers=" + texture.getDepthOrLayers());
			}

			// the whole mip chain: distant native blocks sample filtered levels, as vanilla's do
			int mips = Math.min(texture.getMipLevels(), BlockMeshProtocol.maxMips(width, height));
			while (mips > 1 && BlockMeshProtocol.mipOffset(width, height, mips) > BlockMeshProtocol.ATLAS_BYTES - BlockMeshProtocol.HEADER) {
				mips--;
			}

			for (int m = 1; m < mips; m++) {
				if (texture.getWidth(m) != BlockMeshProtocol.mipExtent(width, m) || texture.getHeight(m) != BlockMeshProtocol.mipExtent(height, m)) {
					mips = m;
					break;
				}
			}

			final int levels = Math.max(1, mips);
			atlasPending = true;
			long generation = resourceGeneration;
			try {
				readAtlasChain(texture, width, height, levels, bytes -> {
					atlasPending = false;
					if (closed || generation != resourceGeneration) {
						return;
					}

					atlasPixels = bytes;
					atlasWidth = width;
					atlasHeight = height;
					atlasMips = levels;
					atlasRevision++;
					atlasPublished = false;
					Passthrough.LOG.info("block mesh atlas read back: revision {}, {} bytes, {} mips", atlasRevision, bytes.length, levels);
				}, generation);
			} catch (Throwable failure) {
				atlasPending = false;
				throw failure;
			}
		}

		if (!lightPending && now >= nextLight) {
			var view = client.gameRenderer.levelLightmap();
			if (view == null) {
				fallback = "vanilla lightmap unavailable";
				return;
			}

			GpuTexture texture = view.texture();
			if (texture != reportedLightTexture) {
				reportedLightTexture = texture;
				Passthrough.LOG.info("block mesh lightmap: {}x{}, {}, layers {}", texture.getWidth(0), texture.getHeight(0), texture.getFormat(), texture.getDepthOrLayers());
			}

			if (texture.getWidth(0) != 16 || texture.getHeight(0) != 16 || texture.getFormat() != GpuFormat.RGBA8_UNORM) {
				fallback = "unsupported vanilla lightmap size/format";
				return;
			}

			lightPending = true;
			nextLight = now + 500_000_000L;
			long generation = resourceGeneration, scene = lightContext;
			try {
				readTexture(texture, 1024, bytes -> {
					lightPending = false;
					if (closed || generation != resourceGeneration || scene != lightContext) {
						return;
					}

					if (!BlockMeshProtocol.renderedLightmap(bytes)) {
						nextLight = 0;
						fallback = "waiting for the first rendered vanilla lightmap";
						return;
					}

					// light-only changes are batched at 2 Hz; a stable light changes no geometry revision
					if (!Arrays.equals(lightPixels, bytes)) {
						lightPixels = bytes;
						relight = true;
					}
				}, generation, scene);
			} catch (Throwable failure) {
				lightPending = false;
				throw failure;
			}
		}
	}

	private static void readTexture(final GpuTexture texture, final int size, final Consumer<byte[]> complete, final long generation, final long scene) {
		GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "passthrough lightmap readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size);
		try {
			RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
				try {
					if (closed || generation != resourceGeneration || scene != lightContext) {
						return;
					}

					try (var mapped = buffer.map(true, false)) {
						byte[] bytes = new byte[size];
						mapped.data().get(bytes);
						complete.accept(bytes);
					}
				} catch (Throwable failure) {
					failed = true;
					Passthrough.LOG.warn("block mesh lightmap readback failed, chunks stay: {}", failure.toString());
				} finally {
					lightPending = false;
					buffer.close();
				}
			}, 0);
		} catch (Throwable failure) {
			buffer.close();
			throw failure;
		}
	}

	/** Every level of the atlas, tightly packed largest first, from one readback buffer. */
	private static void readAtlasChain(final GpuTexture texture, final int width, final int height, final int levels, final Consumer<byte[]> complete,
		final long generation) {
		long total = BlockMeshProtocol.mipOffset(width, height, levels);
		GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "passthrough atlas readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, total);
		int[] remaining = {levels};
		try {
			var encoder = RenderSystem.getDevice().createCommandEncoder();
			for (int level = 0; level < levels; level++) {
				encoder.copyTextureToBuffer(texture, buffer, BlockMeshProtocol.mipOffset(width, height, level), () -> {
					if (--remaining[0] > 0) {
						return;
					}

					try {
						if (closed || generation != resourceGeneration) {
							return;
						}

						try (var mapped = buffer.map(true, false)) {
							byte[] bytes = new byte[(int)total];
							mapped.data().get(bytes);
							complete.accept(bytes);
						}
					} catch (Throwable failure) {
						failed = true;
						Passthrough.LOG.warn("block mesh atlas readback failed, chunks stay: {}", failure.toString());
					} finally {
						atlasPending = false;
						buffer.close();
					}
				}, level);
			}
		} catch (Throwable failure) {
			// copies already submitted still complete; only the last callback closes the buffer
			remaining[0] = Integer.MAX_VALUE;
			throw failure;
		}
	}

	private record Section(LevelChunkSection section, int x, int y, int z) {
		long key() {
			return SectionPos.asLong(this.x, this.y, this.z);
		}
	}

	/** live: packed positions of animated blocks whose quads stay out of the static mesh. */
	private record Geometry(byte[][] layers, int blocks, long[] live) {
		int vertices() {
			int n = 0;
			for (byte[] bytes : this.layers) {
				n += bytes.length / BlockMeshProtocol.LIT_STRIDE;
			}

			return n;
		}
	}

	private static final class Build {
		final BlockMeshCoverage window;
		final BlockMeshChanges.Version generation = CHANGES.version();
		final long full = fullGeneration, resources = resourceGeneration;
		final Context ctx = context;
		final ModelBlockRenderer renderer;
		final FluidStateModelSet fluidModels;
		final FluidRenderer fluidRenderer;
		final BlockMeshFluids fluidCapture = new BlockMeshFluids();
		final Map<Long, LevelChunk> chunks = new HashMap<>();
		final Map<Long, Geometry> geometry = new TreeMap<>();
		final Set<Long> refreshSections = Set.copyOf(dirtySections);
		final Set<Long> refreshChunks = new HashSet<>();
		final List<Section> sections = new ArrayList<>();
		ByteArrayOutputStream[] output = newOutputs(), blockOutput = newOutputs();
		final List<Long> sectionLive = new ArrayList<>();
		boolean animated;
		int blockVertices, live;
		final ByteBuffer vertex = ByteBuffer.allocate(BlockMeshProtocol.LIT_STRIDE).order(ByteOrder.LITTLE_ENDIAN);
		int chunkCursor, sectionCursor, cell, blocks, vertices, sectionBlocks;
		final int width;
		boolean collecting = true;
		long coverFrame = -1;
		boolean coverValue;
		CompletableFuture<MeshPayload> payload;

		Build(final Minecraft client, final BlockMeshCoverage window, final Build previous) {
			this.window = window;
			this.width = window.radius() * 2 + 1;
			this.renderer = new ModelBlockRenderer(client.options.ambientOcclusion().get(), true, client.getBlockColors());
			this.fluidModels = client.getModelManager().getFluidStateModelSet();
			this.fluidRenderer = new FluidRenderer(this.fluidModels);
			fallback = "covering the whole render area";
			for (long key : this.refreshSections) {
				this.refreshChunks.add(ChunkPos.pack(SectionPos.x(key), SectionPos.z(key)));
			}

			if (previous != null && previous.full == this.full && previous.resources == this.resources && previous.ctx.equals(this.ctx)) {
				// keep the immutable overlap, known-empty chunks included: moving one section scans only the entering border
				for (Map.Entry<Long, LevelChunk> entry : previous.chunks.entrySet()) {
					var pos = entry.getValue().getPos();
					if (window.contains(pos.x(), window.minY(), pos.z())) {
						this.chunks.put(entry.getKey(), entry.getValue());
					}
				}

				for (Map.Entry<Long, Geometry> entry : previous.geometry.entrySet()) {
					long key = entry.getKey();
					if (window.contains(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key))) {
						this.geometry.put(key, entry.getValue());
					}
				}

				for (Geometry value : this.geometry.values()) {
					this.vertices += value.vertices();
					this.blocks += value.blocks();
					this.live += value.live().length;
				}

				fallback = previous.window.equals(window) ? "refreshing changed block sections" : "extending the prebuilt render-area border";
			}
		}

		boolean valid(final BlockMeshCoverage now) {
			return CHANGES.current(this.generation) && this.sameBasis(now);
		}

		boolean hasGeometryIn(final int chunkX, final int chunkZ) {
			for (long key : this.geometry.keySet()) {
				if (SectionPos.x(key) == chunkX && SectionPos.z(key) == chunkZ) {
					return true;
				}
			}

			return false;
		}

		/** Same producer context and a prebuilt window round the current vanilla view. */
		boolean containsWindow(final BlockMeshCoverage now) {
			return this.sameContext() && this.window.contains(now);
		}

		private boolean sameContext() {
			return this.resources == resourceGeneration && this.ctx.equals(context);
		}

		private boolean sameBasis(final BlockMeshCoverage now) {
			return this.resources == resourceGeneration && this.ctx.equals(context) && this.window.equals(now);
		}

		boolean step(final Minecraft client) {
			long deadline = System.nanoTime() + 2_000_000L;
			int work = 0;
			// chunk visits are palette checks: the deadline, not a small count, bounds them, so a whole 32-chunk render area
			// is covered in a few frames
			while (this.collecting && this.chunkCursor < this.width * this.width && work < 2048 && System.nanoTime() < deadline) {
				int x = this.window.centerX() - this.window.radius() + this.chunkCursor % this.width;
				int z = this.window.centerZ() - this.window.radius() + this.chunkCursor / this.width;
				this.chunkCursor++;
				long key = ChunkPos.pack(x, z);
				LevelChunk previous = this.chunks.get(key);
				LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
				if (chunk != null && chunk == previous && !this.refreshChunks.contains(key)) {
					continue;
				}

				work++;
				this.coverFrame = -1;
				if (chunk == null) {
					this.chunks.remove(key);
					if (previous != null) {
						for (int y = this.window.minY(); y <= this.window.maxY(); y++) {
							this.removeSection(SectionPos.asLong(x, y, z));
						}
					}

					continue;
				}

				this.chunks.put(key, chunk);
				LevelChunkSection[] all = chunk.getSections();
				for (int i = 0; i < all.length; i++) {
					int y = chunk.getMinSectionY() + i;
					if (y < this.window.minY() || y > this.window.maxY()) {
						continue;
					}

					long section = SectionPos.asLong(x, y, z);
					if (chunk == previous && !this.refreshSections.contains(section)) {
						continue;
					}

					this.removeSection(section);
					if (all[i].maybeHas(BlockMeshClient::real)) {
						this.sections.add(new Section(all[i], x, y, z));
					}
				}
			}

			this.collecting = this.chunkCursor < this.width * this.width;
			if (this.collecting) {
				return false;
			}

			work = 0;
			while (this.sectionCursor < this.sections.size() && work++ < 4096 && System.nanoTime() < deadline) {
				Section s = this.sections.get(this.sectionCursor);
				int x = this.cell & 15, z = (this.cell >>> 4) & 15, y = this.cell >>> 8;
				BlockState state = s.section.getBlockState(x, y, z);
				if (real(state)) {
					this.sectionBlocks++;
					this.blocks++;
					BlockPos pos = new BlockPos(s.x * 16 + x, s.y * 16 + y, s.z * 16 + z);
					// water, lava and waterlogged blocks: vanilla fluid geometry in the native mesh
					FluidState fluid = state.getFluidState();
					if (!fluid.isEmpty()) {
						this.tesselateFluid(pos, state, fluid, s);
					}

					if (state.getRenderShape() == RenderShape.MODEL) {
						var model = client.getModelManager().getBlockStateModelSet().get(state);
						boolean opaque = ModelBlockRenderer.forceOpaque(client.options.cutoutLeaves().get(), state);
						this.animated = false;
						this.blockVertices = 0;
						for (ByteArrayOutputStream out : this.blockOutput) {
							out.reset();
						}

						this.renderer.tesselateBlock((ox, oy, oz, quad, instance) -> {
							var material = quad.materialInfo();
							// fire, magma and the portal advance through the native animation channel; only a sprite that
							// channel can't read still renders live instead
							if (material.sprite().isAnimated() && !ANIMATION.add(material.sprite(), atlasWidth)) {
								this.animated = true;
								return;
							}

							if (!material.sprite().atlasLocation().equals(atlasLocation)) {
								throw new UnsupportedOperationException("a block material outside the block atlas");
							}

							int layer = opaque ? 0 : switch (material.layer()) {
								case SOLID -> 0;
								case CUTOUT -> 1;
								case TRANSLUCENT -> 2;
							};
							if (this.vertices + this.blockVertices + 6 > BlockMeshProtocol.MAX_VERTICES) {
								throw new UnsupportedOperationException("block mesh vertex budget");
							}

							for (int index : QUAD_TRIANGLES) {
								var p = quad.position(index);
								long uv = quad.packedUV(index);
								this.vertex.clear();
								this.vertex.putFloat(ox + p.x()).putFloat(oy + p.y()).putFloat(oz + p.z()).putFloat(UVPair.unpackU(uv)).putFloat(UVPair.unpackV(uv))
									.putInt(instance.getColor(index)).putInt(instance.getLightCoordsWithEmission(index, material.lightEmission()));
								this.blockOutput[layer].writeBytes(this.vertex.array());
								this.blockVertices++;
							}
						}, (float)pos.getX(), (float)pos.getY(), (float)pos.getZ(), level, pos, state, model, state.getSeed(pos));
						if (this.animated) {
							if (++this.live > MAX_LIVE) {
								throw new UnsupportedOperationException("more than " + MAX_LIVE + " animated blocks in the render area");
							}

							this.sectionLive.add(pos.asLong());
						} else {
							for (int i = 0; i < 3; i++) {
								this.output[i].writeBytes(this.blockOutput[i].toByteArray());
							}

							this.vertices += this.blockVertices;
						}
					}
				}

				this.cell++;
				if (this.cell == 4096) {
					// a palette can still mention removed states after its last real block went: empty sections need no
					// repeated propagated-light work
					if (this.sectionBlocks == 0) {
						this.geometry.remove(s.key());
					} else {
						this.geometry.put(s.key(), new Geometry(new byte[][] {this.output[0].toByteArray(), this.output[1].toByteArray(), this.output[2].toByteArray()},
							this.sectionBlocks, this.sectionLive.stream().mapToLong(Long::longValue).toArray()));
					}

					this.output = newOutputs();
					this.sectionLive.clear();
					this.sectionBlocks = 0;
					this.cell = 0;
					this.sectionCursor++;
				}
			}

			return this.sectionCursor == this.sections.size();
		}

		/** One fluid block through vanilla's renderer, straight into the section's layers. */
		private void tesselateFluid(final BlockPos pos, final BlockState state, final FluidState fluid, final Section s) {
			var model = this.fluidModels.get(fluid);
			for (Material.Baked material : new Material.Baked[] {model.stillMaterial(), model.flowingMaterial(), model.overlayMaterial()}) {
				if (material != null) {
					if (!material.sprite().atlasLocation().equals(atlasLocation)) {
						throw new UnsupportedOperationException("a fluid material outside the block atlas");
					}

					if (material.sprite().isAnimated()) {
						ANIMATION.add(material.sprite(), atlasWidth);
					}
				}
			}

			this.fluidCapture.begin(s.x * 16, s.y * 16, s.z * 16);
			this.fluidRenderer.tesselate(level, pos, this.fluidCapture, state, fluid);
			for (int layer = 0; layer < 3; layer++) {
				byte[] triangles = this.fluidCapture.layer(layer);
				if (this.vertices + triangles.length / BlockMeshProtocol.LIT_STRIDE > BlockMeshProtocol.MAX_VERTICES) {
					throw new UnsupportedOperationException("block mesh vertex budget");
				}

				this.output[layer].writeBytes(triangles);
				this.vertices += triangles.length / BlockMeshProtocol.LIT_STRIDE;
			}
		}

		private void removeSection(final long key) {
			Geometry old = this.geometry.remove(key);
			if (old != null) {
				this.vertices -= old.vertices();
				this.blocks -= old.blocks();
				this.live -= old.live().length;
			}
		}

		boolean coversVisible(final Minecraft client) {
			if (this.coverFrame != frameStamp) {
				this.coverValue = this.scanVisible(client);
				this.coverFrame = frameStamp;
			}

			return this.coverValue;
		}

		private boolean scanVisible(final Minecraft client) {
			for (var section : client.levelRenderer.visibleSections()) {
				if (!section.getSectionMesh().hasRenderableLayers()) {
					continue;
				}

				var p = section.getRenderOrigin();
				int x = p.getX() >> 4, y = p.getY() >> 4, z = p.getZ() >> 4;
				if (!this.window.contains(x, y, z) || this.chunks.get(ChunkPos.pack(x, z)) != level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false)) {
					return false;
				}

				if (!this.chunks.containsKey(ChunkPos.pack(x, z))) {
					return false;
				}
			}

			return true;
		}

		MeshPayload payload() {
			if (this.payload == null) {
				// geometry arrays are immutable after tessellation and only the render thread reads chunks: the worker joins
				// and compares bytes and holds at most one queued job
				List<byte[][]> parts = this.geometry.values().stream().map(Geometry::layers).toList();
				byte[] previous = publishedPayload;
				int[] oldCounts = publishedCounts;
				CompletableFuture<MeshPayload> task = new CompletableFuture<>();
				try {
					PAYLOAD_WORKER.execute(() -> {
						if (task.isCancelled()) {
							return;
						}

						try {
							task.complete(MeshPayload.assemble(parts, previous, oldCounts));
						} catch (Throwable failure) {
							task.completeExceptionally(failure);
						}
					});
					this.payload = task;
				} catch (RejectedExecutionException busy) {
					return null;
				}
			}

			return this.payload.isDone() ? this.payload.join() : null;
		}

		void cancelPayload() {
			if (this.payload != null) {
				this.payload.cancel(false);
			}
		}

		private static ByteArrayOutputStream[] newOutputs() {
			return new ByteArrayOutputStream[] {new ByteArrayOutputStream(), new ByteArrayOutputStream(), new ByteArrayOutputStream()};
		}
	}

	/** A block Minecraft draws: not air, not the host's ground (barriers stand in for GTA's collision). */
	private static boolean real(final BlockState state) {
		return !state.isAir() && !state.is(Blocks.BARRIER);
	}

	private static boolean hasReal(final LevelChunk chunk) {
		if (chunk == null) {
			return false;
		}

		for (LevelChunkSection section : chunk.getSections()) {
			if (!section.hasOnlyAir() && section.maybeHas(BlockMeshClient::real)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * While the native mesh replaces vanilla chunks, the animated blocks it left out are submitted like vanilla transient
	 * blocks, with the current animated atlas. From LevelRenderer.submitTransientBlocks.
	 */
	public static void submitLiveBlocks(final PoseStack pose, final SubmitNodeCollector collector, final Vec3 camera) {
		Build shown = displayed;
		ClientLevel world = level;
		if (!suppressChunks() || shown == null || world == null || camera == null) {
			liveStates.clear();
			return;
		}

		Set<Long> keep = new HashSet<>();
		int count = 0;
		for (Geometry section : shown.geometry.values()) {
			for (long key : section.live()) {
				if (++count > MAX_LIVE) {
					break;
				}

				BlockPos pos = BlockPos.of(key);
				BlockState state = world.getBlockState(pos);
				if (!real(state) || state.getRenderShape() != RenderShape.MODEL) {
					continue;
				}

				keep.add(key);
				MovingBlockRenderState live = liveStates.computeIfAbsent(key, k -> {
					MovingBlockRenderState m = new MovingBlockRenderState();
					m.randomSeedPos = pos;
					m.blockPos = pos;
					return m;
				});
				live.blockState = state;
				live.biome = world.getBiome(pos);
				live.cardinalLighting = world.cardinalLighting();
				live.lightEngine = world.getLightEngine();
				pose.pushPose();
				pose.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
				collector.submitMovingBlock(pose, live, 0);
				pose.popPose();
			}
		}

		liveStates.keySet().retainAll(keep);
	}

	public static void close() {
		closed = true;
		if (build != null) {
			build.cancelPayload();
		}

		PAYLOAD_WORKER.shutdownNow();
		exclusion = Exclusion.NONE;
		build = installed = null;
		atlasPixels = lightPixels = null;
		invalidateHandoff();
		clearLightPending();
		MAILBOX.close();
	}
}
