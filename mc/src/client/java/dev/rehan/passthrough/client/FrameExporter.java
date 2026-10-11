package dev.rehan.passthrough.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import dev.rehan.passthrough.Passthrough;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import org.joml.Vector4f;

/**
 * Hands Minecraft's frame to the host through the shared memory "Local\MCPassthroughFrame".
 *
 * <pre>
 * header (4096 bytes, little-endian)
 *   0 int magic "MCPT"   4 int version (1)   8 int header bytes (4096)   12 int slot count (3)
 *   16 long slot stride   24 int max width   28 int max height
 *   32 long publish counter (bumped after each completed slot)   40 int latest slot (-1: none yet)   44 int Minecraft pid
 *   48 int the slot the host is copying right now (-1: none), written by the host: never written into meanwhile
 *   256 + 128 * i: slot i
 *     +0 long seq (odd while being written)   +8 long Minecraft frame   +16 long host frame
 *     +24 int width   +28 int height   +32 float near   +36 float far   +40 float vertical fov (degrees)
 *     +44 int flags: 1 = depth in [0, 1] (zZeroToOne), 2 = rows bottom-up, 4 = reversed Z (1 = near, 0 = far/empty),
 *         32 = avatar layer: the player is drawn alone into layers 3 and 4 (and isn't in the world layer),
 *         64 = GPU frame: the pixels are in shared D3D12 textures (GpuTransport), not in this slot
 *   1024 + 32 * i: slot i's GPU frame: u32 generation, u32 set
 *   2048 + 64 * i: slot i's scene: u32 magic "MCSC", u32 version 1, u64 mesh revision, u64 atlas revision, u64 mesh session
 *     (all zero: the world layer has its chunks; else it left out exactly that native block mesh, which the host draws)
 *     +48 double camera x, +56 y, +64 z   +72 float yaw   +76 pitch   +80 roll   +84 int first person
 *     +88 long capture time (System.nanoTime)   +96 long publish time
 *     +104 double player (Steve's feet) x, +112 y, +120 z
 * slot i data at 4096 + i * stride, each layer width * height * 4 bytes:
 *   world colour RGBA8 (premultiplied alpha), world depth float32, overlay RGBA8 (hand + HUD, premultiplied alpha),
 *   avatar colour RGBA8 and avatar depth float32 (flag 32 only)
 * Max width/height (+24, +28) bound the frames carried in the slots; GPU frames can be larger (GpuTransport.MAX_W/H).
 * </pre>
 *
 * The world layer is copied just before the hand is drawn; the colour target is then cleared so what follows (hand,
 * screen effects, GUI) forms the overlay layer, copied at the end of the frame. Readback is asynchronous: a ring of
 * GPU buffers, published when the GPU fence passes (next frame at the latest).
 */
public final class FrameExporter {
	public static final String NAME = "Local\\MCPassthroughFrame";
	private static final int MAGIC = 0x5450434D;
	private static final int VERSION = 1;
	private static final int HEADER = 4096;
	private static final int SLOTS = 3;
	private static final int SLOT_DESC = 256;
	private static final int SLOT_DESC_BYTES = 128;
	/** Per slot, 32 bytes: u32 GPU generation, u32 GPU set (a GPU frame: flag 64 in the slot's flags). */
	private static final int GPU_DESC = 1024;
	/** Per slot, 64 bytes: the native block mesh revision the world layer left out (BlockMeshClient). */
	private static final int SCENE_DESC = 2048, SCENE_MAGIC = 0x4353434D;
	/** Slot frames (the readback path, GTA V Legacy): GPU frames aren't bound by this. */
	private static final int MAX_W = 2560;
	private static final int MAX_H = 1440;
	private static final long LAYER_MAX = (long)MAX_W * MAX_H * 4;
	private static final long STRIDE = LAYER_MAX * 5;
	static final int FLAG_AVATAR = 32;
	private static final int RING = 3;
	private static final long STUCK_NANOS = 1_000_000_000L;
	private static final Vector4f TRANSPARENT = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
	private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED;
	private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED;
	private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED;
	private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED;
	/** Near/far planes of the frame being rendered (the camera sets far each frame). */
	private static final float NEAR = 0.05F;
	private static float far = 1024.0F;

	private static SharedMemory shm;
	private static boolean failed;
	private static boolean warnedSize;
	private static final Capture[] ring = new Capture[RING];
	private static int ringNext;
	private static int slotNext;
	private static long frameCounter;
	private static long publishCounter;
	private static Capture current;
	/** The newest capture went to shared GPU textures (not a slot). */
	private static boolean lastGpu;

