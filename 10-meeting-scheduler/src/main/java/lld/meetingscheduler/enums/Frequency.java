package lld.meetingscheduler.enums;

/**
 * Supported recurrence frequencies, deliberately scoped to the two patterns the
 * interview problem statement calls out explicitly (daily / weekly). Adding a
 * new frequency (e.g. MONTHLY) means adding an enum constant and a new
 * {@link lld.meetingscheduler.strategy.RecurrenceExpansionStrategy} implementation —
 * existing code is untouched (Open/Closed Principle).
 */
public enum Frequency {
    DAILY,
    WEEKLY
}
