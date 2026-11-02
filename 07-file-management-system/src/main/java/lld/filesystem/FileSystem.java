package lld.filesystem;

import lld.filesystem.exception.DirectoryNotEmptyException;
import lld.filesystem.exception.FileAlreadyExistsException;
import lld.filesystem.exception.InvalidOperationException;
import lld.filesystem.exception.NotADirectoryException;
import lld.filesystem.exception.NotAFileException;
import lld.filesystem.exception.PathNotFoundException;

import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Facade over the in-memory tree: the single entry point every {@link lld.filesystem.command.Command}
 * goes through. Owns path resolution, the root directory, and the locking policy.
 *
 * <h2>Locking strategy (read this before touching concurrency-sensitive code)</h2>
 *
 * <p>Three locking strategies were considered for a mutable tree that supports arbitrary
 * recursive mv/cp/rm:</p>
 * <ol>
 *   <li><b>Per-node lock + hand-over-hand ("lock coupling") traversal.</b> Each directory gets
 *       its own lock; a traversal acquires the child's lock before releasing the parent's.
 *       This maximizes concurrency but is notoriously hard to get right for operations that
 *       touch <em>two</em> arbitrary subtrees at once (mv/cp between unrelated directories):
 *       the lock-acquisition order must be globally consistent to avoid deadlock, and a
 *       recursive copy that walks an entire subtree while holding a chain of locks is prone to
 *       lock-ordering bugs under review. Rejected for an interview-scope solution as too easy to
 *       get subtly wrong (the exact failure mode interviewers probe for).</li>
 *   <li><b>Copy-on-write / persistent immutable tree.</b> Every mutation builds new nodes along
 *       the path from the mutation point to the root and atomically swaps a single "current
 *       root" reference (like Clojure persistent maps / MVCC). Readers never block and always
 *       see a consistent snapshot. Rejected as the <em>primary</em> design here because it is
 *       more machinery than an LLD round needs and makes in-place content mutation (appending to
 *       a large file) expensive (must rebuild the path to root). Documented in
 *       STAFF_LEVEL_DEEP_DIVE.md as the natural evolution for a read-heavy, high-contention
 *       production system.</li>
 *   <li><b>Two-tier locking: one coarse {@link ReentrantReadWriteLock} for tree
 *       <em>structure</em> (which names exist where) + one fine-grained lock per {@link File}
 *       for <em>content</em>.</b> Chosen. All structure-changing operations (mkdir, rm, mv, the
 *       tree-walking part of cp) acquire {@link #treeLock}'s write lock for their entire
 *       duration; all structure-reading operations (ls, pwd, path resolution, cd) acquire its
 *       read lock. File content I/O (cat/write) does not touch {@link #treeLock} at all -- it
 *       goes through {@link File}'s own lock, so a large file read/append can proceed while an
 *       unrelated {@code mkdir} happens elsewhere.</li>
 * </ol>
 *
 * <p><b>Why this is correct for the motivating race ("thread A removes directory X while thread
 * B copies into X").</b> Both {@code rm} and {@code cp} take {@link #treeLock}'s write lock for
 * their <em>entire</em> recursive duration (the whole subtree is walked and copied/deleted while
 * holding the single lock, not re-acquired per node). Since a {@link ReentrantReadWriteLock}'s
 * write lock is exclusive against all other read and write acquisitions, whichever operation
 * calls {@code lock()} first runs to completion -- including its cycle/existence checks -- before
 * the other can start. There is no interleaving in which B observes X half-deleted, or A
 * observes a partially-copied subtree: the whole operation is linearizable with respect to every
 * other structural operation. The cost is that structural operations across the whole tree are
 * serialized with each other (a {@code mkdir} in an unrelated subtree also waits), which is the
 * trade-off documented above and in STAFF_LEVEL_DEEP_DIVE.md.</p>
 *
 * <p><b>Cycle prevention under concurrency.</b> {@code mv}'s cycle check
 * ({@code source.isAncestorOf(destinationParent)}) walks {@code parent} pointers while holding
 * the write lock, so no concurrent {@code mv} can be re-parenting a node mid-walk; the check and
 * the subsequent detach/attach happen atomically under the same lock acquisition.</p>
 */
public final class FileSystem {

    private final Directory root;
    private final ReadWriteLock treeLock = new ReentrantReadWriteLock();

    public FileSystem() {
        this.root = Directory.createRoot();
    }

    public Session newSession() {
        return new Session(root);
    }

    // ------------------------------------------------------------------
    // pwd / cd
    // ------------------------------------------------------------------

    public String pwd(Session session) {
        treeLock.readLock().lock();
        try {
            return PathResolver.toPathString(session.cwdSegments());
        } finally {
            treeLock.readLock().unlock();
        }
    }

    public void cd(Session session, String path) {
        treeLock.readLock().lock();
        try {
            Directory target = resolveDirectoryLocked(session, path);
            session.setCwd(target);
        } finally {
            treeLock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // ls
    // ------------------------------------------------------------------

    public List<FileSystemEntry> ls(Session session, String path) {
        treeLock.readLock().lock();
        try {
            Directory dir = resolveDirectoryLocked(session, path);
            return dir.listChildren();
        } finally {
            treeLock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // mkdir
    // ------------------------------------------------------------------

    public Directory mkdir(Session session, String path, boolean createParents) {
        treeLock.writeLock().lock();
        try {
            List<String> absolute = PathResolver.resolveToAbsoluteSegments(path, session.cwdSegments());
            if (absolute.isEmpty()) {
                throw new FileAlreadyExistsException("/");
            }
            Directory cursor = root;
            for (int i = 0; i < absolute.size(); i++) {
                String segment = absolute.get(i);
                FileSystemEntry existing = cursor.getChild(segment);
                boolean isLast = (i == absolute.size() - 1);
                if (existing == null) {
                    if (!isLast && !createParents) {
                        throw new PathNotFoundException(PathResolver.toPathString(absolute.subList(0, i + 1)));
                    }
                    Directory created = new Directory(segment, cursor);
                    cursor.putChild(created);
                    cursor = created;
                } else if (existing.isDirectory()) {
                    if (isLast && !createParents) {
                        throw new FileAlreadyExistsException(PathResolver.toPathString(absolute.subList(0, i + 1)));
                    }
                    cursor = (Directory) existing;
                } else {
                    throw new NotADirectoryException(PathResolver.toPathString(absolute.subList(0, i + 1)));
                }
            }
            return cursor;
        } finally {
            treeLock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // touch / cat / write
    // ------------------------------------------------------------------

    public File touch(Session session, String path) {
        treeLock.writeLock().lock();
        try {
            PathResolver.ParentAndName pan = PathResolver.splitParentAndName(path, session.cwdSegments());
            Directory parent = resolveDirectoryBySegments(pan.parentSegments());
            FileSystemEntry existing = parent.getChild(pan.name());
            if (existing != null) {
                if (!existing.isDirectory()) {
                    return (File) existing; // idempotent touch, like POSIX touch on existing file
                }
                throw new NotAFileException(path);
            }
            File file = new File(pan.name(), parent);
            parent.putChild(file);
            return file;
        } finally {
            treeLock.writeLock().unlock();
        }
    }

    public String cat(Session session, String path) {
        File file = resolveFile(session, path);
        return file.read();
    }

    public void write(Session session, String path, String content, boolean append) {
        File file;
        treeLock.writeLock().lock();
        try {
            PathResolver.ParentAndName pan = PathResolver.splitParentAndName(path, session.cwdSegments());
            Directory parent = resolveDirectoryBySegments(pan.parentSegments());
            FileSystemEntry existing = parent.getChild(pan.name());
            if (existing == null) {
                file = new File(pan.name(), parent);
                parent.putChild(file);
            } else if (existing.isDirectory()) {
                throw new NotAFileException(path);
            } else {
                file = (File) existing;
            }
        } finally {
            treeLock.writeLock().unlock();
        }
        // Content mutation happens outside treeLock, under File's own lock -- see class javadoc.
        file.write(content, append);
    }

    // ------------------------------------------------------------------
    // rm
    // ------------------------------------------------------------------

    public void rm(Session session, String path, boolean recursive, boolean force) {
        treeLock.writeLock().lock();
        try {
            List<String> absolute = PathResolver.resolveToAbsoluteSegments(path, session.cwdSegments());
            if (absolute.isEmpty()) {
                throw new InvalidOperationException("Refusing to remove the root directory");
            }
            FileSystemEntry entry = resolveEntryBySegments(absolute, force);
            if (entry == null) {
                return; // force: missing path is a no-op
            }
            if (entry.isDirectory() && !((Directory) entry).isEmpty() && !recursive) {
                throw new DirectoryNotEmptyException(path);
            }
            if (isWithinSessionCwd(session, entry)) {
                throw new InvalidOperationException("Cannot remove an ancestor of the current working directory: " + path);
            }
            Directory parent = entry.getParent();
            parent.removeChild(entry.getName());
        } finally {
            treeLock.writeLock().unlock();
        }
    }

    private boolean isWithinSessionCwd(Session session, FileSystemEntry entry) {
        return entry.isDirectory() && ((Directory) entry).isAncestorOf(session.getCwd());
    }

    // ------------------------------------------------------------------
    // cp
    // ------------------------------------------------------------------

    public void cp(Session session, String sourcePath, String destPath, boolean recursive, boolean overwrite) {
        treeLock.writeLock().lock();
        try {
            FileSystemEntry source = resolveEntryRequired(session, sourcePath);
            if (source.isDirectory() && !recursive) {
                throw new InvalidOperationException("cp: -r not specified; omitting directory '" + sourcePath + "'");
            }
            PathResolver.ParentAndName destPan = PathResolver.splitParentAndName(destPath, session.cwdSegments());
            Directory destParent = resolveDirectoryBySegments(destPan.parentSegments());

            // Note: unlike mv, cp never re-parents the source, so copying a directory into one
            // of its own descendants is well-defined and terminates: deepCopy() walks a snapshot
            // of the source's children taken via listChildren() at call time, so nodes created
            // by the copy itself are never visited. No cycle check is needed here.
            FileSystemEntry existingAtDest = destParent.getChild(destPan.name());
            Directory effectiveDestParent = destParent;
            String effectiveDestName = destPan.name();
            if (existingAtDest != null && existingAtDest.isDirectory() && source.isDirectory()) {
                // cp dirA dirB where dirB exists => copy dirA *into* dirB, like POSIX cp -r.
                effectiveDestParent = (Directory) existingAtDest;
                effectiveDestName = source.getName();
                existingAtDest = effectiveDestParent.getChild(effectiveDestName);
            }
            if (existingAtDest != null) {
                if (!overwrite) {
                    throw new FileAlreadyExistsException(destPath);
                }
                if (existingAtDest.isDirectory() != source.isDirectory()) {
                    throw new InvalidOperationException(
                            "cp: cannot overwrite " + (existingAtDest.isDirectory() ? "directory" : "file")
                                    + " with " + (source.isDirectory() ? "directory" : "file") + ": " + destPath);
                }
                effectiveDestParent.removeChild(effectiveDestName);
            }

            FileSystemEntry copy = deepCopy(source, effectiveDestName, effectiveDestParent);
            effectiveDestParent.putChild(copy);
        } finally {
            treeLock.writeLock().unlock();
        }
    }

    private FileSystemEntry deepCopy(FileSystemEntry source, String newName, Directory newParent) {
        if (!source.isDirectory()) {
            return ((File) source).copyAs(newName, newParent);
        }
        Directory sourceDir = (Directory) source;
        Directory copy = new Directory(newName, newParent);
        for (FileSystemEntry child : sourceDir.listChildren()) {
            copy.putChild(deepCopy(child, child.getName(), copy));
        }
        return copy;
    }

    // ------------------------------------------------------------------
    // mv
    // ------------------------------------------------------------------

    public void mv(Session session, String sourcePath, String destPath, boolean overwrite) {
        treeLock.writeLock().lock();
        try {
            FileSystemEntry source = resolveEntryRequired(session, sourcePath);
            if (source.getParent() == null) {
                throw new InvalidOperationException("Cannot move the root directory");
            }
            PathResolver.ParentAndName destPan = PathResolver.splitParentAndName(destPath, session.cwdSegments());
            Directory destParent = resolveDirectoryBySegments(destPan.parentSegments());

            if (destParent == source.getParent() && destPan.name().equals(source.getName())) {
                throw new InvalidOperationException(
                        "mv: '" + sourcePath + "' and '" + destPath + "' are the same file");
            }

            Directory effectiveDestParent = destParent;
            String effectiveDestName = destPan.name();
            FileSystemEntry existingAtDest = destParent.getChild(destPan.name());
            if (existingAtDest != null && existingAtDest.isDirectory() && source.isDirectory()
                    && existingAtDest != source) {
                // mv dirA dirB where dirB exists => move dirA *into* dirB, like POSIX mv.
                effectiveDestParent = (Directory) existingAtDest;
                effectiveDestName = source.getName();
                existingAtDest = effectiveDestParent.getChild(effectiveDestName);
            }

            if (source.isDirectory() && (effectiveDestParent == source || source.isAncestorOf(effectiveDestParent))) {
                throw new InvalidOperationException(
                        "mv: cannot move '" + sourcePath + "' into itself or one of its own descendants: '" + destPath + "'");
            }
            if (source.isAncestorOf(session.getCwd()) && source != session.getCwd()) {
                // Allowed in real shells, but we conservatively forbid moving an ancestor of cwd
                // out from under the active session to avoid a dangling-cwd surprise in a demo/CLI.
                throw new InvalidOperationException("Cannot move an ancestor of the current working directory: " + sourcePath);
            }

            if (existingAtDest != null && existingAtDest != source) {
                if (!overwrite) {
                    throw new FileAlreadyExistsException(destPath);
                }
                if (existingAtDest.isDirectory() != source.isDirectory()) {
                    throw new InvalidOperationException(
                            "mv: cannot overwrite " + (existingAtDest.isDirectory() ? "directory" : "file")
                                    + " with " + (source.isDirectory() ? "directory" : "file") + ": " + destPath);
                }
                if (existingAtDest.isDirectory() && !((Directory) existingAtDest).isEmpty()) {
                    throw new DirectoryNotEmptyException(destPath);
                }
                effectiveDestParent.removeChild(effectiveDestName);
            }

            Directory oldParent = source.getParent();
            oldParent.removeChild(source.getName());
            source.setName(effectiveDestName);
            source.setParent(effectiveDestParent);
            effectiveDestParent.putChild(source);
        } finally {
            treeLock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // Path resolution helpers (caller must hold treeLock)
    // ------------------------------------------------------------------

    private Directory resolveDirectoryLocked(Session session, String path) {
        List<String> absolute = PathResolver.resolveToAbsoluteSegments(path, session.cwdSegments());
        return resolveDirectoryBySegments(absolute);
    }

    private Directory resolveDirectoryBySegments(List<String> absoluteSegments) {
        FileSystemEntry entry = resolveEntryBySegments(absoluteSegments, false);
        if (entry == null) {
            throw new PathNotFoundException(PathResolver.toPathString(absoluteSegments));
        }
        if (!entry.isDirectory()) {
            throw new NotADirectoryException(PathResolver.toPathString(absoluteSegments));
        }
        return (Directory) entry;
    }

    private FileSystemEntry resolveEntryRequired(Session session, String path) {
        List<String> absolute = PathResolver.resolveToAbsoluteSegments(path, session.cwdSegments());
        FileSystemEntry entry = resolveEntryBySegments(absolute, false);
        if (entry == null) {
            throw new PathNotFoundException(path);
        }
        return entry;
    }

    private File resolveFile(Session session, String path) {
        treeLock.readLock().lock();
        try {
            List<String> absolute = PathResolver.resolveToAbsoluteSegments(path, session.cwdSegments());
            FileSystemEntry entry = resolveEntryBySegments(absolute, false);
            if (entry == null) {
                throw new PathNotFoundException(path);
            }
            if (entry.isDirectory()) {
                throw new NotAFileException(path);
            }
            return (File) entry;
        } finally {
            treeLock.readLock().unlock();
        }
    }

    /**
     * Walks from root following {@code absoluteSegments}. Returns {@code null} if not found and
     * {@code tolerateMissing} is true; otherwise throws {@link PathNotFoundException} /
     * {@link NotADirectoryException} as appropriate for an intermediate segment that is not a
     * directory.
     */
    private FileSystemEntry resolveEntryBySegments(List<String> absoluteSegments, boolean tolerateMissing) {
        if (absoluteSegments.isEmpty()) {
            return root;
        }
        FileSystemEntry cursor = root;
        for (int i = 0; i < absoluteSegments.size(); i++) {
            if (!cursor.isDirectory()) {
                throw new NotADirectoryException(PathResolver.toPathString(absoluteSegments.subList(0, i)));
            }
            FileSystemEntry next = ((Directory) cursor).getChild(absoluteSegments.get(i));
            if (next == null) {
                if (tolerateMissing) {
                    return null;
                }
                throw new PathNotFoundException(PathResolver.toPathString(absoluteSegments.subList(0, i + 1)));
            }
            cursor = next;
        }
        return cursor;
    }
}
