package lld.clouddrive.model;

import java.util.Arrays;

/**
 * A single physically-stored, content-addressed chunk of bytes, keyed by its content hash.
 * Multiple {@link FileVersion}s (belonging to the same user or different users) may reference
 * the same {@code Blob} -- that is the whole point of content-addressed deduplication.
 *
 * <p><b>Concurrency contract:</b> {@link #refCount} is a plain mutable {@code int}, not an
 * {@link java.util.concurrent.atomic.AtomicInteger}. That is intentional, not an oversight: every
 * mutation of a {@code Blob}'s ref count MUST happen exclusively from inside a
 * {@code ConcurrentHashMap.compute(hash, ...)} callback in {@code BlobStore}, which the JDK
 * guarantees is invoked atomically per key (no other thread can observe or mutate the mapping for
 * that key concurrently, see {@link java.util.concurrent.ConcurrentHashMap#compute}). Reaching
 * into a {@code Blob} and calling {@link #incrementRef()}/{@link #decrementRef()} from anywhere
 * else would reintroduce the classic increment/decrement-and-free race this design avoids. This
 * invariant is enforced by keeping these methods package-private to {@code lld.clouddrive.model}
 * and only calling them from {@code BlobStore} via a package-private accessor -- see
 * {@code lld.clouddrive.store.InMemoryBlobStore} for the single call site.
 */
public final class Blob {

    private final String contentHash;
    private final byte[] content;
    private final long sizeBytes;
    private int refCount;

    public Blob(String contentHash, byte[] content, int initialRefCount) {
        this.contentHash = contentHash;
        this.content = content;
        this.sizeBytes = content.length;
        this.refCount = initialRefCount;
    }

    public String getContentHash() {
        return contentHash;
    }

    /** Defensive copy: callers must not be able to mutate stored content in place. */
    public byte[] getContent() {
        return Arrays.copyOf(content, content.length);
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public int getRefCount() {
        return refCount;
    }

    /** Must only be invoked from within a {@code ConcurrentHashMap.compute} callback. */
    public void incrementRef() {
        refCount++;
    }

    /** Must only be invoked from within a {@code ConcurrentHashMap.compute} callback. */
    public void decrementRef() {
        refCount--;
    }
}
