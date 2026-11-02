package lld.meetingscheduler.service;

import lld.meetingscheduler.exception.InvalidMeetingRequestException;
import lld.meetingscheduler.exception.MeetingConflictException;
import lld.meetingscheduler.exception.ResourceNotFoundException;
import lld.meetingscheduler.interval.IntervalCalendar;
import lld.meetingscheduler.interval.TreeMapIntervalCalendar;
import lld.meetingscheduler.model.BookingRequest;
import lld.meetingscheduler.model.BookingResult;
import lld.meetingscheduler.model.Meeting;
import lld.meetingscheduler.model.MeetingId;
import lld.meetingscheduler.model.MeetingOccurrence;
import lld.meetingscheduler.model.RecurrenceRule;
import lld.meetingscheduler.model.Room;
import lld.meetingscheduler.model.RoomId;
import lld.meetingscheduler.model.TimeInterval;
import lld.meetingscheduler.model.User;
import lld.meetingscheduler.model.UserId;
import lld.meetingscheduler.enums.CancellationScope;
import lld.meetingscheduler.repository.MeetingRepository;
import lld.meetingscheduler.repository.RoomRepository;
import lld.meetingscheduler.repository.UserRepository;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Facade (Facade pattern) coordinating repositories, per-resource calendars, recurrence
 * expansion, locking, and notification into the single public API a client needs:
 * book / cancel / find-common-free-slots. Kept intentionally thin -- it contains no conflict
 * -detection or expansion logic itself (that lives in {@link IntervalCalendar},
 * {@link RecurrenceExpansionService}, {@link FreeSlotFinder}) so this class does not become a
 * god class; its only job is orchestration and holding the right locks at the right time.
 */
public final class SchedulerService {

    private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(5);

    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final MeetingRepository meetingRepository;
    private final RecurrenceExpansionService recurrenceExpansionService;
    private final ResourceLockCoordinator lockCoordinator;
    private final List<BookingEventListener> listeners = new CopyOnWriteArrayList<>();

    private final ConcurrentHashMap<RoomId, IntervalCalendar> roomCalendars = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UserId, IntervalCalendar> userCalendars = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BookingResult> idempotencyCache = new ConcurrentHashMap<>();

    private final Duration lockTimeout;

    public SchedulerService(
            UserRepository userRepository, RoomRepository roomRepository, MeetingRepository meetingRepository) {
        this(userRepository, roomRepository, meetingRepository, DEFAULT_LOCK_TIMEOUT);
    }

    public SchedulerService(
            UserRepository userRepository,
            RoomRepository roomRepository,
            MeetingRepository meetingRepository,
            Duration lockTimeout) {
        this.userRepository = userRepository;
        this.roomRepository = roomRepository;
        this.meetingRepository = meetingRepository;
        this.recurrenceExpansionService = new RecurrenceExpansionService();
        this.lockCoordinator = new ResourceLockCoordinator();
        this.lockTimeout = lockTimeout;
    }

    public void addListener(BookingEventListener listener) {
        listeners.add(listener);
    }

    private static String roomKey(RoomId id) {
        return "ROOM:" + id.value();
    }

    private static String userKey(UserId id) {
        return "USER:" + id.value();
    }

    private IntervalCalendar calendarForRoom(RoomId id) {
        return roomCalendars.computeIfAbsent(id, k -> new TreeMapIntervalCalendar(roomKey(id)));
    }

    private IntervalCalendar calendarForUser(UserId id) {
        return userCalendars.computeIfAbsent(id, k -> new TreeMapIntervalCalendar(userKey(id)));
    }

