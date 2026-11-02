package lld.ttlcache;

/**
 * Deterministic, manually-advanced {@link Clock} for tests, avoiding real-time
 * {@code Thread.sleep}-based TTL assertions (which are slow and flaky under CI load).
 */
final class FakeClock implements Clock {

    private volatile long currentMillis;

    FakeClock(long initialMillis) {
        this.currentMillis = initialMillis;
    }

    @Override
    public long currentTimeMillis() {
        return currentMillis;
    }

    void advance(long millis) {
        currentMillis += millis;
    }
}
