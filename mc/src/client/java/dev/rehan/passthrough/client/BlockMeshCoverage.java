package dev.rehan.passthrough.client;

/**
 * A mesh must cover the whole vanilla render-area window before any chunk pass is suppressed. Adapted from EldenCraft (MIT,
 * Copyright (c) 2026 Adam Grzegorzewski).
 */
record BlockMeshCoverage(int centerX, int centerZ, int radius, int minY, int maxY) {
	/** Vanilla's 32-chunk render distance plus the streaming margin. */
	static final int MAX_RADIUS = 32 + 2;

	/** Prebuilt chunks beyond the vanilla view: crossing this many borders keeps the mesh. */
	static final int STREAMING_MARGIN = 2;

	/** Palette checks per complete build (a 69x69-chunk, 24-section overworld fits). */
	static final long MAX_SECTIONS = 131072;

	BlockMeshCoverage {
		long width = 2L * radius + 1, height = (long)maxY - minY + 1;
		if (radius < 0 || radius > MAX_RADIUS || height < 1 || height > 64 || width * width * height > MAX_SECTIONS
			|| Math.abs((long)centerX) > 1875000 || Math.abs((long)centerZ) > 1875000) {
			throw new IllegalArgumentException("Render-area coverage exceeds mesh budget");
		}
	}

	boolean contains(final int x, final int y, final int z) {
		return x >= (long)this.centerX - this.radius && x <= (long)this.centerX + this.radius && z >= (long)this.centerZ - this.radius
			&& z <= (long)this.centerZ + this.radius && y >= this.minY && y <= this.maxY;
	}

	boolean intersectsChunkNeighborhood(final int x, final int z) {
		return x >= (long)this.centerX - this.radius - 1 && x <= (long)this.centerX + this.radius + 1 && z >= (long)this.centerZ - this.radius - 1
			&& z <= (long)this.centerZ + this.radius + 1;
	}

	/** A prebuilt border gives streaming time without suppressing any uncovered section, while the section budget allows it. */
	BlockMeshCoverage withStreamingMargin() {
		long height = (long)this.maxY - this.minY + 1;
		for (int margin = STREAMING_MARGIN; margin > 0; margin--) {
			long width = 2L * (this.radius + margin) + 1;
			if (this.radius + margin <= MAX_RADIUS && width * width * height <= MAX_SECTIONS) {
				return new BlockMeshCoverage(this.centerX, this.centerZ, this.radius + margin, this.minY, this.maxY);
			}
		}

		return this;
	}

	boolean contains(final BlockMeshCoverage other) {
		return other != null && this.minY <= other.minY && this.maxY >= other.maxY
			&& Math.abs((long)this.centerX - other.centerX) + other.radius <= this.radius
			&& Math.abs((long)this.centerZ - other.centerZ) + other.radius <= this.radius;
	}
}
