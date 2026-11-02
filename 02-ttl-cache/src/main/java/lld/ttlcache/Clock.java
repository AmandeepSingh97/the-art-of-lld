package lld.ttlcache;

/**
 * Abstraction over wall-clock time so that TTL expiry logic can be deterministically
 * unit-tested without relying on {@link Thread#sleep(long)}.
 *
 * <p>Production code uses {@link #systemClock()}, which simply delegates to
 * {@link System#currentTimeMillis()}. Tests supply a controllable fake implementation
 * that can be advanced manually, which is what allows {@code TtlCacheBasicTest} to
 * assert expiry behaviour without introducing flakiness from real-time sleeps.
 */
@FunctionalInterface
public interface Clock {

    /**
     * @return the current time in epoch milliseconds, per this clock's notion of "now".
     */
    long currentTimeMillis();

    /**
     * @return a {@link Clock} backed by the JVM's system clock.
     */
    static Clock systemClock() {
        return System::currentTimeMillis;
    }
}
