package lld.lfucache;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe {@link Cache} built by wrapping a single-threaded delegate
 * (typically {@link LFUCache}) with a single coarse-grained {@link
 * ReentrantLock}.
 *
 * <h2>Why a single lock, not striping or lock-free</h2>
 * LFU's internal structure makes fine-grained locking unusually hard to get
 * right, which is exactly why this class exists as an explicit, separate
 * decision point rather than being folded invisibly into {@link LFUCache}:
 * <ul>
 *   <li><b>Striped locking</b> (e.g. one lock per key hash, as in {@code
 *       ConcurrentHashMap}) works when operations touch only the data under
 *       one stripe. Here, a single {@code get} can touch <em>two</em>
 *       frequency buckets (old and new) plus the global {@code
 *       minFrequency} counter, and those buckets are shared by every key at
 *       that frequency — i.e. by keys that would hash to different stripes.
 *       Striping by key cannot make bucket access single-stripe, and
 *       striping by frequency does not help either because {@code
 *       minFrequency} is still global and every {@code touch} can mutate
 *       it.</li>
 *   <li><b>Lock-free / CAS-based</b> approaches struggle for the same
 *       reason: a single logical operation (bump frequency) is really a
 *       multi-location update (unlink from bucket A, relink into bucket B,
 *       possibly delete bucket A, possibly bump {@code minFrequency}) that
 *       must be atomic as a whole. There is no single machine word whose
 *       CAS captures "moved this node and fixed up minFrequency correctly,"
 *       so a lock-free version needs something like a multi-word CAS
 *       protocol (e.g. a mini STM) — real, but far beyond interview scope
 *       and rarely worth the complexity versus a lock for cache-sized
 *       critical sections (see {@code STAFF_LEVEL_DEEP_DIVE.md}).</li>
 *   <li>A single lock is trivially correct, is easy to reason about for
 *       deadlock freedom (one lock can't deadlock with itself), and the
 *       critical section is O(1) work — so contention cost is bounded and
 *       predictable. For an LFU cache (as opposed to, say, a sharded
 *       distributed cache), this is the pragmatic staff-level choice: don't
 *       buy correctness risk for a win that the O(1) critical section makes
 *       marginal.</li>
 * </ul>
 *
 * <p>This class is a {@code Cache} decorator rather than a modification of
 * {@link LFUCache} itself, so single-threaded callers pay zero locking
 * overhead and the concurrency story is isolated in one reviewable place
 * (Single Responsibility / Open-Closed).
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class ConcurrentLFUCache<K, V> implements Cache<K, V> {

    private final Cache<K, V> delegate;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Wraps {@code delegate} with a single lock guarding every operation.
     * The delegate must not be shared with (or mutated directly by) any
     * other caller, or this class's guarantees are void.
     *
     * @param delegate the single-threaded cache to protect; must not be
     *                 {@code null}.
     */
    public ConcurrentLFUCache(Cache<K, V> delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Complexity:</b> O(1) amortized for the delegate's work, plus the
     * (uncontended-case) cost of one lock acquire/release.
     */
    @Override
    public Optional<V> get(K key) {
        lock.lock();
        try {
            return delegate.get(key);
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void put(K key, V value) {
        lock.lock();
        try {
            delegate.put(key, value);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return delegate.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int capacity() {
        // Capacity is fixed at construction time and never mutated, so it is
        // safe to read without the lock — there is no race to protect
        // against on an effectively-final field exposed via a pure getter.
        return delegate.capacity();
    }
}
