package dev.rehan.passthrough.client;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Render-thread writer of the block mesh mappings; the host sees complete snapshots under one aligned seqlock (header +8).
 * Adapted from EldenCraft's BlockMeshMailbox (MIT, Copyright (c) 2026 Adam Grzegorzewski).
 */
final class BlockMeshMailbox implements AutoCloseable {
	private static final VarHandle SEQ = ValueLayout.JAVA_LONG.varHandle();
	private final String prefix;
	private final int meshBytes, atlasBytes;
	private final boolean animated;
	private SharedMemory mesh, atlas, anim, light;
	private long meshSeq, atlasSeq, lightSeq, animSeq, nextOpen;
	private byte[] meshHeader, atlasHeader, lightHeader, animHeader;
	private MemorySegment guard = MemorySegment.NULL, ackHandle = MemorySegment.NULL, ack = MemorySegment.NULL;
	private MethodHandle open, map, unmap, close, clock;
	private final BlockMeshProtocol.AcknowledgementCache ackCache = new BlockMeshProtocol.AcknowledgementCache();
	private boolean closed;

	/** The static block mesh, its mipmapped atlas, the lightmap and the animated sprite channel. */
	BlockMeshMailbox() {
		this(BlockMeshProtocol.PREFIX, BlockMeshProtocol.MESH_BYTES, BlockMeshProtocol.ATLAS_BYTES, true);
	}

	BlockMeshMailbox(final String prefix, final int meshBytes, final int atlasBytes, final boolean animated) {
		this.prefix = prefix;
		this.meshBytes = meshBytes;
		this.atlasBytes = atlasBytes;
		this.animated = animated;
	}

