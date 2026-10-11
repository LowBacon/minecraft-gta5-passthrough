package dev.rehan.passthrough.client;

import java.util.Objects;

/**
 * Render-thread policy: one uploaded resident and at most one pending same-coverage update. How long stale geometry may stay
 * displayed is measured per change by BlockMeshChanges. Adapted from EldenCraft (MIT, Copyright (c) 2026 Adam Grzegorzewski).
 */
final class BlockMeshHandoff {
	record Revision(long mesh, long atlas, long session) {
		Revision {
			if (mesh <= 0 || atlas <= 0 || session <= 0) {
				throw new IllegalArgumentException("Mesh handoff revision");
			}
		}
	}

	private Revision latest, resident;

	/** Bootstrap after a coverage, atlas or world identity invalidation: no old ownership survives. */
	void geometry(final Revision revision) {
		this.latest = Objects.requireNonNull(revision);
		this.resident = null;
	}

	void invalidate() {
		this.latest = this.resident = null;
	}

	boolean hasLatest() {
		return this.latest != null;
	}

	boolean canPublishColor() {
		return this.latest != null && this.latest.equals(this.resident);
	}

	/** Same-coverage replacement; identity or atlas changes still require full invalidation. */
	void update(final Revision revision) {
		if (!this.canPublishColor() || revision == null || revision.mesh <= this.latest.mesh || revision.atlas != this.latest.atlas
			|| revision.session != this.latest.session) {
			throw new IllegalArgumentException("Mesh handoff requires one acknowledged revision");
		}

		this.latest = revision;
	}

	/** Exactly one coherent, identity-checked and fresh acknowledgement sample for this frame. */
	Revision select(final Revision acknowledgement) {
		if (acknowledgement != null && acknowledgement.equals(this.latest)) {
			this.resident = this.latest;
		} else if (acknowledgement == null || !acknowledgement.equals(this.resident)) {
			this.resident = null;
		}

		return this.resident;
	}
}
