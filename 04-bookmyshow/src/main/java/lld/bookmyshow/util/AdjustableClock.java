package lld.bookmyshow.util;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A manually-advanceable {@link Clock}, used so tests can deterministically
 * exercise TTL-expiry logic (seat lock expiry, booking expiry) without
 * {@code Thread.sleep}-based flakiness. Production code is wired with
 * {@link Clock#systemUTC()}; tests are wired with this class and call
 * {@link #advance} to simulate time passing.
 */
public final class AdjustableClock extends Clock {

    private final AtomicLong epochMillis;
    private final ZoneId zone;

    public AdjustableClock(Instant start, ZoneId zone) {
        this.epochMillis = new AtomicLong(start.toEpochMilli());
        this.zone = zone;
    }

    public static AdjustableClock startingAt(Instant start) {
        return new AdjustableClock(start, ZoneId.of("UTC"));
    }

    public void advance(java.time.Duration duration) {
        epochMillis.addAndGet(duration.toMillis());
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new AdjustableClock(Instant.ofEpochMilli(epochMillis.get()), zone);
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(epochMillis.get());
    }

    @Override
    public long millis() {
        return epochMillis.get();
    }
}
