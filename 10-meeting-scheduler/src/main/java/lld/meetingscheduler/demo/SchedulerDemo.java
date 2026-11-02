package lld.meetingscheduler.demo;

import lld.meetingscheduler.enums.CancellationScope;
import lld.meetingscheduler.exception.MeetingConflictException;
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
import lld.meetingscheduler.service.ConsoleBookingEventListener;
import lld.meetingscheduler.service.SchedulerService;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runnable demonstration of the Meeting Scheduler: single booking, conflict detection,
 * recurring weekly booking, common-free-slot search across attendees, occurrence
 * cancellation, and a concurrent double-booking race to show exactly one of N racing
 * requests for the same slot wins.
 */
public final class SchedulerDemo {

    public static void main(String[] args) throws Exception {
        var userRepo = new InMemoryUserRepository();
        var roomRepo = new InMemoryRoomRepository();
        var meetingRepo = new InMemoryMeetingRepository();
        SchedulerService scheduler = new SchedulerService(userRepo, roomRepo, meetingRepo);
        scheduler.addListener(new ConsoleBookingEventListener());

        ZoneId nycZone = ZoneId.of("America/New_York");
        ZoneId londonZone = ZoneId.of("Europe/London");

        User alice = userRepo.save(new User(UserId.newId(), "Alice", "alice@example.com", nycZone));
        User bob = userRepo.save(new User(UserId.newId(), "Bob", "bob@example.com", londonZone));
        User carol = userRepo.save(new User(UserId.newId(), "Carol", "carol@example.com", nycZone));
        Room boardroom = roomRepo.save(new Room(RoomId.newId(), "Boardroom A", 8, "Floor 3", nycZone));

        System.out.println("=== 1. Book a single one-off meeting ===");
        ZonedDateTime start = ZonedDateTime.of(2025, 6, 2, 9, 0, 0, 0, nycZone);
        TimeInterval standupInterval = new TimeInterval(start.toInstant(), start.plusMinutes(30).toInstant());
        BookingResult standup = scheduler.bookMeeting(BookingRequest.builder()
                .title("Daily Standup")
                .organizer(alice.id())
                .addAttendee(bob.id())
                .addAttendee(carol.id())
                .room(boardroom.id())
                .interval(standupInterval)
                .build());
        System.out.println("Booked: " + standup.meetingId() + " occurrences=" + standup.scheduledOccurrences());

        System.out.println();
        System.out.println("=== 2. Attempt a conflicting booking on the same room+time -> rejected ===");
        try {
            scheduler.bookMeeting(BookingRequest.builder()
                    .title("Conflicting Sync")
                    .organizer(bob.id())
                    .addAttendee(alice.id())
                    .room(boardroom.id())
                    .interval(standupInterval)
                    .build());
        } catch (MeetingConflictException e) {
            System.out.println("Rejected as expected: " + e.getMessage());
        }

        System.out.println();
        System.out.println("=== 3. Book a recurring weekly meeting (Mon & Wed, 4 occurrences) ===");
        ZonedDateTime weeklyStart = ZonedDateTime.of(2025, 6, 2, 14, 0, 0, 0, nycZone); // Mon
        TimeInterval weeklyInterval = new TimeInterval(weeklyStart.toInstant(), weeklyStart.plusHours(1).toInstant());
        RecurrenceRule weeklyRule = RecurrenceRule.weekly(1, Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), 4);
        BookingResult weeklySync = scheduler.bookMeeting(BookingRequest.builder()
                .title("Design Review")
                .organizer(alice.id())
                .addAttendee(carol.id())
                .interval(weeklyInterval)
                .recurrence(weeklyRule)
                .build());
        System.out.println("Booked recurring series " + weeklySync.meetingId() + " with "
                + weeklySync.scheduledOccurrences().size() + " occurrences:");
        weeklySync.scheduledOccurrences().forEach(occ -> System.out.println("  " + occ));

        System.out.println();
        System.out.println("=== 4. Find common free 30-minute slots for Alice & Carol on 2025-06-02 ===");
        ZonedDateTime windowStart = ZonedDateTime.of(2025, 6, 2, 8, 0, 0, 0, nycZone);
        ZonedDateTime windowEnd = ZonedDateTime.of(2025, 6, 2, 18, 0, 0, 0, nycZone);
        TimeInterval window = new TimeInterval(windowStart.toInstant(), windowEnd.toInstant());
        List<TimeInterval> freeSlots = scheduler.findCommonFreeSlots(
                Set.of(alice.id(), carol.id()), Optional.empty(), window, Duration.ofMinutes(30));
        System.out.println("Free slots:");
        freeSlots.forEach(slot -> System.out.println("  " + slot));

        System.out.println();
        System.out.println("=== 5. Cancel a single occurrence of the recurring series ===");
        LocalDate secondOccurrenceDate = weeklySync.scheduledOccurrences().get(1).start().atZone(nycZone).toLocalDate();
        scheduler.cancelMeeting(weeklySync.meetingId(), CancellationScope.SINGLE_OCCURRENCE, secondOccurrenceDate);
        System.out.println("Cancelled occurrence on " + secondOccurrenceDate);

        System.out.println();
        System.out.println("=== 6. Concurrent double-booking race: 10 threads, same slot, only 1 wins ===");
        Room raceRoom = roomRepo.save(new Room(RoomId.newId(), "Race Room", 4, "Floor 1", nycZone));
        User organizer = userRepo.save(new User(UserId.newId(), "Dave", "dave@example.com", nycZone));
        ZonedDateTime raceStart = ZonedDateTime.of(2025, 6, 10, 10, 0, 0, 0, nycZone);
        TimeInterval raceInterval = new TimeInterval(raceStart.toInstant(), raceStart.plusMinutes(30).toInstant());

        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    scheduler.bookMeeting(BookingRequest.builder()
                            .title("Race Booking #" + idx)
                            .organizer(organizer.id())
                            .room(raceRoom.id())
                            .interval(raceInterval)
                            .build());
                    succeeded.incrementAndGet();
                } catch (MeetingConflictException e) {
                    conflicted.incrementAndGet();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        System.out.println("Succeeded=" + succeeded.get() + " conflicted=" + conflicted.get()
                + " (expected succeeded=1, conflicted=" + (threadCount - 1) + ")");
    }
}
