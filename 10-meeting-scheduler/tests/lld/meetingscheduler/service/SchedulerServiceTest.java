package lld.meetingscheduler.service;

import lld.meetingscheduler.enums.CancellationScope;
import lld.meetingscheduler.exception.InvalidMeetingRequestException;
import lld.meetingscheduler.exception.MeetingConflictException;
import lld.meetingscheduler.exception.ResourceNotFoundException;
import lld.meetingscheduler.model.BookingRequest;
import lld.meetingscheduler.model.BookingResult;
import lld.meetingscheduler.model.RecurrenceRule;
import lld.meetingscheduler.model.Room;
import lld.meetingscheduler.model.RoomId;
import lld.meetingscheduler.model.TimeInterval;
import lld.meetingscheduler.model.User;
import lld.meetingscheduler.model.UserId;
import lld.meetingscheduler.repository.InMemoryMeetingRepository;
import lld.meetingscheduler.repository.InMemoryRoomRepository;
import lld.meetingscheduler.repository.InMemoryUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerServiceTest {

    private InMemoryUserRepository userRepo;
    private InMemoryRoomRepository roomRepo;
    private SchedulerService scheduler;
    private User alice;
    private User bob;
    private Room room;

    @BeforeEach
    void setUp() {
        userRepo = new InMemoryUserRepository();
        roomRepo = new InMemoryRoomRepository();
        scheduler = new SchedulerService(userRepo, roomRepo, new InMemoryMeetingRepository());
        alice = userRepo.save(new User(UserId.newId(), "Alice", "alice@test.com", ZoneOffset.UTC));
        bob = userRepo.save(new User(UserId.newId(), "Bob", "bob@test.com", ZoneOffset.UTC));
        room = roomRepo.save(new Room(RoomId.newId(), "Room 1", 4, "Floor 1", ZoneOffset.UTC));
    }

    private TimeInterval intervalAt(LocalDate date, int startHour, int endHour) {
        ZonedDateTime start = date.atTime(startHour, 0).atZone(ZoneOffset.UTC);
        ZonedDateTime end = date.atTime(endHour, 0).atZone(ZoneOffset.UTC);
        return new TimeInterval(start.toInstant(), end.toInstant());
    }

    @Test
    void bookingSucceedsAndReturnsSingleOccurrenceForOneOffMeeting() {
        BookingResult result = scheduler.bookMeeting(BookingRequest.builder()
                .title("1:1")
                .organizer(alice.id())
                .addAttendee(bob.id())
                .room(room.id())
                .interval(intervalAt(LocalDate.of(2025, 6, 2), 9, 10))
                .build());
        assertEquals(1, result.scheduledOccurrences().size());
    }

    @Test
    void conflictingRoomBookingIsRejected() {
        TimeInterval slot = intervalAt(LocalDate.of(2025, 6, 2), 9, 10);
        scheduler.bookMeeting(BookingRequest.builder()
                .title("First").organizer(alice.id()).room(room.id()).interval(slot).build());

        assertThrows(MeetingConflictException.class, () -> scheduler.bookMeeting(BookingRequest.builder()
                .title("Second").organizer(bob.id()).room(room.id()).interval(slot).build()));
    }

    @Test
    void conflictingAttendeeBookingIsRejectedEvenInDifferentRooms() {
        Room room2 = roomRepo.save(new Room(RoomId.newId(), "Room 2", 4, "Floor 2", ZoneOffset.UTC));
        TimeInterval slot = intervalAt(LocalDate.of(2025, 6, 2), 9, 10);
        scheduler.bookMeeting(BookingRequest.builder()
                .title("First").organizer(alice.id()).addAttendee(bob.id()).room(room.id()).interval(slot).build());

        // Bob is double-booked even though the room differs.
        assertThrows(MeetingConflictException.class, () -> scheduler.bookMeeting(BookingRequest.builder()
                .title("Second").organizer(bob.id()).room(room2.id()).interval(slot).build()));
    }

    @Test
    void backToBackBookingsOnSameRoomSucceed() {
        LocalDate date = LocalDate.of(2025, 6, 2);
        scheduler.bookMeeting(BookingRequest.builder()
                .title("First").organizer(alice.id()).room(room.id()).interval(intervalAt(date, 9, 10)).build());
        // Starts exactly when the first ends -- must succeed under half-open interval semantics.
        BookingResult second = scheduler.bookMeeting(BookingRequest.builder()
                .title("Second").organizer(bob.id()).room(room.id()).interval(intervalAt(date, 10, 11)).build());
        assertEquals(1, second.scheduledOccurrences().size());
    }

    @Test
    void recurringBookingIsAllOrNothingWhenOneOccurrenceConflicts() {
        LocalDate monday = LocalDate.of(2025, 6, 2);
        // Pre-book the room on the second Monday (2025-06-09) at the same time the recurring
        // series would want it.
        scheduler.bookMeeting(BookingRequest.builder()
                .title("Blocker").organizer(bob.id()).room(room.id())
                .interval(intervalAt(LocalDate.of(2025, 6, 9), 9, 10)).build());

        RecurrenceRule weeklyMonday = RecurrenceRule.weekly(1, Set.of(DayOfWeek.MONDAY), 3);
        assertThrows(MeetingConflictException.class, () -> scheduler.bookMeeting(BookingRequest.builder()
                .title("Weekly Sync").organizer(alice.id()).room(room.id())
                .interval(intervalAt(monday, 9, 10)).recurrence(weeklyMonday).build()));

        // The first Monday occurrence must NOT have been booked either -- all-or-nothing.
        assertTrue(scheduler.findCommonFreeSlots(
                        Set.of(alice.id()), java.util.Optional.of(room.id()),
                        intervalAt(monday, 8, 12), java.time.Duration.ofMinutes(30))
                .stream().anyMatch(slot -> slot.contains(intervalAt(monday, 9, 10).start())));
    }

    @Test
    void idempotencyKeyReturnsSameResultOnRetry() {
        TimeInterval slot = intervalAt(LocalDate.of(2025, 6, 2), 9, 10);
        BookingRequest request = BookingRequest.builder()
                .title("Idempotent Meeting").organizer(alice.id()).room(room.id())
                .interval(slot).idempotencyKey("key-123").build();

        BookingResult first = scheduler.bookMeeting(request);
        BookingResult retry = scheduler.bookMeeting(request);
        assertEquals(first.meetingId(), retry.meetingId());
    }

    @Test
    void unknownOrganizerIsRejected() {
        assertThrows(ResourceNotFoundException.class, () -> scheduler.bookMeeting(BookingRequest.builder()
                .title("X").organizer(UserId.newId()).room(room.id())
                .interval(intervalAt(LocalDate.of(2025, 6, 2), 9, 10)).build()));
    }

    @Test
    void unknownAttendeeIsRejected() {
        assertThrows(InvalidMeetingRequestException.class, () -> scheduler.bookMeeting(BookingRequest.builder()
                .title("X").organizer(alice.id()).addAttendee(UserId.newId())
                .interval(intervalAt(LocalDate.of(2025, 6, 2), 9, 10)).build()));
    }

    @Test
    void cancellingSingleOccurrenceFreesOnlyThatDate() {
        LocalDate monday = LocalDate.of(2025, 6, 2);
        RecurrenceRule rule = RecurrenceRule.weekly(1, Set.of(DayOfWeek.MONDAY), 3);
        BookingResult result = scheduler.bookMeeting(BookingRequest.builder()
                .title("Weekly").organizer(alice.id()).room(room.id())
                .interval(intervalAt(monday, 9, 10)).recurrence(rule).build());
        assertEquals(3, result.scheduledOccurrences().size());

        scheduler.cancelMeeting(result.meetingId(), CancellationScope.SINGLE_OCCURRENCE, monday);

        // The cancelled date's slot is now free again on the room...
        Room other = room;
        BookingResult replacement = scheduler.bookMeeting(BookingRequest.builder()
                .title("Replacement").organizer(bob.id()).room(other.id())
                .interval(intervalAt(monday, 9, 10)).build());
        assertEquals(1, replacement.scheduledOccurrences().size());

        // ...but the following week's occurrence is still booked and conflicts.
        assertThrows(MeetingConflictException.class, () -> scheduler.bookMeeting(BookingRequest.builder()
                .title("Conflict").organizer(bob.id()).room(other.id())
                .interval(intervalAt(monday.plusWeeks(1), 9, 10)).build()));
    }

    @Test
    void cancellingAllScopeFreesEveryOccurrence() {
        LocalDate monday = LocalDate.of(2025, 6, 2);
        RecurrenceRule rule = RecurrenceRule.weekly(1, Set.of(DayOfWeek.MONDAY), 3);
        BookingResult result = scheduler.bookMeeting(BookingRequest.builder()
                .title("Weekly").organizer(alice.id()).room(room.id())
                .interval(intervalAt(monday, 9, 10)).recurrence(rule).build());

        scheduler.cancelMeeting(result.meetingId(), CancellationScope.ALL, monday);

        for (int week = 0; week < 3; week++) {
            LocalDate date = monday.plusWeeks(week);
            BookingResult rebooked = scheduler.bookMeeting(BookingRequest.builder()
                    .title("Rebook-" + week).organizer(bob.id()).room(room.id())
                    .interval(intervalAt(date, 9, 10)).build());
            assertEquals(1, rebooked.scheduledOccurrences().size());
        }
    }

    @Test
    void cancellingThisAndFollowingKeepsEarlierOccurrencesBooked() {
        LocalDate monday = LocalDate.of(2025, 6, 2);
        RecurrenceRule rule = RecurrenceRule.weekly(1, Set.of(DayOfWeek.MONDAY), 3);
        BookingResult result = scheduler.bookMeeting(BookingRequest.builder()
                .title("Weekly").organizer(alice.id()).room(room.id())
                .interval(intervalAt(monday, 9, 10)).recurrence(rule).build());

        LocalDate secondWeek = monday.plusWeeks(1);
        scheduler.cancelMeeting(result.meetingId(), CancellationScope.THIS_AND_FOLLOWING, secondWeek);

        // Week 1 (the very first occurrence) must still be booked -> conflicts on rebooking.
        assertThrows(MeetingConflictException.class, () -> scheduler.bookMeeting(BookingRequest.builder()
                .title("Conflict").organizer(bob.id()).room(room.id())
                .interval(intervalAt(monday, 9, 10)).build()));

        // Weeks 2 and 3 must now be free.
        BookingResult rebooked = scheduler.bookMeeting(BookingRequest.builder()
                .title("Rebook").organizer(bob.id()).room(room.id())
                .interval(intervalAt(secondWeek, 9, 10)).build());
        assertEquals(1, rebooked.scheduledOccurrences().size());
    }

    @Test
    void findCommonFreeSlotsExcludesEveryAttendeesBusyTime() {
        LocalDate date = LocalDate.of(2025, 6, 2);
        scheduler.bookMeeting(BookingRequest.builder()
                .title("Alice busy").organizer(alice.id()).interval(intervalAt(date, 9, 10)).build());
        scheduler.bookMeeting(BookingRequest.builder()
                .title("Bob busy").organizer(bob.id()).interval(intervalAt(date, 11, 12)).build());

        List<TimeInterval> free = scheduler.findCommonFreeSlots(
                Set.of(alice.id(), bob.id()), java.util.Optional.empty(),
                intervalAt(date, 8, 13), java.time.Duration.ofMinutes(30));

        assertEquals(List.of(
                intervalAt(date, 8, 9),
                intervalAt(date, 10, 11),
                intervalAt(date, 12, 13)
        ), free);
    }
}