	private FrameExporter() {
	}

	private static final class Capture {
		GpuBuffer color;
		GpuBuffer depth;
		GpuBuffer overlay;
		GpuBuffer avatarColor, avatarDepth;
		/** The player was drawn into the avatar layers (flag 32). */
		boolean avatar;
		/** The native block mesh revision this frame's world layer left out (its chunks are the host's to draw), or NONE. */
		BlockMeshClient.Exclusion exclusion = BlockMeshClient.Exclusion.NONE;
		int width;
		int height;
		long generation;
		boolean busy;
		long busySince;
		HostState.Pose pose;
		float far;
		long frame;
		long captureNanos;
		/** The shared GPU texture set this capture writes (GpuTransport), or -1: the readback path. */
		int gpuSet = -1;

		void allocate(final int w, final int h) {
			this.free();
			long n = (long)w * h * 4;
			int usage = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;
			this.color = RenderSystem.getDevice().createBuffer(() -> "passthrough world colour", usage, n);
			this.depth = RenderSystem.getDevice().createBuffer(() -> "passthrough world depth", usage, n);
			this.overlay = RenderSystem.getDevice().createBuffer(() -> "passthrough overlay", usage, n);
			this.width = w;
			this.height = h;
		}

		void free() {
			for (GpuBuffer b : new GpuBuffer[]{this.color, this.depth, this.overlay, this.avatarColor, this.avatarDepth}) {
				if (b != null) {
					b.close();
				}
			}

			this.color = this.depth = this.overlay = this.avatarColor = this.avatarDepth = null;
		}
	}

	public static void setFar(final float depthFar) {
		far = depthFar;
	}

	public static boolean exporting() {
		return shm != null;
	}

	/** A world layer was captured this frame and its overlay is still to come. */
	public static boolean capturing() {
		return current != null;
	}

	/**
	 * Whether the avatar layers can be carried: slot frames always can, GPU frames only from a host sharing five planes. By
	 * the path the newest capture took (a host that went away leaves its last GPU generation imported).
	 */
	public static boolean avatarPossible() {
		return !lastGpu || GpuTransport.avatarPlanes();
	}

	private static boolean ensureShm() {
		if (shm != null) {
			return true;
		}

		if (failed) {
			return false;
		}

		try {
			shm = SharedMemory.create(NAME, HEADER + STRIDE * SLOTS);
			MemorySegment m = shm.segment;
			m.set(INT, 0, MAGIC);
			m.set(INT, 4, VERSION);
			m.set(INT, 8, HEADER);
			m.set(INT, 12, SLOTS);
			m.set(LONG, 16, STRIDE);
			m.set(INT, 24, MAX_W);
			m.set(INT, 28, MAX_H);
			m.set(INT, 40, -1);
			m.set(INT, 44, (int)ProcessHandle.current().pid());
			m.set(INT, 48, -1);
			Passthrough.LOG.info("frame export: shared memory {} ({} MB)", NAME, (HEADER + STRIDE * SLOTS) >> 20);
			return true;
		} catch (Throwable t) {
			failed = true;
			Passthrough.LOG.error("frame export disabled: couldn't create shared memory", t);
			return false;
		}
	}

