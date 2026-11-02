package lld.clouddrive.util;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates short, unique, monotonically-increasing-looking ids without pulling in a UUID
 * library dependency or exposing a predictable auto-increment integer (which would make ids
 * guessable/enumerable in a real API).
 *
 * <p>Thread-safe: backed by a single {@link AtomicLong} counter combined with a random suffix.
 * Not cryptographically secure by design -- these are resource ids, not secrets.
 */
public final class IdGenerator {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final String prefix;

    public IdGenerator(String prefix) {
        this.prefix = prefix;
    }

    public String next() {
        long seq = COUNTER.incrementAndGet();
        long rand = ThreadLocalRandom.current().nextLong(0, 0xFFFFF);
        return prefix + "-" + Long.toString(seq, 36) + Long.toString(rand, 36);
    }
}
