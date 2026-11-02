package lld.contactmanagement.exception;

import java.util.UUID;

/**
 * Thrown when an optimistic-concurrency (compare-and-set) update loop in
 * {@code ContactService} exhausts its retry budget because of sustained
 * contention on the same contact. Distinct from
 * {@link java.util.ConcurrentModificationException}, which in the JDK is
 * conventionally reserved for fail-fast iterator invalidation, not
 * application-level optimistic-lock contention.
 */
public class OptimisticLockException extends RuntimeException {

    public OptimisticLockException(UUID contactId, int attempts) {
        super("Failed to update contact " + contactId + " after " + attempts
                + " optimistic-concurrency retries; contention is too high");
    }
}
