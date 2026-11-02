package lld.clouddrive.quota;

import lld.clouddrive.exception.QuotaExceededException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks and enforces per-user storage quota.
 *
 * <p><b>The concurrency problem this solves:</b> two uploads from the SAME user, submitted on
 * two different threads (e.g. two browser tabs, or a sync client uploading several files in
 * parallel), must never both be admitted if only one of them actually fits in the remaining
 * quota. A naive "read used, check against limit, then write used + size" is a classic
 * check-then-act race: both threads can read the same {@code used} value, both see enough
 * headroom, and both proceed, overshooting the quota.
 *
 * <p><b>Why a CAS retry loop and not a per-user lock:</b> both are correct; a
 * {@code synchronized}/{@link java.util.concurrent.locks.ReentrantLock} per user would also
 * work and would arguably be easier to read. This implementation uses a lock-free
 * compare-and-swap loop on an {@link AtomicLong} per user instead because quota checks sit on
 * the hot path of every single upload and a lock-free approach avoids any thread ever blocking
 * on another thread's I/O-adjacent work (in this design the quota check happens before the
 * (potentially slow) blob write, so no thread holds anything while doing real work -- but if a
 * future change moved the check to wrap the actual byte-copy, a lock would risk holding the lock
 * across that I/O; CAS has no such risk because there is nothing to hold). The lock-based
 * alternative and this trade-off are discussed in README section 11.
 */
public final class QuotaManager {

    private final ConcurrentHashMap<String, AtomicLong> usedBytesByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> quotaBytesByUser = new ConcurrentHashMap<>();
    private final long defaultQuotaBytes;

    public QuotaManager(long defaultQuotaBytes) {
        this.defaultQuotaBytes = defaultQuotaBytes;
    }

    public void registerUser(String userId, long quotaBytes) {
        quotaBytesByUser.put(userId, quotaBytes);
        usedBytesByUser.putIfAbsent(userId, new AtomicLong(0));
    }

    private long quotaFor(String userId) {
        return quotaBytesByUser.getOrDefault(userId, defaultQuotaBytes);
    }

    private AtomicLong usedCounterFor(String userId) {
        return usedBytesByUser.computeIfAbsent(userId, u -> new AtomicLong(0));
    }

    /**
     * Atomically reserves {@code bytes} of quota for {@code userId}, or throws if doing so would
     * exceed their limit. Correct under arbitrary concurrent callers for the same user: the
     * check ("would this overshoot?") and the act ("reserve it") happen as a single atomic
     * compare-and-swap, retried only on contention, never on a stale read being acted upon.
     *
     * @throws QuotaExceededException if {@code bytes} would not fit in the user's remaining quota.
     */
    public void reserve(String userId, long bytes) {
        long quota = quotaFor(userId);
        AtomicLong used = usedCounterFor(userId);
        while (true) {
            long current = used.get();
            long updated = current + bytes;
            if (updated > quota) {
                throw new QuotaExceededException(userId, bytes, Math.max(0, quota - current));
            }
            if (used.compareAndSet(current, updated)) {
                return; // Won the race: our reservation is now durably recorded.
            }
            // Someone else updated `used` concurrently between our read and our CAS attempt;
            // loop and re-evaluate against the fresh value rather than retrying blindly.
        }
    }

    /** Frees previously reserved quota, e.g. when a file/version is permanently purged. */
    public void release(String userId, long bytes) {
        AtomicLong used = usedCounterFor(userId);
        used.updateAndGet(current -> Math.max(0, current - bytes));
    }

    public long getUsedBytes(String userId) {
        return usedCounterFor(userId).get();
    }

    public long getQuotaBytes(String userId) {
        return quotaFor(userId);
    }

    public long getRemainingBytes(String userId) {
        return Math.max(0, quotaFor(userId) - getUsedBytes(userId));
    }
}