	void initialize() throws Throwable {
		if (this.clock != null) {
			return;
		}

		Linker linker = Linker.nativeLinker();
		SymbolLookup lib = SymbolLookup.libraryLookup("kernel32", Arena.global());
		this.open = linker.downcallHandle(lib.find("OpenFileMappingW").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		this.map = linker.downcallHandle(lib.find("MapViewOfFile").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
		this.unmap = linker.downcallHandle(lib.find("UnmapViewOfFile").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		this.close = linker.downcallHandle(lib.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		this.clock = linker.downcallHandle(lib.find("GetTickCount64").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_LONG));
		// one writer per name: a second Minecraft would otherwise interleave its snapshots with ours. GetLastError comes
		// from the call's captured state (a separate GetLastError call from Java can see a value the JVM left there).
		StructLayout captured = Linker.Option.captureStateLayout();
		MethodHandle createMutex = linker.downcallHandle(lib.find("CreateMutexW").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
			Linker.Option.captureCallState("GetLastError"));
		try (Arena a = Arena.ofConfined()) {
			MemorySegment state = a.allocate(captured);
			this.guard = (MemorySegment)createMutex.invokeExact(state, MemorySegment.NULL, 0,
				a.allocateFrom(ValueLayout.JAVA_BYTE, ("Local\\" + this.prefix + "Mesh.Writer\0").getBytes(StandardCharsets.UTF_16LE)));
			int status = state.get(ValueLayout.JAVA_INT, captured.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError")));
			if (this.guard.address() == 0 || status == 183) { // ERROR_ALREADY_EXISTS
				throw new IllegalStateException("Block mesh writer already exists");
			}
		}

		this.mesh = SharedMemory.create("Local\\" + this.prefix + "Mesh", this.meshBytes);
		this.atlas = SharedMemory.create("Local\\" + this.prefix + "Atlas", this.atlasBytes);
		if (this.animated) {
			this.anim = SharedMemory.create("Local\\" + this.prefix + "Anim", BlockMeshProtocol.ANIM_BYTES);
			this.light = SharedMemory.create("Local\\" + this.prefix + "Light", BlockMeshProtocol.LIGHT_BYTES);
		}
	}

	long now() throws Throwable {
		return (long)this.clock.invokeExact();
	}

	/** The real 16x16 lightmap updates independently of the immutable geometry. */
	void lighting(final byte[] header, final byte[] data) {
		this.lightHeader = header.clone();
		this.writeLight(data);
	}

	private void writeLight(final byte[] data) {
		if (this.light == null || this.lightHeader == null || this.closed) {
			return;
		}

		MemorySegment dst = this.light.segment;
		SEQ.setVolatile(dst, 8L, this.lightSeq + 1);
		VarHandle.fullFence();
		MemorySegment src = MemorySegment.ofArray(this.lightHeader);
		MemorySegment.copy(src, 0, dst, 0, 8);
		MemorySegment.copy(src, 16, dst, 16, 112);
		if (data != null) {
			MemorySegment.copy(MemorySegment.ofArray(data), 0, dst, 128, 1024);
		}

		VarHandle.fullFence();
		SEQ.setVolatile(dst, 8L, this.lightSeq + 2);
		this.lightSeq += 2;
	}

	/** The current frames of every animated sprite in the resident atlas. */
	void animation(final byte[] header, final byte[] data) {
		if (this.anim == null || this.closed) {
			return;
		}

		this.animHeader = header.clone();
		long seq = this.animSeq;
		MemorySegment dst = this.anim.segment;
		SEQ.setVolatile(dst, 8L, seq + 1);
		VarHandle.fullFence();
		MemorySegment src = MemorySegment.ofArray(header);
		MemorySegment.copy(src, 0, dst, 0, 8);
		MemorySegment.copy(src, 16, dst, 16, 112);
		MemorySegment.copy(MemorySegment.ofArray(data), 0, dst, 128, data.length);
		VarHandle.fullFence();
		SEQ.setVolatile(dst, 8L, seq + 2);
		this.animSeq = seq + 2;
	}

	void mesh(final byte[] header, final byte[] data) {
		this.meshHeader = header.clone();
		this.write(this.mesh, header, MemorySegment.ofArray(data), false);
	}

	void atlas(final byte[] header, final ByteBuffer data) {
		this.atlasHeader = header.clone();
		this.write(this.atlas, header, MemorySegment.ofBuffer(data), true);
	}

	/** Every frame: the stamp (and whether the host may use it) of every publication, without rewriting payloads. */
	void heartbeat(final boolean active) throws Throwable {
		long now = this.now();
		if (this.meshHeader != null) {
			ByteBuffer.wrap(this.meshHeader).order(ByteOrder.LITTLE_ENDIAN).putLong(48, now).putInt(36, active ? 1 : 0);
			this.write(this.mesh, this.meshHeader, null, false);
		}

		if (this.atlasHeader != null) {
			ByteBuffer.wrap(this.atlasHeader).order(ByteOrder.LITTLE_ENDIAN).putLong(48, now).putInt(36, active ? 1 : 0);
			this.write(this.atlas, this.atlasHeader, null, true);
		}

		if (this.lightHeader != null) {
			ByteBuffer.wrap(this.lightHeader).order(ByteOrder.LITTLE_ENDIAN).putLong(48, now).putInt(36, active ? 1 : 0);
			this.writeLight(null);
		}

		if (this.animHeader != null && this.anim != null && !this.closed) {
			ByteBuffer.wrap(this.animHeader).order(ByteOrder.LITTLE_ENDIAN).putLong(48, now).putInt(36, active ? 1 : 0);
			long seq = this.animSeq;
			SEQ.setVolatile(this.anim.segment, 8L, seq + 1);
			VarHandle.fullFence();
			MemorySegment.copy(MemorySegment.ofArray(this.animHeader), 16, this.anim.segment, 16, 112);
			VarHandle.fullFence();
			SEQ.setVolatile(this.anim.segment, 8L, seq + 2);
			this.animSeq = seq + 2;
		}
	}

	private void write(final SharedMemory target, final byte[] header, final MemorySegment data, final boolean isAtlas) {
		if (this.closed || target == null) {
			return;
		}

		long seq = isAtlas ? this.atlasSeq : this.meshSeq;
		MemorySegment dst = target.segment;
		SEQ.setVolatile(dst, 8L, seq + 1);
		VarHandle.fullFence();
		MemorySegment src = MemorySegment.ofArray(header);
		MemorySegment.copy(src, 0, dst, 0, 8);
		MemorySegment.copy(src, 16, dst, 16, 112);
		if (data != null) {
			MemorySegment.copy(data, 0, dst, 128, ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(80));
		}

		VarHandle.fullFence();
		SEQ.setVolatile(dst, 8L, seq + 2);
		if (isAtlas) {
			this.atlasSeq = seq + 2;
		} else {
			this.meshSeq = seq + 2;
		}
	}

	/** The revision the host holds for this identity (fresh and coherent), or null. */
	BlockMeshHandoff.Revision acknowledgement(final BlockMeshProtocol.Identity id) throws Throwable {
		if (this.closed) {
			return null;
		}

		if (this.ack.address() == 0) {
			long now = this.now();
			if (now < this.nextOpen) {
				return null;
			}

			this.nextOpen = now + 1000;
			try (Arena a = Arena.ofConfined()) {
				this.ackHandle = (MemorySegment)this.open.invokeExact(4, 0, // FILE_MAP_READ
					a.allocateFrom(ValueLayout.JAVA_BYTE, (BlockMeshProtocol.ACK_NAME + "\0").getBytes(StandardCharsets.UTF_16LE)));
				if (this.ackHandle.address() == 0) {
					return null;
				}

				MemorySegment raw = (MemorySegment)this.map.invokeExact(this.ackHandle, 4, 0, 0, 128L);
				if (raw.address() == 0) {
					this.closeAck();
					return null;
				}

				this.ack = raw.reinterpret(128);
			}
		}

		for (int i = 0; i < 3; i++) {
			long before = (long)SEQ.getVolatile(this.ack, 8L);
			if (before <= 0 || (before & 1) != 0) {
				continue;
			}

			VarHandle.fullFence();
			byte[] copy = this.ack.toArray(ValueLayout.JAVA_BYTE);
			VarHandle.fullFence();
			if (before != (long)SEQ.getVolatile(this.ack, 8L)) {
				continue;
			}

			return this.ackCache.accept(copy, id, this.now());
		}

		// an odd or changing seqlock isn't a revocation: reuse only earlier coherent bytes, revalidated (identity, 250 ms)
		return this.ackCache.contended(id, this.now());
	}

	void clearAcknowledgement() {
		this.ackCache.clear();
	}

	private void closeAck() {
		this.ackCache.clear();
		try {
			if (this.ack.address() != 0) {
				int ignored = (int)this.unmap.invokeExact(this.ack);
			}
		} catch (Throwable ignored) {
			// gone
		}

		try {
			if (this.ackHandle.address() != 0) {
				int ignored = (int)this.close.invokeExact(this.ackHandle);
			}
		} catch (Throwable ignored) {
			// gone
		}

		this.ack = this.ackHandle = MemorySegment.NULL;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		try {
			this.heartbeat(false);
		} catch (Throwable ignored) {
			// closing anyway
		}

		this.closed = true;
		this.closeAck();
		for (SharedMemory m : new SharedMemory[] {this.mesh, this.atlas, this.anim, this.light}) {
			if (m != null) {
				m.close();
			}
		}

		try {
			if (this.guard.address() != 0) {
				int ignored = (int)this.close.invokeExact(this.guard);
			}
		} catch (Throwable ignored) {
			// gone
		}
	}
}
