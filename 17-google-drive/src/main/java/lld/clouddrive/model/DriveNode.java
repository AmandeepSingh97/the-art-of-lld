package lld.clouddrive.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Common base of the Composite hierarchy: a {@link Folder} (composite) can contain other
 * {@code DriveNode}s, and a {@link File} (leaf) cannot. Client code (permission resolution,
 * trash, search, move/rename) is written entirely against this base type, which is the point of
 * using Composite here -- "share a folder" and "share a file" are the same operation.
 *
 * <p><b>Concurrency contract:</b> {@code DriveNode} itself does not synchronize {@link #parent}
 * or {@link #name} mutations. Those are only ever mutated by {@code DriveService} while holding
 * its tree-structural {@link java.util.concurrent.locks.ReadWriteLock} write lock (see README
 * section 11), so a single lock protects the whole tree's shape instead of requiring careful
 * multi-node lock ordering for operations like {@code move} that touch two parents at once. The
 * node's ACL list uses {@link CopyOnWriteArrayList} because grants/revokes are rare compared to
 * permission-check reads, and read access needs no external lock.
 */
public abstract class DriveNode {

    private final String id;
    private final String ownerId;
    private final Instant createdAt;
    private final List<AccessEntry> aclEntries = new CopyOnWriteArrayList<>();

    private volatile String name;
    private volatile Folder parent;

    protected DriveNode(String id, String name, String ownerId, Folder parent, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.parent = parent;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public abstract boolean isFolder();

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** Only to be called by DriveService under the structural write lock. */
    public void renameInternal(String newName) {
        this.name = Objects.requireNonNull(newName, "newName");
    }

    public String getOwnerId() {
        return ownerId;
    }

    public Folder getParent() {
        return parent;
    }

    /** Only to be called by DriveService under the structural write lock. */
    public void setParentInternal(Folder newParent) {
        this.parent = newParent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Explicit (non-inherited) ACL entries on this node. Snapshot-safe to iterate without a lock. */
    public List<AccessEntry> getAclEntries() {
        return aclEntries;
    }

    public void upsertAclEntry(AccessEntry entry) {
        aclEntries.removeIf(e -> e.getUserId().equals(entry.getUserId()));
        aclEntries.add(entry);
    }

    public void removeAclEntry(String userId) {
        aclEntries.removeIf(e -> e.getUserId().equals(userId));
    }
}