    /**
     * Books a (possibly recurring) meeting. Validates the request, expands recurrence (if any)
     * into concrete occurrences, then atomically (see {@link ResourceLockCoordinator})
     * verifies every occurrence is conflict-free on every involved resource (room + all
     * attendees) before committing any of them -- an all-or-nothing guarantee: a recurring
     * meeting either gets every occurrence booked, or none of them, rather than silently
     * dropping the occurrences that happened to conflict.
     *
     * @throws InvalidMeetingRequestException if any referenced user/room does not exist
     * @throws MeetingConflictException if any occurrence conflicts on any involved resource
     * @throws lld.meetingscheduler.exception.SchedulingTimeoutException if locks for every
     *         involved resource cannot be acquired within the configured timeout
     */
    public BookingResult bookMeeting(BookingRequest request) {
        Optional<BookingResult> cached = request.idempotencyKey().map(idempotencyCache::get);
        if (cached.isPresent()) {
            return cached.get();
        }

        User organizer = userRepository.findById(request.organizerId())
                .orElseThrow(() -> new ResourceNotFoundException("Unknown organizer: " + request.organizerId()));
        for (UserId attendeeId : request.attendeeIds()) {
            if (!userRepository.exists(attendeeId)) {
                throw new InvalidMeetingRequestException("Unknown attendee: " + attendeeId);
            }
        }
        Optional<Room> room = request.roomId().map(id -> roomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown room: " + id)));

        ZoneId anchorZone = organizer.timezone();
        List<TimeInterval> occurrences = request.recurrenceRule()
                .map(rule -> recurrenceExpansionService.expand(request.interval(), rule, anchorZone, Set.of()))
                .orElseGet(() -> List.of(request.interval()));
        if (occurrences.isEmpty()) {
            throw new InvalidMeetingRequestException("Recurrence rule produced zero occurrences");
        }

        List<String> resourceKeys = new ArrayList<>();
        request.roomId().ifPresent(id -> resourceKeys.add(roomKey(id)));
        request.attendeeIds().forEach(id -> resourceKeys.add(userKey(id)));

        return lockCoordinator.withLocks(resourceKeys, lockTimeout, () -> {
            // Phase 1: validate every occurrence against every involved resource. Nothing is
            // mutated here -- this is what gives us the all-or-nothing guarantee.
            for (TimeInterval occurrence : occurrences) {
                if (room.isPresent()) {
                    IntervalCalendar cal = calendarForRoom(room.get().id());
                    cal.findConflict(occurrence).ifPresent(existing -> {
                        throw new MeetingConflictException(roomKey(room.get().id()), occurrence, existing.interval());
                    });
                }
                for (UserId attendeeId : request.attendeeIds()) {
                    IntervalCalendar cal = calendarForUser(attendeeId);
                    cal.findConflict(occurrence).ifPresent(existing -> {
                        throw new MeetingConflictException(userKey(attendeeId), occurrence, existing.interval());
                    });
                }
            }

            // Phase 2: commit. All validations passed, so every book() call below is guaranteed
            // conflict-free and cannot throw.
            MeetingId meetingId = MeetingId.newId();
            Meeting meeting = new Meeting(
                    meetingId,
                    request.title(),
                    request.organizerId(),
                    request.attendeeIds(),
                    request.roomId(),
                    request.interval(),
                    request.recurrenceRule(),
                    anchorZone);
            meetingRepository.save(meeting);

            for (TimeInterval occurrence : occurrences) {
                MeetingOccurrence meetingOccurrence = new MeetingOccurrence(meetingId, occurrence);
                room.ifPresent(r -> calendarForRoom(r.id()).book(meetingOccurrence));
                for (UserId attendeeId : request.attendeeIds()) {
                    calendarForUser(attendeeId).book(meetingOccurrence);
                }
            }

            BookingResult result = new BookingResult(meetingId, occurrences);
            request.idempotencyKey().ifPresent(key -> idempotencyCache.put(key, result));
            listeners.forEach(l -> l.onMeetingBooked(meeting));
            return result;
        });
    }

    /**
     * Cancels a meeting (or part of its series) per {@code scope}. {@code occurrenceDate} is
     * required for {@link CancellationScope#SINGLE_OCCURRENCE} and
     * {@link CancellationScope#THIS_AND_FOLLOWING}, and ignored for {@link CancellationScope#ALL}.
     */
    public void cancelMeeting(MeetingId meetingId, CancellationScope scope, LocalDate occurrenceDate) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown meeting: " + meetingId));

        List<String> resourceKeys = new ArrayList<>();
        meeting.roomId().ifPresent(id -> resourceKeys.add(roomKey(id)));
        meeting.attendeeIds().forEach(id -> resourceKeys.add(userKey(id)));

        lockCoordinator.withLocks(resourceKeys, lockTimeout, () -> {
            List<TimeInterval> currentOccurrences = occurrencesOf(meeting);
            List<TimeInterval> toCancel = switch (scope) {
                case ALL -> currentOccurrences;
                case SINGLE_OCCURRENCE -> filterByDate(currentOccurrences, meeting.anchorZone(),
                        date -> date.equals(occurrenceDate));
                case THIS_AND_FOLLOWING -> filterByDate(currentOccurrences, meeting.anchorZone(),
                        date -> !date.isBefore(occurrenceDate));
            };

            if (toCancel.isEmpty()) {
                throw new InvalidMeetingRequestException(
                        "No matching occurrence(s) found for scope " + scope + " on date " + occurrenceDate);
            }

            for (TimeInterval occurrence : toCancel) {
                meeting.roomId().ifPresent(id -> calendarForRoom(id).cancel(occurrence.start()));
                meeting.attendeeIds().forEach(id -> calendarForUser(id).cancel(occurrence.start()));
                meeting.addExceptionDate(occurrence.start().atZone(meeting.anchorZone()).toLocalDate());
                listeners.forEach(l -> l.onMeetingCancelled(meeting, occurrence));
            }

            if (scope == CancellationScope.ALL) {
                meeting.markCancelled();
            }
            meetingRepository.save(meeting);
            return null;
        });
    }

    private List<TimeInterval> occurrencesOf(Meeting meeting) {
        if (meeting.recurrenceRule().isEmpty()) {
            return List.of(meeting.baseInterval());
        }
        RecurrenceRule rule = meeting.recurrenceRule().get();
        return recurrenceExpansionService.expand(
                meeting.baseInterval(), rule, meeting.anchorZone(), meeting.exceptionDates());
    }

    private static List<TimeInterval> filterByDate(
            List<TimeInterval> occurrences, ZoneId zone, java.util.function.Predicate<LocalDate> predicate) {
        List<TimeInterval> matches = new ArrayList<>();
        for (TimeInterval occurrence : occurrences) {
            if (predicate.test(occurrence.start().atZone(zone).toLocalDate())) {
                matches.add(occurrence);
            }
        }
        return matches;
    }

    /**
     * Finds every free slot of at least {@code minDuration} within {@code window} that is free
     * for every one of {@code attendeeIds} simultaneously (and, if {@code roomId} is present,
     * also free on that room). See {@link FreeSlotFinder} for the merge-intervals algorithm.
     */
    public List<TimeInterval> findCommonFreeSlots(
            Set<UserId> attendeeIds, Optional<RoomId> roomId, TimeInterval window, Duration minDuration) {
        List<TimeInterval> busy = new ArrayList<>();
        for (UserId attendeeId : attendeeIds) {
            for (MeetingOccurrence occurrence : calendarForUser(attendeeId).occurrencesOverlapping(window)) {
                busy.add(occurrence.interval());
            }
        }
        roomId.ifPresent(id -> {
            for (MeetingOccurrence occurrence : calendarForRoom(id).occurrencesOverlapping(window)) {
                busy.add(occurrence.interval());
            }
        });
        return Collections.unmodifiableList(FreeSlotFinder.findCommonFreeSlots(busy, window, minDuration));
    }
}
