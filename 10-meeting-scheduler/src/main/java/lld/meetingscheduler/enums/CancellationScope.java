package lld.meetingscheduler.enums;

/**
 * Scope of a cancellation request against a (possibly recurring) meeting series.
 * Mirrors the "this event / this and following / all events" choice every real
 * calendar product (Google Calendar, Outlook) surfaces to users, because recurring
 * meetings are edited/cancelled far more often at the occurrence level than as a
 * whole series.
 */
public enum CancellationScope {
    /** Cancel a single occurrence (identified by its occurrence date); the series continues. */
    SINGLE_OCCURRENCE,
    /** Cancel this occurrence and every later occurrence; earlier occurrences are untouched. */
    THIS_AND_FOLLOWING,
    /** Cancel the entire series, including already-elapsed occurrences going forward. */
    ALL
}
