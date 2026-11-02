package lld.filesystem;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Composite-pattern base type for every node in the in-memory tree ({@link File} is a leaf,
 * {@link Directory} is a composite).
 *
 * <p><b>Identity.</b> Every entry gets a globally unique, monotonically increasing {@code id}
 * at construction time via {@link #ID_GENERATOR}. This id (not {@code hashCode()} or
 * {@code identityHashCode()}, both of which are not guaranteed collision-free across the JVM)
 * is the canonical key used by {@link FileSystem} to establish a deadlock-free lock-acquisition
 * order when an operation (mv, cp) must hold locks on two different directories at once -- see
 * {@link FileSystem} javadoc for the full argument.</p>
 *
 * <p><b>Mutability.</b> {@code name} and {@code parent} are mutated by mv/rename operations, so
 * they are stored in {@code volatile} fields to guarantee visibility across threads. All
 * structural reads/writes of these fields that must be atomic with other tree changes are still
 * required to go through {@link FileSystem}'s tree lock; volatility alone only prevents stale
 * reads, it does not provide compound-operation atomicity.</p>
 */
public abstract class FileSystemEntry {

    private static final AtomicLong ID_GENERATOR = new AtomicLong(0);

    private final long id;
    private volatile String name;
    private volatile Directory parent;
    private final Instant createdAt;
    private volatile Instant modifiedAt;

    protected FileSystemEntry(String name, Directory parent) {
        this.id = ID_GENERATOR.incrementAndGet();
        // The root directory is the sole exception to normal naming rules: it has no parent and
        // its conventional display name is "/" itself, so it does not go through validateName().
        this.name = (parent == null) ? name : validateName(name);
        this.parent = parent;
        Instant now = Instant.now();
        this.createdAt = now;
        this.modifiedAt = now;
    }

    static String validateName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/")) {
            throw new IllegalArgumentException("Invalid entry name: '" + name + "'");
        }
        return name;
    }

    /** Globally unique, stable identity used for lock ordering and equality. */
    public final long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** Package-private: only {@link Directory}/{@link FileSystem} rename an entry, under lock. */
    void setName(String name) {
        this.name = validateName(name);
        touch();
    }

    public Directory getParent() {
        return parent;
    }

    /** Package-private: only {@link FileSystem} re-parents an entry, under the tree write lock. */
    void setParent(Directory parent) {
        this.parent = parent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getModifiedAt() {
        return modifiedAt;
    }

    protected final void touch() {
        this.modifiedAt = Instant.now();
    }

    public abstract boolean isDirectory();

    /** Logical size in bytes: file content length, or recursive sum of children for a directory. */
    public abstract long size();

    /**
     * Returns true if {@code other} is this node or one of its ancestors is this node, i.e.
     * {@code other} lives inside the subtree rooted at {@code this}. Used by {@link FileSystem}
     * to reject moves that would create a cycle (a directory moved into its own descendant).
     *
     * <p>Must only be called while holding the tree structure lock (read or write) so that the
     * chain of {@code parent} pointers observed is consistent with a single point in time.</p>
     */
    final boolean isAncestorOf(FileSystemEntry other) {
        FileSystemEntry cursor = other;
        while (cursor != null) {
            if (cursor == this) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileSystemEntry other)) return false;
        return id == other.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }
}
