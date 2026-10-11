package dev.rehan.passthrough.client;

import java.util.ArrayDeque;

/**
 * Separates face/coverage changes from shading work on an otherwise complete mesh, and measures how long each geometry change
 * has waited for a displayed snapshot. A mesh that keeps up with continuous edits (flowing water, fire, redstone) is never
 * stale for long, so it is never replaced by the RGB-D fallback; an edit that isn't displayed within the grace still is.
 * Adapted from EldenCraft (MIT, Copyright (c) 2026 Adam Grzegorzewski).
 */
final class BlockMeshChanges {
	record Version(long geometry, long lighting) {
	}

	/** An undisplayed placement or removal is visible as stale geometry for at most this long. */
	static final long GRACE_NANOS = 1_000_000_000L;

	private static final int MAX_PENDING = 1024;

	private long geometry = 1, lighting = 1;
	/** (geometry generation, time) of changes not yet known to be displayed, oldest first. */
	private final ArrayDeque<long[]> pending = new ArrayDeque<>();

	Version version() {
		return new Version(this.geometry, this.lighting);
	}

	void geometryChanged() {
		this.geometryChanged(System.nanoTime());
	}

	void geometryChanged(final long now) {
		this.geometry++;
		if (this.pending.size() >= MAX_PENDING) {
			// fold into the newest entry, keeping its older time: staleness is never understated
			this.pending.peekLast()[0] = this.geometry;
		} else {
			this.pending.addLast(new long[] {this.geometry, now});
		}
	}

	void lightingChanged() {
		this.lighting++;
	}

	boolean current(final Version version) {
		return version != null && version.geometry == this.geometry && version.lighting == this.lighting;
	}

	/** How long the oldest geometry change missing from version has waited; 0 if none. */
	long staleNanos(final Version version, final long now) {
		if (version == null) {
			return Long.MAX_VALUE;
		}

		for (long[] change : this.pending) {
			if (change[0] > version.geometry) {
				return Math.max(0, now - change[1]);
			}
		}

		return 0;
	}

	boolean withinGrace(final Version version, final long now) {
		return this.staleNanos(version, now) <= GRACE_NANOS;
	}

	/** The displayed snapshot contains every change up to its version: forget those. */
	void displayed(final Version version) {
		if (version == null) {
			return;
		}

		while (!this.pending.isEmpty() && this.pending.peekFirst()[0] <= version.geometry) {
			this.pending.removeFirst();
		}
	}
}
