package lld.filesystem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A composite node: holds named children (files and/or sub-directories).
 *
 * <p>Backed by a {@link LinkedHashMap} for two reasons: (1) O(1) average lookup/insert/remove by
 * name, which is the dominant access pattern (path resolution walks one segment at a time), and
 * (2) insertion-order iteration, which gives {@code ls} a stable, deterministic ordering without
 * an extra sort -- useful for reproducible tests and matching typical shell expectations (not
 * alphabetical, but stable).</p>
 *
 * <p><b>Thread-safety.</b> This class is intentionally <em>not</em> internally synchronized.
 * All structural reads/writes of {@link #children} are performed by {@link FileSystem} while
 * holding its global tree {@link java.util.concurrent.locks.ReadWriteLock}. Exposing raw
 * mutator methods here and relying on an external lock (rather than making every method
 * {@code synchronized}) keeps locking policy in one place and allows compound operations
 * (e.g. "check cycle, then detach, then attach" for {@code mv}) to be atomic, which per-method
 * synchronization could not provide.</p>
 */
public final class Directory extends FileSystemEntry {

    private final Map<String, FileSystemEntry> children = new LinkedHashMap<>();

    Directory(String name, Directory parent) {
        super(name, parent);
    }

    /** Creates the single root directory of a new file system. Root has no parent and name "/". */
    static Directory createRoot() {
        return new Directory("/", null);
    }

    @Override
    public boolean isDirectory() {
        return true;
    }

    @Override
    public long size() {
        long total = 0;
        for (FileSystemEntry e : children.values()) {
            total += e.size();
        }
        return total;
    }

    /** Package-private accessor; callers (FileSystem) must hold the tree lock. */
    FileSystemEntry getChild(String name) {
        return children.get(name);
    }

    boolean hasChild(String name) {
        return children.containsKey(name);
    }

    boolean isEmpty() {
        return children.isEmpty();
    }

    /** Returns a defensive snapshot list, safe to iterate after releasing the tree lock. */
    List<FileSystemEntry> listChildren() {
        return new ArrayList<>(children.values());
    }

    Collection<String> childNames() {
        return new ArrayList<>(children.keySet());
    }

    void putChild(FileSystemEntry entry) {
        children.put(entry.getName(), entry);
        touch();
    }

    FileSystemEntry removeChild(String name) {
        FileSystemEntry removed = children.remove(name);
        if (removed != null) {
            touch();
        }
        return removed;
    }
}
