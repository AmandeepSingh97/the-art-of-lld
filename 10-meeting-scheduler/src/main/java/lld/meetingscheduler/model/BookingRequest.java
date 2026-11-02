package lld.meetingscheduler.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Input to {@link lld.meetingscheduler.service.SchedulerService#bookMeeting(BookingRequest)}.
 *
 * <p>Uses the Builder pattern (see {@link Builder}) rather than a telescoping constructor
 * because most fields are optional (room, recurrence rule, idempotency key) and the
 * all-args-constructor alternative becomes unreadable at the call site ("was that the 4th or
 * 5th boolean/Optional parameter?"). This is a textbook, non-forced use of Builder: there are
 * genuinely 3+ optional parameters and construction-time validation to centralize.
 */
public final class BookingRequest {

    private final String title;
    private final UserId organizerId;
    private final Set<UserId> attendeeIds;
    private final Optional<RoomId> roomId;
    private final TimeInterval interval;
    private final Optional<RecurrenceRule> recurrenceRule;
    private final Optional<String> idempotencyKey;

    private BookingRequest(Builder b) {
        this.title = Objects.requireNonNull(b.title, "title must be set");
        this.organizerId = Objects.requireNonNull(b.organizerId, "organizerId must be set");
        this.interval = Objects.requireNonNull(b.interval, "interval must be set");
        this.roomId = Optional.ofNullable(b.roomId);
        this.recurrenceRule = Optional.ofNullable(b.recurrenceRule);
        this.idempotencyKey = Optional.ofNullable(b.idempotencyKey);

        Set<UserId> attendees = new LinkedHashSet<>(b.attendeeIds);
        attendees.add(organizerId); // organizer is always an implicit attendee
        this.attendeeIds = Collections.unmodifiableSet(attendees);

        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
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

    public TimeInterval interval() {
        return interval;
    }

    public Optional<RecurrenceRule> recurrenceRule() {
        return recurrenceRule;
    }

    public Optional<String> idempotencyKey() {
        return idempotencyKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String title;
        private UserId organizerId;
        private final Set<UserId> attendeeIds = new HashSet<>();
        private RoomId roomId;
        private TimeInterval interval;
        private RecurrenceRule recurrenceRule;
        private String idempotencyKey;

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder organizer(UserId organizerId) {
            this.organizerId = organizerId;
            return this;
        }

        public Builder attendees(Set<UserId> attendeeIds) {
            this.attendeeIds.addAll(attendeeIds);
            return this;
        }

        public Builder addAttendee(UserId attendeeId) {
            this.attendeeIds.add(attendeeId);
            return this;
        }

        public Builder room(RoomId roomId) {
            this.roomId = roomId;
            return this;
        }

        public Builder interval(TimeInterval interval) {
            this.interval = interval;
            return this;
        }

        public Builder recurrence(RecurrenceRule recurrenceRule) {
            this.recurrenceRule = recurrenceRule;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public BookingRequest build() {
            return new BookingRequest(this);
        }
    }
}
