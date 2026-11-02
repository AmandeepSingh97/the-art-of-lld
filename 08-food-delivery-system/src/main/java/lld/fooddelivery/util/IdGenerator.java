package lld.fooddelivery.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe monotonically increasing id generator for demo/in-memory use. Each generator
 * instance is scoped to one "entity kind" (orders, payments, ...) by its {@code prefix}, and
 * uses {@link AtomicLong#incrementAndGet()} so concurrent callers never receive duplicate ids
 * without needing an external lock. A production system would instead rely on a database
 * sequence/UUID/Snowflake-style distributed id — see README "Persistence and Database Design".
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
