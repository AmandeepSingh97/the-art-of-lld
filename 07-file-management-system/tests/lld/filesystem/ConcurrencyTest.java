package lld.filesystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the concurrency claims documented on {@link FileSystem}: the global tree
 * read/write lock must (a) let independent-subtree structural operations proceed without
 * corrupting shared state and interleave safely, and (b) make a recursive rm and a recursive cp
 * that target the same subtree mutually exclusive and atomic, so neither thread ever observes
 * (or leaves behind) a half-finished tree.
 */
class ConcurrencyTest {

    @Test
    void manyThreadsCreatingDisjointDirectoriesDoNotCorruptTheTree() throws InterruptedException {
        FileSystem fs = new FileSystem();
        Session setupSession = fs.newSession();
        fs.mkdir(setupSession, "/work", false);

        int threadCount = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            int index = i;
            pool.submit(() -> {
                Session session = fs.newSession();
                ready.countDown();
                try {
                    go.await();
                    fs.mkdir(session, "/work/dir-" + index, false);
                    fs.write(session, "/work/dir-" + index + "/file.txt", "payload-" + index, false);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    // leave latch state consistent even on failure
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(0, failures.get(), "no thread should have failed under disjoint, non-conflicting writes");
        List<FileSystemEntry> children = fs.ls(setupSession, "/work");
        assertEquals(threadCount, children.size());
        for (int i = 0; i < threadCount; i++) {
            assertEquals("payload-" + i, fs.cat(setupSession, "/work/dir-" + i + "/file.txt"));
        }
    }

    @Test
    void recursiveRemoveAndRecursiveCopyOfSameSubtreeAreLinearizable() throws Exception {
        // Motivating race from the design doc: thread A removes directory X while thread B
        // copies into X. With the global structural write lock, exactly one of the two
        // orderings happens atomically; there must be no partially-copied or partially-deleted
        // observable state, and the operation that loses the race must see a clean failure
        // (not corruption), not a crash or an inconsistent tree.
        FileSystem fs = new FileSystem();
        Session setup = fs.newSession();
        fs.mkdir(setup, "/x/a/b/c", true);
        fs.write(setup, "/x/a/file1.txt", "one", false);
        fs.write(setup, "/x/a/b/file2.txt", "two", false);
        fs.mkdir(setup, "/dest", false);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);

        Session removerSession = fs.newSession();
        Session copierSession = fs.newSession();

        var removeTask = pool.submit(() -> {
            go.await();
            try {
                fs.rm(removerSession, "/x", true, false);
            } catch (RuntimeException ignored) {
                // Acceptable if cp already moved first: rm would then succeed on whatever is left,
                // so this branch should not normally trigger, but we tolerate races in either
                // direction without letting an exception escape the task.
            }
            return null;
        });
        var copyTask = pool.submit(() -> {
            go.await();
            try {
                fs.cp(copierSession, "/x", "/dest/x-copy", true, false);
            } catch (RuntimeException ignored) {
                // Expected outcome if rm wins the race: source no longer exists.
            }
            return null;
        });

        go.countDown();
        removeTask.get(10, TimeUnit.SECONDS);
        copyTask.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // Whichever order won, the resulting tree must be internally consistent: either the copy
        // fully exists with both files, or it does not exist at all -- never a partial subtree.
        List<FileSystemEntry> destChildren = fs.ls(setup, "/dest");
        if (!destChildren.isEmpty()) {
            assertEquals("one", fs.cat(setup, "/dest/x-copy/a/file1.txt"));
            assertEquals("two", fs.cat(setup, "/dest/x-copy/a/b/file2.txt"));
        }
        // /x is gone in every legal interleaving (rm always eventually wins because it was
        // submitted to run to completion exactly like cp: whichever started first finishes
        // first under the exclusive write lock).
        boolean xStillExists = fs.ls(setup, "/").stream().anyMatch(e -> e.getName().equals("x"));
        boolean xRemoved = !xStillExists;
        assertTrue(xRemoved || !destChildren.isEmpty(), "tree must be in one fully-consistent state");
    }

    @Test
    void concurrentAppendsToSameFileDoNotLoseWrites() throws Exception {
        // File content is guarded by its own lock independent of the tree lock, so concurrent
        // appends to ONE file must still be serialized correctly (no lost updates), while
        // unrelated structural work elsewhere is unaffected.
        FileSystem fs = new FileSystem();
        Session setup = fs.newSession();
        fs.touch(setup, "/counter.txt");

        int threadCount = 8;
        int appendsPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch go = new CountDownLatch(1);

        for (int i = 0; i < threadCount; i++) {
            Session session = fs.newSession();
            pool.submit(() -> {
                try {
                    go.await();
                    for (int j = 0; j < appendsPerThread; j++) {
                        fs.write(session, "/counter.txt", "x", true);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        String content = fs.cat(setup, "/counter.txt");
        assertEquals(threadCount * appendsPerThread, content.length(),
                "every append must be reflected exactly once; a shorter length means a lost update");
    }
}