	/** GameRenderer.renderLevel, just before the 3D HUD (hand): copy the world layer, then clear colour for the overlay. */
	public static void captureWorld(final RenderTarget target) {
		current = null;
		HostState.Pose pose = HostState.frame();
		if (pose == null || !ensureShm()) {
			return;
		}

		int w = target.width;
		int h = target.height;
		boolean slotFits = w <= MAX_W && h <= MAX_H;
		if (w <= 0 || h <= 0 || w > GpuTransport.MAX_W || h > GpuTransport.MAX_H) {
			tooBig(w, h, GpuTransport.MAX_W, GpuTransport.MAX_H);
			return;
		}

		Capture c = ring[ringNext];
		if (c == null) {
			c = ring[ringNext] = new Capture();
		}

		long now = System.nanoTime();
		if (c.busy && now - c.busySince < STUCK_NANOS) {
			return;
		}

		// GPU frames first (the host shares D3D12 textures: Enhanced); while it catches up the frame is dropped, never read
		// back; a host without them (Legacy) leaves the readback path
		c.gpuSet = GpuTransport.acquire(w, h);
		if (c.gpuSet == GpuTransport.BUSY) {
			return;
		}

		if (c.gpuSet >= 0 && !(GpuTransport.copyColor(target.getColorTexture(), c.gpuSet, 0) && GpuTransport.copyDepth(target.getDepthTexture(), c.gpuSet, 1))) {
			GpuTransport.discard(c.gpuSet);
			c.gpuSet = -1;
		}

		if (c.gpuSet < 0 && !slotFits) {
			tooBig(w, h, MAX_W, MAX_H); // only GPU frames carry this size
			return;
		}

		if (c.gpuSet >= 0) {
			// readback buffers of another size must not survive into a later readback frame
			if (c.color != null && (c.width != w || c.height != h)) {
				c.free();
			}

			c.width = w;
			c.height = h;
		} else if (c.width != w || c.height != h || c.color == null) {
			c.allocate(w, h);
		}

		lastGpu = c.gpuSet >= 0;
		c.generation++;
		c.avatar = false;
		c.exclusion = BlockMeshClient.exclusion();
		c.busy = true;
		c.busySince = now;
		c.pose = pose;
		c.far = far;
		c.frame = ++frameCounter;
		c.captureNanos = now;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		if (c.gpuSet < 0) {
			encoder.copyTextureToBuffer(target.getColorTexture(), c.color, 0L, () -> {}, 0);
			encoder.copyTextureToBuffer(target.getDepthTexture(), c.depth, 0L, () -> {}, 0);
		}

		encoder.clearColorTexture(target.getColorTexture(), TRANSPARENT);
		current = c;
	}

	private static void tooBig(final int w, final int h, final int maxW, final int maxH) {
		if (!warnedSize) {
			warnedSize = true;
			Passthrough.LOG.warn("frame export: {}x{} is bigger than {}x{}, not exporting", w, h, maxW, maxH);
		}
	}

	/**
	 * Right after captureWorld, the player drawn alone (HostAvatarRenderer) into the colour and depth targets: copied as
	 * the avatar layers. False if they couldn't be (the capture is then abandoned: the world layer has no player in it).
	 */
	public static boolean captureAvatar(final RenderTarget target) {
		Capture c = current;
		if (c == null || target.width != c.width || target.height != c.height) {
			return false;
		}

		if (c.gpuSet >= 0) {
			if (!(GpuTransport.copyColor(target.getColorTexture(), c.gpuSet, 3) && GpuTransport.copyDepth(target.getDepthTexture(), c.gpuSet, 4))) {
				return false;
			}
		} else {
			if (c.avatarColor == null) {
				long n = (long)c.width * c.height * 4;
				int usage = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;
				c.avatarColor = RenderSystem.getDevice().createBuffer(() -> "passthrough avatar colour", usage, n);
				c.avatarDepth = RenderSystem.getDevice().createBuffer(() -> "passthrough avatar depth", usage, n);
			}

			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.copyTextureToBuffer(target.getColorTexture(), c.avatarColor, 0L, () -> {}, 0);
			encoder.copyTextureToBuffer(target.getDepthTexture(), c.avatarDepth, 0L, () -> {}, 0);
		}

		c.avatar = true;
		return true;
	}

	/** This frame's capture is dropped: nothing of it is published (the host keeps showing the last frame). */
	public static void abandon() {
		Capture c = current;
		current = null;
		if (c != null) {
			GpuTransport.discard(c.gpuSet);
			c.gpuSet = -1;
			c.busy = false;
		}
	}

	/** End of GameRenderer.render: the overlay (hand, screen effects, GUI) is complete. */
	public static void captureOverlay(final RenderTarget target) {
		Capture c = current;
		current = null;
		if (c == null) {
			return;
		}

		if (target.width != c.width || target.height != c.height) {
			GpuTransport.discard(c.gpuSet);
			c.gpuSet = -1;
			c.busy = false;
			return;
		}

		if (c.gpuSet >= 0) {
			// GPU frames: the overlay into its plane, the fence signalled with this frame's number, published now (the host
			// copies once the GPU reaches the fence): no readback round trip at all
			if (!GpuTransport.copyColor(target.getColorTexture(), c.gpuSet, 2)) {
				GpuTransport.discard(c.gpuSet);
				c.gpuSet = -1;
				c.busy = false;
				return;
			}

			GpuTransport.signal(c.gpuSet, c.frame);
			ringNext = (ringNext + 1) % RING;
			publish(c);
			return;
		}

		long generation = c.generation;
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(target.getColorTexture(), c.overlay, 0L, () -> {
			if (c.generation == generation && c.busy) {
				publish(c);
			}
		}, 0);
		ringNext = (ringNext + 1) % RING;
	}

