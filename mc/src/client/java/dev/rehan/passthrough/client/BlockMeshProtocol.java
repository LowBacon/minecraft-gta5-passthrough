package dev.rehan.passthrough.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * The native block mesh transport: Minecraft's real blocks baked into one mesh, with the block atlas, the lightmap and the
 * animated sprites, drawn by the host's compositor with the host camera (gta/src/blocks/block_protocol.hpp is the
 * reader). Runtime only: nothing extracted is ever written to disk. Adapted from EldenCraft's BlockMeshProtocol (MIT,
 * Copyright (c) 2026 Adam Grzegorzewski, github.com/SocketByte/EldenCraft).
 *
 * <pre>
 * Every mapping starts with the same 128-byte header (little-endian):
 *   0 u32 magic   4 u32 version (1)   8 u64 seqlock (odd while written)   16 u32 producer pid (Minecraft)
 *   20 u32 host pid (GTA)   24 u64 epoch   32 u32 map (dimension)   36 u32 active (1) / inactive (0)   40 u64 session
 *   48 u64 stamp (GetTickCount64)   56 u64 revision   64 u64 atlas revision   72 u32 count   76 u32 stride
 *   80 u32 payload bytes   84 u32 solid   88 u32 cutout   92 u32 translucent (vertices per pass)   96 u64 anchor
 *   104 u32 atlas mip levels (atlas only); the rest zero. The payload follows at 128.
 * Local\MCPassthroughBlockMesh: triangles, 28 bytes a vertex: float x, y, z (Minecraft world), float u, v (atlas),
 *   u32 ARGB colour, u32 packed light (block | sky &lt;&lt; 16); solid, then cutout, then translucent.
 * Local\MCPassthroughBlockAtlas: count x stride RGBA8 texels, the whole mip chain, largest first.
 * Local\MCPassthroughBlockLight: the 16x16 RGBA8 lightmap.
 * Local\MCPassthroughBlockAnim: count 16-byte regions {u16 x, y, w, h, u8 mip, 3 zero, u32 offset}, then RGBA8 pixels.
 * Local\MCPassthroughBlockMeshAck (written by the host): the revision it holds, fresh for 250 ms.
 * </pre>
 */
final class BlockMeshProtocol {
	static final int HEADER = 128,
		MESH_MAGIC = 0x424d434d,
		ATLAS_MAGIC = 0x4142434d,
		ACK_MAGIC = 0x414d434d,
		ANIM_MAGIC = 0x4e41434d,
		LIGHT_MAGIC = 0x4c4d434d;
	static final String PREFIX = "MCPassthroughBlock", ACK_NAME = "Local\\MCPassthroughBlockMeshAck";
	static final int MESH_BYTES = 64 * 1024 * 1024,
		ATLAS_BYTES = HEADER + 90 * 1024 * 1024,
		STRIDE = 24,
		LIT_STRIDE = 28,
		MAX_VERTICES = 2097152,
		MAX_MIPS = 13;

	/** Mining cracks and outlines keep their own, smaller mappings. */
	static final int DETAIL_MESH_BYTES = 8 * 1024 * 1024, DETAIL_ATLAS_BYTES = HEADER + 4096 * 4096 * 4;

	/** Animated sprite frames for the resident atlas: a region table, then RGBA8 pixels. */
	static final int ANIM_BYTES = 4 * 1024 * 1024, ANIM_REGION = 16, MAX_ANIM_REGIONS = 4096;

	static final int LIGHT_BYTES = HEADER + 1024;

	private BlockMeshProtocol() {
	}

	/** One mip level dimension, clamped to a texel. */
	static int mipExtent(final int size, final int level) {
		return Math.max(1, size >> level);
	}

	/** Levels a width x height texture can have, capped at MAX_MIPS. */
	static int maxMips(final int width, final int height) {
		int levels = 1;
		for (int size = Math.max(width, height); size > 1; size >>= 1) {
			levels++;
		}

		return Math.min(levels, MAX_MIPS);
	}

	/** Byte offset of a level in the tightly packed RGBA8 chain (largest level first). */
	static long mipOffset(final int width, final int height, final int level) {
		long at = 0;
		for (int m = 0; m < level; m++) {
			at += (long)mipExtent(width, m) * mipExtent(height, m) * 4;
		}

		return at;
	}

	/** Who publishes (Minecraft), for whom (the host), and which world and mesh session it belongs to. */
	record Identity(long producer, long host, long epoch, long map, long session, long anchor) {
		Identity {
			if (producer <= 0 || producer > 0xffffffffL || host <= 0 || host > 0xffffffffL || epoch <= 0 || map < 0 || map > 0xffffffffL
				|| session <= 0 || anchor <= 0) {
				throw new IllegalArgumentException("Mesh identity");
			}
		}
	}

	static byte[] header(final int magic, final Identity id, final long stamp, final long revision, final long atlas, final int a, final int b,
		final int bytes, final int solid, final int cutout, final int translucent, final boolean active) {
		return header(magic, id, stamp, revision, atlas, a, b, bytes, solid, cutout, translucent, active, 1);
	}

