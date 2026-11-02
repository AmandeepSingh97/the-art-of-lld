package lld.clouddrive.store;

import lld.clouddrive.model.Blob;

/**
 * Content-addressed storage with reference counting, the mechanism that implements
 * deduplication: identical content, uploaded once or a thousand times by any number of users,
 * is physically stored exactly once.
 */
public interface BlobStore {

    /**
     * Stores {@code content}, deduplicating against any existing blob with the same content
     * hash. If a blob with that hash already exists, its reference count is atomically
     * incremented instead of storing a second physical copy.
     *
     * @return the (possibly pre-existing) {@link Blob} backing this content.
     */
    Blob storeAndRetain(byte[] content);

    /**
     * Drops one reference to the blob identified by {@code contentHash}. When the reference
     * count reaches zero the blob is physically removed from the store.
     *
     * @throws IllegalStateException if the hash is unknown or already has zero references.
     */
    void release(String contentHash);

    /** @return the blob for {@code contentHash}, or {@code null} if it does not exist. */
    Blob get(String contentHash);

    /** @return number of distinct physical blobs currently stored (for metrics/tests). */
    int blobCount();
}
