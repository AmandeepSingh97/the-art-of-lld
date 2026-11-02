package lld.filesystem;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents one "terminal session": a current-working-directory pointer into a shared
 * {@link FileSystem}. Multiple sessions can point at the same {@link FileSystem} instance
 * simultaneously (e.g. two terminal tabs), each with an independently movable cwd, analogous to
 * per-process cwd in a real OS.
 *
 * <p><b>Thread-safety.</b> A single {@code Session} is intended to be driven by one logical
 * actor (one thread/tab) at a time, same as a real shell is single-threaded from the user's
 * perspective. The cwd reference is still held in an {@link AtomicReference} rather than a plain
 * field so that a concurrent structural change elsewhere in the tree (another session deleting
 * this session's cwd out from under it) is published safely and {@code cd ..} / {@code pwd}
 * never observe a torn write.</p>
 */
public final class Session {

    private final AtomicReference<Directory> cwd;

    Session(Directory initialCwd) {
        this.cwd = new AtomicReference<>(initialCwd);
    }

    Directory getCwd() {
        return cwd.get();
    }

    void setCwd(Directory directory) {
        cwd.set(directory);
    }

    /**
     * Computes the absolute path segments of the current working directory by walking parent
     * pointers up to the root. Must be called while holding at least the tree read lock, since
     * {@code parent} pointers can change concurrently due to {@code mv}.
     */
    List<String> cwdSegments() {
        Directory dir = cwd.get();
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        while (dir != null && dir.getParent() != null) {
            names.add(0, dir.getName());
            dir = dir.getParent();
        }
        return names;
    }
}
