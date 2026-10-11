package dev.rehan.passthrough.client;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/** A named, pagefile-backed Win32 file mapping: other processes open it by name, and nothing touches the disk. */
final class SharedMemory implements AutoCloseable {
	private static final int PAGE_READWRITE = 0x04;
	private static final int FILE_MAP_ALL_ACCESS = 0xF001F;
	private static MethodHandle createFileMapping, mapViewOfFile, unmapViewOfFile, closeHandle;
	final MemorySegment segment;
	private final MemorySegment handle;
	private boolean closed;

	private SharedMemory(final MemorySegment segment, final MemorySegment handle) {
		this.segment = segment;
		this.handle = handle;
	}

	private static synchronized void link() {
		if (createFileMapping != null) {
			return;
		}

		Linker linker = Linker.nativeLinker();
		SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
		createFileMapping = linker.downcallHandle(
			kernel32.find("CreateFileMappingW").orElseThrow(),
			FunctionDescriptor.of(
				ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS
			)
		);
		mapViewOfFile = linker.downcallHandle(
			kernel32.find("MapViewOfFile").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
		);
		unmapViewOfFile = linker.downcallHandle(kernel32.find("UnmapViewOfFile").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		closeHandle = linker.downcallHandle(kernel32.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
	}

	static SharedMemory create(final String name, final long size) throws Throwable {
		link();
		MemorySegment wideName = Arena.global().allocateFrom(ValueLayout.JAVA_BYTE, (name + "\0").getBytes(StandardCharsets.UTF_16LE));
		MemorySegment invalidHandle = MemorySegment.ofAddress(-1L);
		MemorySegment handle = (MemorySegment)createFileMapping.invoke(
			invalidHandle, MemorySegment.NULL, PAGE_READWRITE, (int)(size >>> 32), (int)size, wideName
		);
		if (handle.address() == 0L) {
			throw new IllegalStateException("CreateFileMappingW failed for " + name);
		}

		MemorySegment view = (MemorySegment)mapViewOfFile.invoke(handle, FILE_MAP_ALL_ACCESS, 0, 0, size);
		if (view.address() == 0L) {
			int ignored = (int)closeHandle.invoke(handle);
			throw new IllegalStateException("MapViewOfFile failed for " + name);
		}

		return new SharedMemory(view.reinterpret(size), handle);
	}

	/** Unmaps the view and closes the handle: the mapping goes away once no other process holds it. Never touch segment after. */
	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		this.closed = true;
		try {
			int ignored = (int)unmapViewOfFile.invoke(this.segment);
			ignored = (int)closeHandle.invoke(this.handle);
		} catch (Throwable ignored) {
			// nothing left to release
		}
	}
}
