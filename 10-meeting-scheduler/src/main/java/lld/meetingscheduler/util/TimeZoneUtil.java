package lld.meetingscheduler.util;

import lld.meetingscheduler.model.TimeInterval;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Presentation-edge timezone conversion helpers. All storage/comparison in this system is in
 * UTC ({@link Instant}); this class exists solely to translate a stored {@link TimeInterval}
 * back into a particular user's/room's local wall-clock representation when displaying it,
 * and to translate a user-supplied local time into the UTC {@link Instant} the system stores.
 *
 * <p>Keeping this conversion in exactly one place (rather than scattering {@code ZonedDateTime}
 * arithmetic through the booking/conflict-detection code) is what prevents DST-related bugs:
 * every comparison and sort in the core engine operates on {@link Instant}, which has no
 * notion of "offset" to get stale.
 */
public final class TimeZoneUtil {

    private TimeZoneUtil() {
    }

    /** Converts a UTC instant to a local date-time in the given zone (for display). */
    public static ZonedDateTime toLocal(Instant instant, ZoneId zone) {
        return ZonedDateTime.ofInstant(instant, zone);
    }

    /** Converts a local date-time (as entered by a user in their own zone) to a UTC instant. */
    public static Instant toInstant(ZonedDateTime localDateTime) {
        return localDateTime.toInstant();
    }

    /** Renders an interval in the given zone as a human-readable string, e.g. for logs/demo output. */
    public static String describe(TimeInterval interval, ZoneId zone) {
        return toLocal(interval.start(), zone) + " -> " + toLocal(interval.end(), zone);
    }
}
