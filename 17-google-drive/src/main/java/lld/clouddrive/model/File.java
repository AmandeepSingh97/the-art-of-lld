package lld.clouddrive.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Leaf node of the Composite tree: a file with an immutable, append-only version history. Each
 * edit appends a new {@link FileVersion} (Memento) rather than mutating the current one, so any
 * thread holding a reference to an older {@code FileVersion} keeps seeing consistent data even
 * while a concurrent upload is appending a newer version.
 *
 * <p><b>Concurrency contract:</b> {@link #versions} is a plain {@link ArrayList} guarded by
 * {@code synchronized} blocks on this file's own monitor ({@code this}). Granularity is
 * per-file, not global: two threads uploading new versions of two different files never
 * contend with each other, only concurrent writers to the SAME file serialize. Structural
 * identity (which folder this file lives in) is governed separately by {@code DriveService}'s
 * tree lock, so a file can be read/versioned without blocking unrelated move/rename traffic
 * elsewhere in the tree.
 */
public final class File extends DriveNode {

    private final List<FileVersion> versions = new ArrayList<>();

    public File(String id, String name, String ownerId, Folder parent, Instant createdAt) {
        super(id, name, ownerId, parent, createdAt);
    }

    @Override
    public boolean isFolder() {
        return false;
    }

    public synchronized void addVersion(FileVersion version) {
        versions.add(version);
    }

    public synchronized FileVersion getCurrentVersion() {
        if (versions.isEmpty()) {
            return null;
        }
        return versions.get(versions.size() - 1);
    }

    public synchronized List<FileVersion> listVersions() {
        return List.copyOf(versions);
    }

    public synchronized FileVersion getVersion(int versionNumber) {
        for (FileVersion v : versions) {
            if (v.getVersionNumber() == versionNumber) {
                return v;
            }
        }
        return null;
    }

    public synchronized int nextVersionNumber() {
        return versions.size() + 1;
    }

    /** Sum of every version's logical size ever created for this file (see quota accounting). */
    public synchronized long totalLogicalBytesAcrossAllVersions() {
        long total = 0;
        for (FileVersion v : versions) {
            total += v.getSizeBytes();
        }
        return total;
    }
}
