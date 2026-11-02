package lld.filesystem;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A leaf node in the Composite tree: holds byte content.
 *
 * <p><b>Thread-safety.</b> Content is guarded by its own {@link ReentrantReadWriteLock},
 * independent of the global tree-structure lock held by {@link FileSystem}. This lets many
 * concurrent {@code cat} calls on the same file run in parallel (shared read lock) while a
 * {@code write} takes the exclusive write lock, and lets content I/O on file A proceed
 * concurrently with a structural operation (mkdir/mv/rm) happening elsewhere in the tree that
 * does not touch file A -- see {@link FileSystem} for why structural operations use a coarser,
 * separate lock.</p>
 */
public final class File extends FileSystemEntry {

    private final ReadWriteLock contentLock = new ReentrantReadWriteLock();
    private byte[] content;

    File(String name, Directory parent) {
        super(name, parent);
        this.content = new byte[0];
    }

    @Override
    public boolean isDirectory() {
        return false;
    }

    @Override
    public long size() {
        contentLock.readLock().lock();
        try {
            return content.length;
        } finally {
            contentLock.readLock().unlock();
        }
    }

    /** Returns the current content decoded as UTF-8 text. */
    public String read() {
        contentLock.readLock().lock();
        try {
            return new String(content, StandardCharsets.UTF_8);
        } finally {
            contentLock.readLock().unlock();
        }
    }

    public byte[] readBytes() {
        contentLock.readLock().lock();
        try {
            return content.clone();
        } finally {
            contentLock.readLock().unlock();
        }
    }

    /**
     * Overwrites or appends UTF-8 text content.
     *
     * @param text   the text to write
     * @param append if true, the text is appended; otherwise it replaces existing content
     */
    public void write(String text, boolean append) {
        byte[] incoming = text.getBytes(StandardCharsets.UTF_8);
        contentLock.writeLock().lock();
        try {
            if (append && content.length > 0) {
                byte[] merged = new byte[content.length + incoming.length];
                System.arraycopy(content, 0, merged, 0, content.length);
                System.arraycopy(incoming, 0, merged, content.length, incoming.length);
                content = merged;
            } else {
                content = incoming;
            }
            touch();
        } finally {
            contentLock.writeLock().unlock();
        }
    }

    /** Deep-copies the content into a brand-new {@link File} under {@code newParent}. */
    File copyAs(String newName, Directory newParent) {
        File copy = new File(newName, newParent);
        contentLock.readLock().lock();
        try {
            copy.content = this.content.clone();
        } finally {
            contentLock.readLock().unlock();
        }
        return copy;
    }
}
