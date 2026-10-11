package dev.rehan.passthrough.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.rehan.passthrough.Passthrough;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

/**
 * While native blocks are on, the details drawn on blocks go native too: vanilla's real crack models and UVs while mining,
 * and the selection outline's edges, in their own small mesh beside the immutable block mesh (the host draws them over its
 * native blocks, with the same camera). If anything fails, vanilla draws them as before. Adapted from EldenCraft's
 * BlockDetails (MIT, Copyright (c) 2026 Adam Grzegorzewski).
 */
public final class BlockDetails {
	private static final int MAX_VERTICES = 16380;
	private static final BlockMeshMailbox MAILBOX = new BlockMeshMailbox("MCPassthroughBlockDetail", BlockMeshProtocol.DETAIL_MESH_BYTES,
		BlockMeshProtocol.DETAIL_ATLAS_BYTES, false);
	private static final ByteArrayOutputStream cracks = new ByteArrayOutputStream(), lines = new ByteArrayOutputStream();
	private static final ByteBuffer vertexBytes = ByteBuffer.allocate(BlockMeshProtocol.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
	private static BlockMeshProtocol.Identity identity;
	private static Vec3 camera;
	private static Object models;
	private static byte[] pixels, previous;
	private static int size, height, previousCracks;
	private static long atlasRevision, revision, report;
	private static boolean initialized, active, atlasPublished;

	private BlockDetails() {
	}

	/** LevelRenderer.submitFeatures HEAD. */
	public static void begin() {
		active = false;
		cracks.reset();
		lines.reset();
		Minecraft mc = Minecraft.getInstance();
		BlockMeshProtocol.Identity next = BlockMeshClient.presentationIdentity();
		if (next == null) {
			pause();
			return;
		}

		try {
			if (!initialized) {
				MAILBOX.initialize();
				initialized = true;
			}

			Object nextModels = mc.getModelManager().getBlockStateModelSet();
			if (!next.equals(identity) || models != nextModels) {
				identity = next;
				models = nextModels;
				pixels = null;
				atlasPublished = false;
				previous = null;
			}

			if (pixels == null) {
				loadTextures(mc);
			}

			camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
			if (!atlasPublished) {
				MAILBOX.atlas(BlockMeshProtocol.header(BlockMeshProtocol.ATLAS_MAGIC, identity, MAILBOX.now(), atlasRevision, atlasRevision, size, height,
					pixels.length, 0, 0, 0, true), ByteBuffer.wrap(pixels));
				atlasPublished = true;
			}

			active = true;
		} catch (Throwable error) {
			pixels = null;
			failure(error);
		}
	}

	/** The ten destroy stages stacked in one strip, plus one opaque white texel row for the outline. */
	private static void loadTextures(final Minecraft mc) throws Exception {
		List<NativeImage> images = new ArrayList<>();
		try {
			for (int stage = 0; stage < 10; stage++) {
				Identifier name = Identifier.withDefaultNamespace("textures/block/destroy_stage_" + stage + ".png");
				try (InputStream stream = mc.getResourceManager().getResourceOrThrow(name).open()) {
					images.add(NativeImage.read(stream));
				}
			}

			size = images.getFirst().getWidth();
			height = size * 10 + 1;
			if (size < 1 || size > 256) {
				throw new IllegalArgumentException("unsupported destroy texture size");
			}

			pixels = new byte[size * height * 4];
			for (int stage = 0; stage < 10; stage++) {
				NativeImage image = images.get(stage);
				if (image.getWidth() != size || image.getHeight() != size) {
					throw new IllegalArgumentException("destroy textures differ in size");
				}

				for (int y = 0; y < size; y++) {
					for (int x = 0; x < size; x++) {
						int argb = image.getPixel(x, y), at = ((stage * size + y) * size + x) * 4;
						pixels[at] = (byte)(argb >>> 16);
						pixels[at + 1] = (byte)(argb >>> 8);
						pixels[at + 2] = (byte)argb;
						pixels[at + 3] = (byte)(argb >>> 24);
					}
				}
			}

			Arrays.fill(pixels, size * size * 10 * 4, pixels.length, (byte)255);
			atlasRevision++;
		} finally {
			images.forEach(NativeImage::close);
		}
	}

	/** LevelRenderer.submitBlockDestroyAnimation: true when the crack went into the native details (vanilla skips it). */
	public static boolean breaking(final PoseStack pose, final List<BlockStateModelPart> parts, final int stage, final BlockStateModel model,
		final BlockBreakingRenderState state, final RandomSource random) {
		if (!active) {
			return false;
		}

		try {
			int before = cracks.size();
			BlockCrackGeometry.Sink sink = (x, y, z, u, v) -> vertex(cracks, x, y, z, u, v, -1);
			// Fabric redirects LevelRenderer's collectParts to a no-op, so the list handed here is empty: collect vanilla's
			// parts with its own per-position seed
			List<BlockStateModelPart> collected = new ArrayList<>(parts);
			if (collected.isEmpty()) {
				random.setSeed(state.blockState().getSeed(state.blockPos()));
				model.collectParts(random, collected);
			}

			BlockCrackGeometry.emit(pose, collected, stage, size, sink);
			if (cracks.size() == before) {
				// custom Fabric models may expose their quads only through emitQuads
				random.setSeed(state.blockState().getSeed(state.blockPos()));
				BlockCrackGeometry.emitFabric(pose, (net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel)model, state.blockPos(), state.blockState(),
					random, net.fabricmc.fabric.api.client.renderer.v1.Renderer.get(), stage, size, sink);
			}

			// no geometry from either route: the genuine submission draws the crack rather than nothing
			return cracks.size() != before;
		} catch (RuntimeException error) {
			failure(error);
			return false;
		}
	}

	/** LevelRenderer.submitHitOutline: the selection box's edges as thin native strips, at the vanilla edges exactly. */
	public static boolean outline(final PoseStack pose, final VoxelShape shape, final int argb, final float width) {
		if (!active) {
			return false;
		}

		try {
			HostState.Pose frame = HostState.frame();
			if (frame == null) {
				return false;
			}

			Minecraft mc = Minecraft.getInstance();
			shape.forAllEdges((x0, y0, z0, x1, y1, z1) -> {
				Vector3f a = pose.last().pose().transformPosition((float)x0, (float)y0, (float)z0, new Vector3f());
				Vector3f b = pose.last().pose().transformPosition((float)x1, (float)y1, (float)z1, new Vector3f());
				double distance = Math.max(.1, (a.length() + b.length()) * .5);
				float half = (float)Math.clamp(distance * Math.tan(Math.toRadians(frame.fov()) * .5) * width / Math.max(1, mc.gameRenderer.mainRenderTarget().height),
					.00025, .05);
				// two crossed strips round the exact vanilla edge give a steady raster thickness from every side
				Vector3f delta = new Vector3f(b).sub(a).normalize();
				Vector3f first = new Vector3f(Math.abs(delta.y) < .9 ? 0 : 1, Math.abs(delta.y) < .9 ? 1 : 0, 0).cross(delta).normalize().mul(half);
				Vector3f second = new Vector3f(delta).cross(first).normalize().mul(half);
				strip(a, b, first, argb);
				strip(a, b, second, argb);
			});
			return true;
		} catch (RuntimeException error) {
			failure(error);
			return false;
		}
	}

	private static void strip(final Vector3f a, final Vector3f b, final Vector3f side, final int argb) {
		Vector3f[] v = {new Vector3f(a).sub(side), new Vector3f(a).add(side), new Vector3f(b).add(side), new Vector3f(b).sub(side)};
		for (int index : new int[] {0, 1, 2, 0, 2, 3}) {
			vertex(lines, v[index].x, v[index].y, v[index].z, .5f, (height - .5f) / height, argb);
		}
	}

	/** Camera-relative (the pose stack's) position to world coordinates, then the 24-byte prelit vertex. */
	private static void vertex(final ByteArrayOutputStream out, final float x, final float y, final float z, final float u, final float v, final int argb) {
		if ((cracks.size() + lines.size()) / BlockMeshProtocol.STRIDE >= MAX_VERTICES) {
			throw new IllegalArgumentException("detail vertex limit");
		}

		ByteBuffer bytes = vertexBytes;
		bytes.clear();
		bytes.putFloat((float)(camera.x + x)).putFloat((float)(camera.y + y)).putFloat((float)(camera.z + z)).putFloat(u).putFloat(v)
			.put((byte)(argb >>> 16)).put((byte)(argb >>> 8)).put((byte)argb).put((byte)(argb >>> 24));
		out.writeBytes(bytes.array());
	}

	/** LevelRenderer.submitFeatures TAIL: this frame's cracks and outline, published when they changed. */
	public static void finish() {
		if (!active) {
			return;
		}

		try {
			byte[] data = new byte[cracks.size() + lines.size()];
			System.arraycopy(cracks.toByteArray(), 0, data, 0, cracks.size());
			System.arraycopy(lines.toByteArray(), 0, data, cracks.size(), lines.size());
			int count = cracks.size() / BlockMeshProtocol.STRIDE;
			if (!Arrays.equals(previous, data) || count != previousCracks) {
				MAILBOX.mesh(BlockMeshProtocol.header(BlockMeshProtocol.MESH_MAGIC, identity, MAILBOX.now(), ++revision, atlasRevision,
					data.length / BlockMeshProtocol.STRIDE, BlockMeshProtocol.STRIDE, data.length, count, 0, lines.size() / BlockMeshProtocol.STRIDE, true), data);
				previous = data;
				previousCracks = count;
			}

			MAILBOX.heartbeat(true);
		} catch (Throwable error) {
			failure(error);
		}
	}

	private static void pause() {
		active = false;
		try {
			if (initialized) {
				MAILBOX.heartbeat(false);
			}
		} catch (Throwable ignored) {
			// next frame
		}
	}

	private static void failure(final Throwable error) {
		if (error instanceof VirtualMachineError fatal) {
			throw fatal;
		}

		pause();
		long now = System.nanoTime();
		if (now - report > 5_000_000_000L) {
			report = now;
			Passthrough.LOG.warn("native block details unavailable, vanilla draws them: {}", error.toString());
		}
	}

	public static void close() {
		pause();
		MAILBOX.close();
		pixels = previous = null;
	}
}
