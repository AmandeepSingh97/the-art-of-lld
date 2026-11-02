package lld.clouddrive.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Metadata recorded when a node is soft-deleted, enough to restore it to exactly where it was
 * (or safely re-parent it to a fallback location if its original parent is gone) and to know
 * when the retention window expires so the background sweeper can purge it permanently.
 */
public final class TrashRecord {

    private final String nodeId;
    private final String originalParentId;
    private final String originalName;
    private final Instant deletedAt;
    private final Instant purgeAfter;
    private final String deletedByUserId;

    public TrashRecord(String nodeId, String originalParentId, String originalName,
                        Instant deletedAt, Instant purgeAfter, String deletedByUserId) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.originalParentId = originalParentId;
        this.originalName = Objects.requireNonNull(originalName, "originalName");
        this.deletedAt = Objects.requireNonNull(deletedAt, "deletedAt");
        this.purgeAfter = Objects.requireNonNull(purgeAfter, "purgeAfter");
        this.deletedByUserId = Objects.requireNonNull(deletedByUserId, "deletedByUserId");
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getOriginalParentId() {
        return originalParentId;
    }

    public String getOriginalName() {
        return originalName;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getPurgeAfter() {
        return purgeAfter;
    }

    public String getDeletedByUserId() {
        return deletedByUserId;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(purgeAfter);
    }
}
