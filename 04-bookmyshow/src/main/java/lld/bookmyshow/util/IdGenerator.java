package lld.bookmyshow.util;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized id generation. Uses {@link UUID} for globally unique ids
 * (bookings, locks, payments — things that must never collide even across
 * process restarts or multiple instances) and a monotonic counter only
 * where a readable sequential id is useful for demo output.
 */
public final class IdGenerator {

    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    private IdGenerator() {
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static String sequential(String prefix) {
        return prefix + "-" + SEQUENCE.incrementAndGet();
    }
}
