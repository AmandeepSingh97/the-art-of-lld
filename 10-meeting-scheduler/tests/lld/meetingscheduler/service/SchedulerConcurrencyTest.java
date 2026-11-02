package lld.meetingscheduler.service;

import lld.meetingscheduler.exception.MeetingConflictException;
import lld.meetingscheduler.model.BookingRequest;
import lld.meetingscheduler.model.Room;
import lld.meetingscheduler.model.RoomId;
import lld.meetingscheduler.model.TimeInterval;
import lld.meetingscheduler.model.User;
import lld.meetingscheduler.model.UserId;
import lld.meetingscheduler.repository.InMemoryMeetingRepository;
import lld.meetingscheduler.repository.InMemoryRoomRepository;
import lld.meetingscheduler.repository.InMemoryUserRepository;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises {@link SchedulerService} under genuine concurrent contention with real threads
 * racing for the exact same room+slot, proving the double-booking-prevention claim rather than
 * merely asserting it. If {@link ResourceLockCoordinator}'s ordered-locking guarantee were
 * broken (e.g. by reverting to unsynchronized per-calendar access), this test reliably fails by
 * observing more than one winner.
 */
class SchedulerConcurrencyTest {

    @RepeatedTest(5)
    @Timeout(15)
    void exactlyOneRacingRequestWinsForTheSameRoomSlot() throws InterruptedException {
        InMemoryUserRepository userRepo = new InMemoryUserRepository();
        InMemoryRoomRepository roomRepo = new InMemoryRoomRepository();
        SchedulerService scheduler = new SchedulerService(userRepo, roomRepo, new InMemoryMeetingRepository());

        Room room = roomRepo.save(new Room(RoomId.newId(), "Contended Room", 4, "Floor 1", ZoneOffset.UTC));
        int threadCount = 16;
        List<User> organizers = IntStream.range(0, threadCount)
                .mapToObj(i -> userRepo.save(new User(UserId.newId(), "User" + i, "u" + i + "@test.com", ZoneOffset.UTC)))
                .collect(Collectors.toList());

        Instant start = Instant.parse("2025-06-02T09:00:00Z");
        TimeInterval contestedSlot = new TimeInterval(start, start.plusSeconds(1800));

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            User organizer = organizers.get(i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                await(go);
                try {
                    scheduler.bookMeeting(BookingRequest.builder()
                            .title("Racer-" + organizer.id())
                            .organizer(organizer.id())
                            .room(room.id())
                            .interval(contestedSlot)
                            .build());
                    succeeded.incrementAndGet();
                } catch (MeetingConflictException e) {
                    conflicted.incrementAndGet();
                }
            }));
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        assertTimelyTermination(pool);

        for (Future<?> f : futures) {
            drain(f);
        }

        assertEquals(1, succeeded.get(), "exactly one racing booking must win the contested slot");
        assertEquals(threadCount - 1, conflicted.get(), "every other racer must observe a conflict, not silently lose data");
    }

    @Test
    @Timeout(15)
    void concurrentBookingsOnDisjointSlotsAllSucceed() throws InterruptedException {
        InMemoryUserRepository userRepo = new InMemoryUserRepository();
        InMemoryRoomRepository roomRepo = new InMemoryRoomRepository();
        SchedulerService scheduler = new SchedulerService(userRepo, roomRepo, new InMemoryMeetingRepository());
        Room room = roomRepo.save(new Room(RoomId.newId(), "Room", 4, "Floor 1", ZoneOffset.UTC));

        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            User organizer = userRepo.save(new User(UserId.newId(), "U" + idx, "u" + idx + "@test.com", ZoneOffset.UTC));
            futures.add(pool.submit(() -> {
                ready.countDown();
                await(go);
                // Each thread books a distinct, non-overlapping slot -> no contention, all succeed.
                Instant start = Instant.parse("2025-06-02T09:00:00Z").plusSeconds(idx * 1800L);
                TimeInterval slot = new TimeInterval(start, start.plusSeconds(1800));
                scheduler.bookMeeting(BookingRequest.builder()
                        .title("Slot-" + idx).organizer(organizer.id()).room(room.id()).interval(slot).build());
                succeeded.incrementAndGet();
            }));
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        assertTimelyTermination(pool);
        for (Future<?> f : futures) {
            drain(f);
        }

        assertEquals(threadCount, succeeded.get(), "non-overlapping bookings must not falsely conflict with each other");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void assertTimelyTermination(ExecutorService pool) throws InterruptedException {
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Thread pool did not terminate in time");
        }
    }

    private static void drain(Future<?> future) {
        try {
            future.get();
        } catch (Exception e) {
            // MeetingConflictException is handled inside the task itself; any other exception
            // surfacing here is a real test failure.
            throw new RuntimeException(e);
        }
    }
}
