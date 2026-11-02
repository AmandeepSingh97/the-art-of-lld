package lld.clouddrive.store;

/**
 * Strategy for turning raw file content into the content-address key used for deduplication.
 * Pulled behind an interface so the hash algorithm (or, for a production extension, a
 * content-defined chunking scheme instead of whole-file hashing -- see README section 10) can be
 * swapped without touching {@code BlobStore} or any caller.
 */
public interface HashingStrategy {

    /** @return a stable hex-encoded digest of {@code content}; equal content -> equal digest. */
    String hash(byte[] content);
}
