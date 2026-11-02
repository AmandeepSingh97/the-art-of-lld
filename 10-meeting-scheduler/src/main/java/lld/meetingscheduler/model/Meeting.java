package lld.meetingscheduler.model;

import lld.meetingscheduler.enums.MeetingStatus;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A meeting series (the aggregate root of this domain). For a one-off meeting,
 * {@code recurrenceRule} is empty and the series has exactly one occurrence
 * ({@code baseInterval}). For a recurring meeting, {@code baseInterval} anchors the first
 * occurrence (its date and time-of-day/duration are reused for every subsequent occurrence)
 * and {@code recurrenceRule} describes how later occurrences are generated -- see
 * {@link lld.meetingscheduler.service.RecurrenceExpansionService}.
 *
 * <p>{@code exceptionDates} records occurrence dates that were individually cancelled out of
 * an otherwise-active series (see {@link lld.meetingscheduler.enums.CancellationScope#SINGLE_OCCURRENCE}),
 * mirroring how Google Calendar/Outlook represent "delete this one instance" without
 * destroying the whole series.
 *
 * <p>Mutable fields ({@code status}, {@code exceptionDates}) are intentionally the only
 * mutable state on this class; all mutation is routed through
 * {@link lld.meetingscheduler.service.SchedulerService}, which is responsible for holding
 * the relevant locks before mutating (see the concurrency discussion in the README).
 * {@code exceptionDates} is a {@link CopyOnWriteArraySet} as defense-in-depth so that a
 * concurrent *read* of the exception set (e.g. for rendering) is never torn, even though the
 * primary atomicity guarantee comes from {@code ResourceLockCoordinator}.
 */
public final class Meeting {

    private final MeetingId id;
    private final String title;
    private final UserId organizerId;
    private final Set<UserId> attendeeIds;
    private final Optional<RoomId> roomId;
    private final TimeInterval baseInterval;
    private final Optional<RecurrenceRule> recurrenceRule;
    private final ZoneId anchorZone;
    private final Set<LocalDate> exceptionDates;
    private volatile MeetingStatus status;

    public Meeting(
            MeetingId id,
            String title,
            UserId organizerId,
            Set<UserId> attendeeIds,
            Optional<RoomId> roomId,
            TimeInterval baseInterval,
            Optional<RecurrenceRule> recurrenceRule,
            ZoneId anchorZone) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.organizerId = Objects.requireNonNull(organizerId, "organizerId must not be null");
        this.attendeeIds = Collections.unmodifiableSet(new HashSet<>(
                Objects.requireNonNull(attendeeIds, "attendeeIds must not be null")));
        this.roomId = Objects.requireNonNull(roomId, "roomId must not be null");
        this.baseInterval = Objects.requireNonNull(baseInterval, "baseInterval must not be null");
        this.recurrenceRule = Objects.requireNonNull(recurrenceRule, "recurrenceRule must not be null");
        this.anchorZone = Objects.requireNonNull(anchorZone, "anchorZone must not be null");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (attendeeIds.isEmpty()) {
            throw new IllegalArgumentException("a meeting must have at least one attendee");
        }
        this.exceptionDates = new CopyOnWriteArraySet<>();
        this.status = MeetingStatus.SCHEDULED;
    }

    public MeetingId id() {
        return id;
    }

    public String title() {
        return title;
    }

    public UserId organizerId() {
        return organizerId;
    }

    public Set<UserId> attendeeIds() {
        return attendeeIds;
    }

    public Optional<RoomId> roomId() {
        return roomId;
    }

    public TimeInterval baseInterval() {
        return baseInterval;
    }

    public Optional<RecurrenceRule> recurrenceRule() {
        return recurrenceRule;
    }

    /**
     * The timezone this series' recurrence is anchored in, captured at creation time (from the
     * organizer's timezone at booking time). Recomputing occurrences later (e.g. on
     * cancellation) always reuses this stored zone rather than re-reading the organizer's
     * *current* timezone, so a later change to the organizer's profile timezone cannot silently
     * shift when a recurring series' future occurrences are considered to fall.
     */
    public ZoneId anchorZone() {
        return anchorZone;
    }

    public boolean isRecurring() {
        return recurrenceRule.isPresent();
    }

    public Set<LocalDate> exceptionDates() {
        return Collections.unmodifiableSet(exceptionDates);
    }

    public void addExceptionDate(LocalDate date) {
        exceptionDates.add(Objects.requireNonNull(date, "date must not be null"));
    }

    public MeetingStatus status() {
        return status;
    }

    public void markCancelled() {
        this.status = MeetingStatus.CANCELLED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Meeting meeting)) return false;
        return id.equals(meeting.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Meeting{id=" + id + ", title='" + title + "', baseInterval=" + baseInterval
                + ", recurring=" + isRecurring() + ", status=" + status + "}";
    }
}
