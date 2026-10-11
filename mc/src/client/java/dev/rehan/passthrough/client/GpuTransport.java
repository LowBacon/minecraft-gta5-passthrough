package dev.rehan.passthrough.client;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.rehan.passthrough.Passthrough;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import org.lwjgl.opengl.ARBCopyImage;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Zero-copy frames: Minecraft writes its planes straight into D3D12 textures the host (the GTA add-on, GTA V Enhanced)
 * shares, so no pixel crosses the CPU: no readback wait, no 25 MB copy, no upload. Adapted from EldenCraft's ECGT v1
 * transport (MIT, Copyright (c) 2026 Adam Grzegorzewski, github.com/SocketByte/EldenCraft).
 *
 * <pre>
 * control mapping Local\MCPassthroughGpuFrame, 4096 bytes, opened-or-created by either side:
 *   host     0 u32 magic MCGT (written last)  4 u32 version  8 u32 host pid  12 u32 generation (never 0)
 *            16 u32 width  20 u32 height  24 u32 sets (3)  28 u32 planes (5, or 3 from an older host)
 *            32 + 8 * plane u64 plane bytes  76 u32 host status (2 = unavailable)
 *            96 u32 preferred width  100 u32 preferred height (the host's back buffer)
 *   guest    128 u32 magic MCGQ  132 width  136 height  140 guest pid  144 status (1 imported, 2 failed)
 *            148 generation the status is about
 *   acks     256 + 8 * set u64: newest Minecraft frame the host copied (or dropped) from that set
 * textures Local\MCPassthroughGpu_hostpid_generation_set_plane, fence Local\MCPassthroughGpu_hostpid_generation_ready
 * planes: 0 world RGBA8, 1 world depth R32F (raw window depth), 2 overlay RGBA8, 3 avatar RGBA8, 4 avatar depth R32F
 * (the player alone: HostAvatarRenderer); rows bottom-up, as in the CPU path.
 * </pre>
 * The MCPT slot of a GPU frame carries flag 64 and, at 1024 + 32 * slot, the generation and set. Render thread only;
 * raw GL state touched here is saved and restored.
 */
final class GpuTransport {
	static final String NAME = "Local\\MCPassthroughGpuFrame";
	static final int FLAG = 64;
	static final int SETS = 3, PLANES = 5, OLD_PLANES = 3;
	static final int UNAVAILABLE = -1, BUSY = -2;
	static final int MAX_W = 3840, MAX_H = 2160;
	private static final int BYTES = 4096, MAGIC = 0x5447434D, REQUEST_MAGIC = 0x5147434D, VERSION = 1;
	private static final int PREFERRED = 96, HOST_STATUS = 76, REQUEST = 128, ACKS = 256, STATUS_IMPORTED = 1, STATUS_FAILED = 2;
	private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED;
	private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED;

	private static SharedMemory control;
	private static boolean disabled, checked, copyImage, importFailed;
	private static int generation, width, height, failedGeneration, planes;
	private static final int[][] memory = new int[SETS][PLANES], texture = new int[SETS][PLANES];
	private static final int[][] depthTarget = new int[SETS][PLANES];
	private static final long[] written = new long[SETS];
	private static int semaphore, program, vao, readFbo, drawFbo, setNext;
	private static long lastRequestNanos, waitingSince;

	private GpuTransport() {
	}

	private static boolean available() {
		if (disabled) {
			return false;
		}

		if (!checked) {
			checked = true;
			if (Boolean.getBoolean("passthrough.cpuFrames")) {
				disabled = true;
				Passthrough.LOG.info("GPU frames off (-Dpassthrough.cpuFrames=true): shared-memory frames");
				return false;
			}

			var caps = GL.getCapabilities();
			boolean supported = caps.GL_EXT_memory_object && caps.GL_EXT_memory_object_win32 && caps.GL_EXT_semaphore && caps.GL_EXT_semaphore_win32;
			copyImage = caps.OpenGL43 || caps.GL_ARB_copy_image;
			Passthrough.LOG.info("GPU frames: memory/semaphore interop {}, copy_image {}", supported, copyImage);
			if (!supported) {
				disabled = true;
				return false;
			}
		}

		if (control == null) {
			try {
				control = SharedMemory.create(NAME, BYTES);
			} catch (Throwable failure) {
				disabled = true;
				Passthrough.LOG.warn("GPU frames: no control mapping, shared-memory frames stay", failure);
				return false;
			}
		}

		return true;
	}

	/** Asks for textures of this size and returns a free set, BUSY while the host catches up, or UNAVAILABLE (CPU frames). */
	static int acquire(final int w, final int h) {
		if (!available()) {
			return UNAVAILABLE;
		}

		MemorySegment m = control.segment;
		if (m.get(INT, HOST_STATUS) == STATUS_FAILED) {
			return UNAVAILABLE;
		}

		long now = System.nanoTime();
		if (m.get(INT, REQUEST + 4) != w || m.get(INT, REQUEST + 8) != h || now - lastRequestNanos > 1_000_000_000L) {
			lastRequestNanos = now;
			m.set(INT, REQUEST + 4, w);
			m.set(INT, REQUEST + 8, h);
			m.set(INT, REQUEST + 12, (int) ProcessHandle.current().pid());
			VarHandle.fullFence();
			m.set(INT, REQUEST, REQUEST_MAGIC);
		}

		VarHandle.fullFence();
		if (m.get(INT, 0) != MAGIC || m.get(INT, 4) != VERSION || m.get(INT, 24) != SETS || m.get(INT, 28) != PLANES && m.get(INT, 28) != OLD_PLANES) {
			if (waitingSince == 0) {
				waitingSince = now;
			}

			// a host without GPU frames (Legacy, D3D11): after a second of nothing, the CPU path
			return now - waitingSince < 1_000_000_000L ? BUSY : UNAVAILABLE;
		}

		waitingSince = 0;
		int next = m.get(INT, 12);
		if (next == failedGeneration) {
			return UNAVAILABLE;
		}

		if (m.get(INT, 16) != w || m.get(INT, 20) != h || next == 0) {
			return BUSY;
		}

		if (next != generation && !importGeneration(m, next, w, h)) {
			return failedGeneration == next ? UNAVAILABLE : BUSY;
		}

		if (program == 0) {
			return UNAVAILABLE;
		}

		long newest = 0;
		for (int s = 0; s < SETS; s++) {
			newest = Math.max(newest, m.get(LONG, ACKS + s * 8L));
		}

		// a set is free again once the host has copied (or dropped) its frame or any newer one
		for (int i = 0; i < SETS; i++) {
			int s = (setNext + i) % SETS;
			if (written[s] == 0 || newest >= written[s]) {
				setNext = (s + 1) % SETS;
				return s;
			}
		}

		return BUSY;
	}

	static int generation() {
		return generation;
	}

	/** Whether the imported sets have the avatar planes (3 and 4): an older host shares only three. */
	static boolean avatarPlanes() {
		return generation != 0 && planes == PLANES;
	}

	private static boolean depthPlane(final int p) {
		return p == 1 || p == 4;
	}

	/** Whether GPU frames proved unusable in this process. */
	static boolean failed() {
		return disabled || importFailed || control != null && control.segment.get(INT, HOST_STATUS) == STATUS_FAILED;
	}

	/** The host's back-buffer size (full resolution costs nothing extra to transport), or null. */
	static int[] preferredSize() {
		if (!available() || failed()) {
			return null;
		}

		int w = control.segment.get(INT, PREFERRED), h = control.segment.get(INT, PREFERRED + 4);
		return w > 0 && h > 0 && w <= MAX_W && h <= MAX_H ? new int[] {w, h} : null;
	}

	private static boolean importGeneration(final MemorySegment m, final int next, final int w, final int h) {
		destroy();
		while (GL11C.glGetError() != 0) {
			// clear stale errors
		}

		int oldTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
		int oldDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
		int pid = m.get(INT, 8);
		int count = m.get(INT, 28);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			String prefix = "Local\\MCPassthroughGpu_" + Integer.toUnsignedString(pid) + "_" + Integer.toUnsignedString(next);
			for (int s = 0; s < SETS; s++) {
				for (int p = 0; p < count; p++) {
					int object = EXTMemoryObject.glCreateMemoryObjectsEXT();
					memory[s][p] = object;
					EXTMemoryObject.glMemoryObjectParameteriEXT(object, EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, GL11C.GL_TRUE);
					EXTMemoryObjectWin32.glImportMemoryWin32NameEXT(object, m.get(LONG, 32 + p * 8L), EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D12_RESOURCE_EXT,
						MemoryUtil.memAddress(stack.UTF16(prefix + "_" + s + "_" + p)));
					check("import plane");
					int id = GL11C.glGenTextures();
					texture[s][p] = id;
					GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
					EXTMemoryObject.glTexStorageMem2DEXT(GL11C.GL_TEXTURE_2D, 1, depthPlane(p) ? GL30C.GL_R32F : GL11C.GL_RGBA8, w, h, object, 0L);
					GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
					GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
					check("texture storage");
					if (depthPlane(p)) {
						int fbo = GL30C.glGenFramebuffers();
						depthTarget[s][p] = fbo;
						GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, fbo);
						GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, id, 0);
						if (GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
							throw new IllegalStateException("shared depth target incomplete");
						}
					}
				}
			}

			semaphore = EXTSemaphore.glGenSemaphoresEXT();
			EXTSemaphoreWin32.glImportSemaphoreWin32NameEXT(semaphore, EXTSemaphoreWin32.GL_HANDLE_TYPE_D3D12_FENCE_EXT,
				MemoryUtil.memAddress(stack.UTF16(prefix + "_ready")));
			check("import fence");
			if (program == 0) {
				program = depthProgram();
			}

			if (vao == 0) {
				vao = GL30C.glGenVertexArrays();
			}

			if (!copyImage) {
				readFbo = GL30C.glGenFramebuffers();
				drawFbo = GL30C.glGenFramebuffers();
			}

			if (!sameGeneration(m, next, pid, w, h)) {
				destroy();
				return false;
			}

			generation = next;
			planes = count;
			width = w;
			height = h;
			java.util.Arrays.fill(written, 0L);
			status(m, STATUS_IMPORTED, next);
			Passthrough.LOG.info("GPU frames active: {}x{} generation {}, {} planes, no pixel readback", w, h, Integer.toUnsignedString(next), count);
			return true;
		} catch (Throwable failure) {
			// the host replaced its textures mid-import (a resize, a restart): try that one again instead of giving up
			if (!sameGeneration(m, next, pid, w, h)) {
				destroy();
				return false;
			}

			failedGeneration = next;
			importFailed = true;
			destroy();
			status(m, STATUS_FAILED, next);
			Passthrough.LOG.warn("GPU frames: import failed, shared-memory frames stay", failure);
			return false;
		} finally {
			GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, oldTexture);
			GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, oldDraw);
		}
	}

	private static boolean sameGeneration(final MemorySegment m, final int next, final int pid, final int w, final int h) {
		VarHandle.fullFence();
		return m.get(INT, 0) == MAGIC && m.get(INT, 12) == next && m.get(INT, 8) == pid && m.get(INT, 16) == w && m.get(INT, 20) == h;
	}

	private static void status(final MemorySegment m, final int value, final int forGeneration) {
		m.set(INT, REQUEST + 20, forGeneration);
		VarHandle.fullFence();
		m.set(INT, REQUEST + 16, value);
	}

	private static void check(final String step) {
		int error = GL11C.glGetError();
		if (error != 0) {
			throw new IllegalStateException(step + ": GL error 0x" + Integer.toHexString(error));
		}
	}

	private static int depthProgram() {
		int vertex = shader(GL20C.GL_VERTEX_SHADER,
			"#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0.0,1.0);}");
		int fragment = shader(GL20C.GL_FRAGMENT_SHADER,
			"#version 330 core\nuniform sampler2D sourceDepth;out float value;void main(){value=texelFetch(sourceDepth,ivec2(gl_FragCoord.xy),0).r;}");
		int linked = GL20C.glCreateProgram();
		GL20C.glAttachShader(linked, vertex);
		GL20C.glAttachShader(linked, fragment);
		GL20C.glLinkProgram(linked);
		GL20C.glDeleteShader(vertex);
		GL20C.glDeleteShader(fragment);
		if (GL20C.glGetProgrami(linked, GL20C.GL_LINK_STATUS) == 0) {
			String log = GL20C.glGetProgramInfoLog(linked);
			GL20C.glDeleteProgram(linked);
			throw new IllegalStateException(log);
		}

		return linked;
	}

	private static int shader(final int type, final String source) {
		int id = GL20C.glCreateShader(type);
		GL20C.glShaderSource(id, source);
		GL20C.glCompileShader(id);
		if (GL20C.glGetShaderi(id, GL20C.GL_COMPILE_STATUS) == 0) {
			String log = GL20C.glGetShaderInfoLog(id);
			GL20C.glDeleteShader(id);
			throw new IllegalStateException(log);
		}

		return id;
	}

	private static int glId(final GpuTexture source) {
		return source instanceof GlTexture gl ? gl.glId() : 0;
	}

	/** Copies a colour target into a shared plane on the GPU. False if the backend isn't OpenGL. */
	static boolean copyColor(final GpuTexture source, final int set, final int plane) {
		int id = glId(source);
		if (id == 0 || plane >= planes || texture[set][plane] == 0) {
			return false;
		}

		if (copyImage) {
			ARBCopyImage.glCopyImageSubData(id, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, texture[set][plane], GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
			return true;
		}

		int oldRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING), oldDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
		boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
		try {
			GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
			GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFbo);
			GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, id, 0);
			GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFbo);
			GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, texture[set][plane], 0);
			GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
		} finally {
			if (scissor) {
				GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
			}

			GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, oldRead);
			GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, oldDraw);
		}

		return true;
	}

	/** Writes a depth texture's raw window depth into one of the set's R32F planes (1 or 4) with one fullscreen triangle. */
	static boolean copyDepth(final GpuTexture source, final int set, final int plane) {
		int id = glId(source);
		if (id == 0 || program == 0 || !depthPlane(plane) || plane >= planes || depthTarget[set][plane] == 0) {
			return false;
		}

		int oldProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM), oldVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
		int oldDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING), oldActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
		GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
		int oldTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D), oldSampler = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
		int[] viewport = new int[4];
		GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
		boolean depth = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST), blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
		boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST), cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
		int[] mask = new int[4];
		GL11C.glGetIntegerv(GL11C.GL_COLOR_WRITEMASK, mask);
		GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
		int compare = GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE);
		try {
			GL11C.glDisable(GL11C.GL_DEPTH_TEST);
			GL11C.glDisable(GL11C.GL_BLEND);
			GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
			GL11C.glDisable(GL11C.GL_CULL_FACE);
			GL11C.glColorMask(true, true, true, true);
			GL33C.glBindSampler(0, 0);
			GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
			GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, depthTarget[set][plane]);
			GL11C.glViewport(0, 0, width, height);
			GL20C.glUseProgram(program);
			GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "sourceDepth"), 0);
			GL30C.glBindVertexArray(vao);
			GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
		} finally {
			GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE, compare);
			GL30C.glBindVertexArray(oldVao);
			GL20C.glUseProgram(oldProgram);
			GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, oldDraw);
			GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
			GL11C.glColorMask(mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0);
			if (depth) {
				GL11C.glEnable(GL11C.GL_DEPTH_TEST);
			}

			if (blend) {
				GL11C.glEnable(GL11C.GL_BLEND);
			}

			if (scissor) {
				GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
			}

			if (cull) {
				GL11C.glEnable(GL11C.GL_CULL_FACE);
			}

			GL33C.glBindSampler(0, oldSampler);
			GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, oldTexture);
			GL13C.glActiveTexture(oldActive);
		}

		return true;
	}

	/** Makes every write to this set visible to the host and marks it as holding `frame` (the fence value). */
	static void signal(final int set, final long frame) {
		int[] layouts = new int[planes];
		java.util.Arrays.fill(layouts, EXTSemaphore.GL_LAYOUT_GENERAL_EXT);
		EXTSemaphore.glSemaphoreParameterui64EXT(semaphore, EXTSemaphoreWin32.GL_D3D12_FENCE_VALUE_EXT, frame);
		EXTSemaphore.glSignalSemaphoreEXT(semaphore, new int[0], java.util.Arrays.copyOf(texture[set], planes), layouts);
		GL11C.glFlush();
		written[set] = frame;
	}

	/** The capture was abandoned before its signal: nothing of it is published. */
	static void discard(final int set) {
		if (set >= 0 && set < SETS) {
			written[set] = 0;
		}
	}

	private static void destroy() {
		for (int s = 0; s < SETS; s++) {
			for (int p = 0; p < PLANES; p++) {
				if (depthTarget[s][p] != 0) {
					GL30C.glDeleteFramebuffers(depthTarget[s][p]);
				}

				depthTarget[s][p] = 0;
				if (texture[s][p] != 0) {
					GL11C.glDeleteTextures(texture[s][p]);
				}

				if (memory[s][p] != 0) {
					EXTMemoryObject.glDeleteMemoryObjectsEXT(memory[s][p]);
				}

				texture[s][p] = memory[s][p] = 0;
			}
		}

		if (semaphore != 0) {
			EXTSemaphore.glDeleteSemaphoresEXT(semaphore);
		}

		semaphore = 0;
		generation = 0;
		planes = 0;
		java.util.Arrays.fill(written, 0L);
	}
}