	private static void publish(final Capture c) {
		boolean published = false;
		try {
			MemorySegment m = shm.segment;
			int slot = slotNext;
			// never the slot the host is copying (it says which, +48): measured, 1.5% of reads were torn by this otherwise
			VarHandle.fullFence();
			if (slot == m.get(INT, 48)) {
				slot = (slot + 1) % SLOTS;
			}

			slotNext = (slot + 1) % SLOTS;
			long desc = SLOT_DESC + (long)SLOT_DESC_BYTES * slot;
			long seq = m.get(LONG, desc);
			if ((seq & 1L) != 0L) {
				seq++;
			}

			m.set(LONG, desc, seq + 1L);
			VarHandle.fullFence();
			long base = HEADER + STRIDE * slot;
			long n = (long)c.width * c.height * 4;
			boolean gpu = c.gpuSet >= 0;
			if (!gpu) {
				copy(c.color, m, base, n);
				copy(c.depth, m, base + n, n);
				copy(c.overlay, m, base + 2 * n, n);
				if (c.avatar) {
					copy(c.avatarColor, m, base + 3 * n, n);
					copy(c.avatarDepth, m, base + 4 * n, n);
				}
			}

			// which shared texture set holds a GPU frame (inside the header, under this slot's seqlock)
			long gpuDesc = GPU_DESC + 32L * slot;
			m.set(INT, gpuDesc, gpu ? GpuTransport.generation() : 0);
			m.set(INT, gpuDesc + 4, gpu ? c.gpuSet : 0);
			// which native block mesh the world layer left out (inside the header, under this slot's seqlock)
			long sceneDesc = SCENE_DESC + 64L * slot;
			m.asSlice(sceneDesc, 64).fill((byte)0);
			if (c.exclusion.active()) {
				m.set(INT, sceneDesc, SCENE_MAGIC);
				m.set(INT, sceneDesc + 4, 1);
				m.set(LONG, sceneDesc + 8, c.exclusion.mesh());
				m.set(LONG, sceneDesc + 16, c.exclusion.atlas());
				m.set(LONG, sceneDesc + 24, c.exclusion.session());
			}
			HostState.Pose p = c.pose;
			m.set(LONG, desc + 8, c.frame);
			m.set(LONG, desc + 16, p.hostFrame());
			m.set(INT, desc + 24, c.width);
			m.set(INT, desc + 28, c.height);
			m.set(FLOAT, desc + 32, NEAR);
			m.set(FLOAT, desc + 36, c.far);
			m.set(FLOAT, desc + 40, HostState.renderFov(p)); // what it was rendered with (overscan included)
			m.set(INT, desc + 44, (RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0) | 2 | 4 | (c.avatar ? FLAG_AVATAR : 0) | (gpu ? GpuTransport.FLAG : 0));
			m.set(DOUBLE, desc + 48, p.x());
			m.set(DOUBLE, desc + 56, p.y());
			m.set(DOUBLE, desc + 64, p.z());
			m.set(FLOAT, desc + 72, p.yaw());
			m.set(FLOAT, desc + 76, p.pitch());
			m.set(FLOAT, desc + 80, p.roll());
			m.set(INT, desc + 84, p.firstPerson() ? 1 : 0);
			m.set(LONG, desc + 88, c.captureNanos);
			m.set(LONG, desc + 96, System.nanoTime());
			// where Steve was drawn (the host moves his pixels by how far he has gone since)
			m.set(DOUBLE, desc + 104, p.px());
			m.set(DOUBLE, desc + 112, p.py());
			m.set(DOUBLE, desc + 120, p.pz());
			VarHandle.fullFence();
			m.set(LONG, desc, seq + 2L);
			m.set(INT, 40, slot);
			VarHandle.fullFence();
			m.set(LONG, 32, ++publishCounter);
			published = true;
		} catch (RuntimeException e) {
			Passthrough.LOG.warn("frame export failed", e);
		} finally {
			if (!published) {
				GpuTransport.discard(c.gpuSet); // a set no descriptor names is never read by the host
			}

			c.gpuSet = -1;
			c.busy = false;
		}
	}

	private static void copy(final GpuBuffer buffer, final MemorySegment dst, final long offset, final long n) {
		try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
			MemorySegment.copy(MemorySegment.ofBuffer(view.data()), 0L, dst, offset, n);
		}
	}
}
