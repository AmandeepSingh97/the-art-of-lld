package lld.clouddrive.model;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable snapshot of a {@link File}'s content at one point in time -- the "Memento" in this
 * design's use of the Memento pattern. Once created a {@code FileVersion} never changes; editing a
 * file always produces a brand new {@code FileVersion} appended to the file's history rather than
 * mutating an existing one. This gives lock-free, safe-to-share-across-threads read access to any
 * past version while an edit is in flight on a new one.
 */
public final class FileVersion {

    private final int versionNumber;
    private final String contentHash;
    private final long sizeBytes;
    private final String authorUserId;
    private final Instant createdAt;

    public FileVersion(int versionNumber, String contentHash, long sizeBytes, String authorUserId,
                        Instant createdAt) {
        this.versionNumber = versionNumber;
        this.contentHash = Objects.requireNonNull(contentHash, "contentHash");
        this.sizeBytes = sizeBytes;
        this.authorUserId = Objects.requireNonNull(authorUserId, "authorUserId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getContentHash() {
        return contentHash;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getAuthorUserId() {
        return authorUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "v" + versionNumber + "(" + sizeBytes + "b, hash=" + contentHash.substring(0, 8) + "..)";
    }
}
