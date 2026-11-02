package lld.parkinglot.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, monotonically increasing id generator used for tickets and
 * payments. Backed by {@link AtomicLong} rather than a {@code synchronized}
 * counter: increments are lock-free (CAS loop internally) and this is a
 * hot path hit by every entry/exit gate concurrently.
 *
 * <p>In a real deployment this would be replaced by a distributed id scheme
 * (e.g. a database sequence, Snowflake-style id, or UUID) once more than one
 * process can mint tickets; see {@code STAFF_LEVEL_DEEP_DIVE.md}.
 */
public final class IdGenerator {

    private final String prefix;
    private final AtomicLong counter = new AtomicLong(0);

    public IdGenerator(String prefix) {
        this.prefix = prefix;
    }

    public String next() {
        return prefix + "-" + counter.incrementAndGet();
    }
}