	/** mips: the atlas level count (its payload is the full packed chain). For an animation, revision is the tick, a the region count. */
	static byte[] header(final int magic, final Identity id, final long stamp, final long revision, final long atlas, final int a, final int b,
		final int bytes, final int solid, final int cutout, final int translucent, final boolean active, final int mips) {
		if (stamp < 0 || revision <= 0 || atlas <= 0) {
			throw new IllegalArgumentException("Mesh revision/time");
		}

		if (magic != ATLAS_MAGIC && mips != 1) {
			throw new IllegalArgumentException("Only atlases have mips");
		}

		if (magic == MESH_MAGIC) {
			if (a < 0 || a > MAX_VERTICES || a % 3 != 0 || (b != STRIDE && b != LIT_STRIDE) || bytes != a * b || bytes > MESH_BYTES - HEADER
				|| solid < 0 || cutout < 0 || translucent < 0 || solid % 3 != 0 || cutout % 3 != 0 || translucent % 3 != 0
				|| (long)solid + cutout + translucent != a) {
				throw new IllegalArgumentException("Mesh bounds");
			}
		} else if (magic == ATLAS_MAGIC) {
			if (a < 1 || a > 4096 || b < 1 || b > 4096 || mips < 1 || mips > maxMips(a, b) || bytes != mipOffset(a, b, mips)
				|| bytes > ATLAS_BYTES - HEADER || solid != 0 || cutout != 0 || translucent != 0) {
				throw new IllegalArgumentException("Atlas bounds");
			}
		} else if (magic == LIGHT_MAGIC) {
			if (a != 16 || b != 16 || bytes != 1024 || solid != 0 || cutout != 0 || translucent != 0) {
				throw new IllegalArgumentException("Lightmap bounds");
			}
		} else if (magic == ANIM_MAGIC) {
			if (a < 0 || a > MAX_ANIM_REGIONS || b != 0 || bytes < (long)a * ANIM_REGION || bytes > ANIM_BYTES - HEADER || solid != 0
				|| cutout != 0 || translucent != 0) {
				throw new IllegalArgumentException("Animation bounds");
			}
		} else {
			throw new IllegalArgumentException("Mesh magic");
		}

		ByteBuffer out = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
		out.putInt(0, magic)
			.putInt(4, 1)
			.putInt(16, (int)id.producer)
			.putInt(20, (int)id.host)
			.putLong(24, id.epoch)
			.putInt(32, (int)id.map)
			.putInt(36, active ? 1 : 0)
			.putLong(40, id.session)
			.putLong(48, stamp)
			.putLong(56, revision)
			.putLong(64, atlas)
			.putInt(72, a)
			.putInt(76, b)
			.putInt(80, bytes)
			.putInt(84, solid)
			.putInt(88, cutout)
			.putInt(92, translucent)
			.putLong(96, id.anchor);
		if (magic == ATLAS_MAGIC) {
			out.putInt(104, mips);
		}

		return out.array();
	}

	/** The host's acknowledgement, if it is coherent, for this identity and at most 250 ms old. */
	static BlockMeshHandoff.Revision acknowledgement(final byte[] bytes, final Identity id, final long now) {
		if (bytes == null || bytes.length != HEADER || id == null || now < 0) {
			return null;
		}

		ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		long seq = b.getLong(8), time = b.getLong(48);
		if (b.getInt(0) != ACK_MAGIC || b.getInt(4) != 1 || seq <= 0 || (seq & 1) != 0 || b.getInt(36) != 1 || time < 0 || now < time
			|| now - time > 250 || Integer.toUnsignedLong(b.getInt(16)) != id.producer || Integer.toUnsignedLong(b.getInt(20)) != id.host
			|| b.getLong(24) != id.epoch || Integer.toUnsignedLong(b.getInt(32)) != id.map || b.getLong(40) != id.session || b.getLong(56) <= 0
			|| b.getLong(64) <= 0 || b.getLong(96) != id.anchor) {
			return null;
		}

		for (int i = 72; i < 96; i++) {
			if (bytes[i] != 0) {
				return null;
			}
		}

		for (int i = 104; i < 128; i++) {
			if (bytes[i] != 0) {
				return null;
			}
		}

		return new BlockMeshHandoff.Revision(b.getLong(56), b.getLong(64), id.session);
	}

	/** Retry only transient seqlock contention; never extend the producer's original lease. */
	static final class AcknowledgementCache {
		private byte[] coherent;
		private Identity identity;

		BlockMeshHandoff.Revision accept(final byte[] bytes, final Identity id, final long now) {
			this.clear();
			BlockMeshHandoff.Revision result = acknowledgement(bytes, id, now);
			if (result != null) {
				this.coherent = bytes.clone();
				this.identity = id;
			}

			return result;
		}

		BlockMeshHandoff.Revision contended(final Identity id, final long now) {
			if (!Objects.equals(this.identity, id)) {
				this.clear();
				return null;
			}

			BlockMeshHandoff.Revision result = acknowledgement(this.coherent, id, now);
			if (result == null) {
				this.clear();
			}

			return result;
		}

		void clear() {
			this.coherent = null;
			this.identity = null;
		}
	}

	/** 26.3's lightmap shader writes alpha 1 for every texel: a freshly allocated, zeroed target isn't a rendered lightmap. */
	static boolean renderedLightmap(final byte[] map) {
		if (map == null || map.length != 1024) {
			return false;
		}

		for (int i = 3; i < map.length; i += 4) {
			if ((map[i] & 255) != 255) {
				return false;
			}
		}

		return true;
	}
}
