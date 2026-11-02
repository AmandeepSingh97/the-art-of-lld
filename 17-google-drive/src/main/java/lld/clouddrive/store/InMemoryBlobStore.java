package lld.clouddrive.store;

import lld.clouddrive.model.Blob;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link BlobStore} keyed by content hash.
 *
 * <p><b>Why this is race-free:</b> every mutation of a blob's reference count --
 * create-with-refcount-1, increment-on-dedup-hit, decrement-and-maybe-remove -- happens inside a
 * single {@link ConcurrentHashMap#compute} call for that key. The JDK guarantees the remapping
 * function for a given key is applied atomically with respect to other operations on the SAME
 * key: no other thread can observe a partially-updated mapping, and no other thread can run a
 * {@code compute}/{@code put}/{@code remove} for that same key concurrently while one is in
 * flight (internally this is implemented with a lock on the hash bin). That closes the classic
 * ref-count race where thread A decrements 1 -> 0 and starts freeing the blob while thread B
 * concurrently increments 0 -> 1 believing it is adding a new reference to a blob that is about
 * to disappear out from under it: here, "check ref count, remove if zero" and "check if present,
 * increment" can never interleave because they are the same critical section for a given hash.
 *
 * <p>Different hashes still proceed fully in parallel (different map bins, no shared lock), so
 * contention is limited to concurrent writers of literally the same byte-identical content.
 */
public final class InMemoryBlobStore implements BlobStore {

    private final ConcurrentHashMap<String, Blob> blobs = new ConcurrentHashMap<>();
    private final HashingStrategy hashingStrategy;

    public InMemoryBlobStore(HashingStrategy hashingStrategy) {
        this.hashingStrategy = hashingStrategy;
    }

    @Override
    public Blob storeAndRetain(byte[] content) {
        String hash = hashingStrategy.hash(content);
        return blobs.compute(hash, (key, existing) -> {
            if (existing == null) {
                return new Blob(key, content, 1);
            }
            existing.incrementRef();
            return existing;
        });
    }

    @Override
    public void release(String contentHash) {
        blobs.compute(contentHash, (key, existing) -> {
            if (existing == null) {
                throw new IllegalStateException("release() called on unknown blob hash " + key);
            }
            existing.decrementRef();
            if (existing.getRefCount() <= 0) {
                return null; // returning null from compute() removes the mapping atomically.
            }
            return existing;
        });
    }

    @Override
    public Blob get(String contentHash) {
        return blobs.get(contentHash);
    }

    @Override
    public int blobCount() {
        return blobs.size();
    }
}
