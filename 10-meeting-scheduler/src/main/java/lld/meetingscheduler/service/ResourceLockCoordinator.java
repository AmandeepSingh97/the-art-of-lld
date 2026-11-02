package lld.meetingscheduler.service;

import lld.meetingscheduler.exception.SchedulingTimeoutException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Provides the cross-resource atomicity guarantee a single booking needs: "check and then
 * commit across the room's calendar AND every attendee's calendar happens as one indivisible
 * step", so no other thread can observe or create a half-booked state, and no two concurrent
 * requests for the same slot on the same resource can both succeed (double-booking).
 *
 * <h2>Why a global lock per resource key, acquired in sorted order</h2>
 * A single booking touches multiple independent resources (one room + N attendees). Taking
 * locks on them one at a time, in whatever order the attendee set happens to iterate in, is the
 * textbook setup for deadlock: thread A books (room-1, alice, bob) and locks room-1 then blocks
 * waiting for alice, while thread B books (alice, room-1) and has already locked alice and is
 * waiting for room-1 -- classic circular wait.
 *
 * <p>This class removes that risk structurally: every caller sorts its resource keys and
 * acquires locks in that fixed global order (lexicographic on the resource key string), every
 * time, for every booking. Two threads contending for an overlapping resource set will always
 * attempt to acquire their shared resources in the same relative order, so circular wait (one
 * of the four necessary conditions for deadlock, per the standard Coffman conditions) cannot
 * arise. Combined with bounded {@link ReentrantLock#tryLock(long, TimeUnit)} waits (rather than
 * unbounded {@code lock()}), a thread that still cannot make progress fails fast with
 * {@link SchedulingTimeoutException} instead of blocking forever.
 *
 * <h2>Why pessimistic (lock-then-check) rather than optimistic concurrency</h2>
 * An optimistic approach (read a snapshot version of each calendar, validate, commit with a
 * compare-and-swap, retry on conflict) avoids holding locks during validation but requires
 * every calendar to expose a versioned/CAS-capable API and a retry loop at the call site, and
 * degrades badly under high contention on a single hot resource (e.g. a single popular
 * conference room during a busy morning) because every retry redoes the full validation.
 * Pessimistic locking is simpler to reason about and is cheap here because the critical section
 * (a handful of O(log n) interval lookups) is extremely short -- lock hold time, not locking
 * strategy, is what would dominate contention, and it is already minimal. See README §11 and
 * §16 for the fuller trade-off writeup.
 */
public final class ResourceLockCoordinator {

    private final ConcurrentHashMap<String, ReentrantLock> locksByResourceKey = new ConcurrentHashMap<>();

    private ReentrantLock lockFor(String resourceKey) {
        return locksByResourceKey.computeIfAbsent(resourceKey, key -> new ReentrantLock(true));
    }

    /**
     * Acquires locks for every key in {@code resourceKeys} (deduplicated, sorted) within
     * {@code timeout}, runs {@code criticalSection}, and releases all acquired locks -- in
     * reverse acquisition order -- whether the critical section succeeds or throws.
     *
     * @throws SchedulingTimeoutException if any lock cannot be acquired within {@code timeout}
     */
    public <T> T withLocks(Collection<String> resourceKeys, Duration timeout, Callable<T> criticalSection) {
        List<String> sortedKeys = new ArrayList<>(new TreeSet<>(resourceKeys));
        List<ReentrantLock> acquired = new ArrayList<>(sortedKeys.size());
        try {
            for (String key : sortedKeys) {
                ReentrantLock lock = lockFor(key);
                boolean acquiredLock;
                try {
                    acquiredLock = lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SchedulingTimeoutException(
                            "Interrupted while acquiring lock for resource '" + key + "'");
                }
                if (!acquiredLock) {
                    throw new SchedulingTimeoutException(
                            "Timed out acquiring lock for resource '" + key + "' after " + timeout);
                }
                acquired.add(lock);
            }
            try {
                return criticalSection.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Unexpected checked exception in critical section", e);
            }
        } finally {
            for (int i = acquired.size() - 1; i >= 0; i--) {
                acquired.get(i).unlock();
            }
        }
    }

    /** Number of distinct resource locks created so far (diagnostics/tests only). */
    public int trackedResourceCount() {
        return locksByResourceKey.size();
    }
}
