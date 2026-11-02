package lld.clouddrive.store;

import lld.clouddrive.model.Blob;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency-correctness tests for {@link InMemoryBlobStore}'s reference counting -- the second
 * concurrency guarantee called out in the README: a blob backing deduplicated content must never
 * be freed while any reference to it is still outstanding, and must never leak (stay allocated
 * forever after its last reference is dropped).
 */
class BlobStoreConcurrencyTest {

    @RepeatedTest(5)
    void concurrentRetainsOfIdenticalContentAllSucceedAndRefCountMatchesCallerCount() throws InterruptedException {
        BlobStore store = new InMemoryBlobStore(new Sha256HashingStrategy());
        byte[] content = "identical payload".getBytes(StandardCharsets.UTF_8);
        int threadCount = 64;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    store.storeAndRetain(content);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, store.blobCount(), "identical content must dedupe down to exactly one physical blob");
        String hash = new Sha256HashingStrategy().hash(content);
        assertEquals(threadCount, store.get(hash).getRefCount(),
                "ref count must equal the number of outstanding retains, with none lost to races");
    }

    @Test
    void concurrentRetainAndReleaseNeverFreesABlobWithLiveReferences() throws InterruptedException {
        BlobStore store = new InMemoryBlobStore(new Sha256HashingStrategy());
        byte[] content = "contended payload".getBytes(StandardCharsets.UTF_8);
        String hash = new Sha256HashingStrategy().hash(content);

        // Seed with one long-lived reference so the blob is never expected to disappear.
        store.storeAndRetain(content);

        int threadCount = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger observedNullMidFlight = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    store.storeAndRetain(content); // +1 ref
                    if (store.get(hash) == null) {
                        observedNullMidFlight.incrementAndGet();
                    }
                    store.release(hash); // -1 ref, back to the seeded reference
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(0, observedNullMidFlight.get(),
                "a blob with the seeded reference still outstanding must never appear absent from the store");
        Blob remaining = store.get(hash);
        assertNotNull(remaining, "the seeded reference must keep the blob alive");
        assertEquals(1, remaining.getRefCount(), "all transient +1/-1 pairs must net out to zero extra refs");
    }

    @Test
    void lastReleaseActuallyRemovesTheBlob() {
        BlobStore store = new InMemoryBlobStore(new Sha256HashingStrategy());
        byte[] content = "solo payload".getBytes(StandardCharsets.UTF_8);
        Blob blob = store.storeAndRetain(content);

        assertEquals(1, blob.getRefCount());
        store.release(blob.getContentHash());

        assertNull(store.get(blob.getContentHash()), "last release must free the blob");
        assertEquals(0, store.blobCount());
    }
}
