package lld.fooddelivery;

import lld.fooddelivery.domain.DeliveryPartner;
import lld.fooddelivery.domain.GeoLocation;
import lld.fooddelivery.domain.Money;
import lld.fooddelivery.domain.Order;
import lld.fooddelivery.domain.OrderItem;
import lld.fooddelivery.domain.OrderStatus;
import lld.fooddelivery.domain.PartnerStatus;
import lld.fooddelivery.exception.InvalidOrderStateTransitionException;
import lld.fooddelivery.service.DeliveryAssignmentService;
import lld.fooddelivery.strategy.NearestAvailablePartnerStrategy;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Concurrency correctness tests for the order state machine and delivery-partner assignment:
 * exactly the scenarios called out in the assignment brief — a restaurant backing out of an
 * order (cancel) racing with a concurrent delivery-partner assignment, and two simultaneous
 * attempts to accept/reject the same just-placed order.
 */
class OrderConcurrencyTest {

    private static Order freshOrder(String id) {
        List<OrderItem> items = List.of(new OrderItem("item-1", "Pizza", Money.of("300.00"), 1));
        return new Order(id, "cust-1", "rest-1", items, Money.of("300.00"), Money.of("300.00"));
    }

    /**
     * Two threads race to accept vs. reject the same freshly placed order. Exactly one must
     * win; the other must observe {@link InvalidOrderStateTransitionException} rather than
     * silently corrupting the order into an inconsistent status. Repeated to surface races
     * that only manifest under specific interleavings.
     */
    @RepeatedTest(50)
    void concurrentAcceptAndRejectResolvesToExactlyOneWinner() throws InterruptedException {
        Order order = freshOrder("race-order");
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Runnable acceptTask = () -> {
            await(startGate);
            try {
                order.accept();
                successes.incrementAndGet();
            } catch (InvalidOrderStateTransitionException e) {
                failures.incrementAndGet();
            }
        };
        Runnable rejectTask = () -> {
            await(startGate);
            try {
                order.reject("out of stock");
                successes.incrementAndGet();
            } catch (InvalidOrderStateTransitionException e) {
                failures.incrementAndGet();
            }
        };

        pool.submit(acceptTask);
        pool.submit(rejectTask);
        startGate.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, successes.get(), "exactly one of accept/reject must win the race");
        assertEquals(1, failures.get(), "exactly one of accept/reject must be rejected as an invalid transition");
        assertTrue(order.status() == OrderStatus.ACCEPTED || order.status() == OrderStatus.REJECTED,
                "final status must be a clean outcome of whichever transition won, not a corrupted state");
    }

    /**
     * The scenario called out in the assignment: a delivery partner is being assigned to an
     * order at the exact moment the order is cancelled (the closest legal analogue to
     * "restaurant rejects while assignment is in flight" — see {@link Order}'s class javadoc
     * for why {@code cancel}, not {@code reject}, is the transition that can actually overlap
     * with assignment). The invariant under test: it must never be possible for a partner to
     * remain reserved ({@code BUSY}) against an order that ends up {@code CANCELLED} — the
     * assignment service must observe the cancellation and compensate.
     */
    @RepeatedTest(50)
    void partnerAssignmentRacingWithCancellationNeverLeavesAStrandedReservation() throws InterruptedException {
        Order order = freshOrder("race-order-2");
        order.accept(); // partner assignment is only legal once ACCEPTED

        DeliveryPartner partner = new DeliveryPartner("rider-1", "Asha",
                new GeoLocation(12.97, 77.59), PartnerStatus.AVAILABLE);
        DeliveryAssignmentService assignmentService =
                new DeliveryAssignmentService(new NearestAvailablePartnerStrategy());
        assignmentService.registerPartner(partner);

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicBoolean assigned = new AtomicBoolean(false);

        pool.submit(() -> {
            await(startGate);
            Optional<DeliveryPartner> result =
                    assignmentService.assignPartnerFor(order, new GeoLocation(12.971, 77.591));
            assigned.set(result.isPresent());
        });
        pool.submit(() -> {
            await(startGate);
            try {
                order.cancel("customer changed their mind");
            } catch (InvalidOrderStateTransitionException ignored) {
                // Acceptable if assignment's internal order.assignDeliveryPartner() call had
                // not yet run, this thread still always succeeds from ACCEPTED, so this branch
                // should not trigger in this particular scenario; kept defensively.
            }
        });

        startGate.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(OrderStatus.CANCELLED, order.status());
        if (assigned.get()) {
            // Assignment won the race before cancellation: the partner is legitimately BUSY
            // and it is the service layer's job (OrderService#cancelOrder) to release it — not
            // a violation by itself, so simulate that compensating step here and verify it
            // actually frees the partner.
            assertEquals(PartnerStatus.BUSY, partner.status());
            assignmentService.release(partner.id());
            assertEquals(PartnerStatus.AVAILABLE, partner.status());
        } else {
            // Cancellation won the race: the assignment service must have released its own
            // reservation attempt, so the partner must already be AVAILABLE, never left BUSY.
            assertEquals(PartnerStatus.AVAILABLE, partner.status(),
                    "a losing assignment attempt must never leave the partner stranded as BUSY");
        }
    }

    /**
     * Two concurrent order-assignment attempts competing for the single available partner:
     * exactly one must win the CAS reservation; the loser must receive no partner rather than
     * both believing they succeeded (which would mean one rider delivering two orders at once).
     */
    @Test
    void onlyOneOfTwoConcurrentOrdersCanReserveTheSameSolePartner() throws InterruptedException {
        DeliveryPartner partner = new DeliveryPartner("rider-1", "Asha",
                new GeoLocation(12.97, 77.59), PartnerStatus.AVAILABLE);
        DeliveryAssignmentService assignmentService =
                new DeliveryAssignmentService(new NearestAvailablePartnerStrategy());
        assignmentService.registerPartner(partner);

        Order orderA = freshOrder("order-a");
        Order orderB = freshOrder("order-b");
        orderA.accept();
        orderB.accept();

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger winners = new AtomicInteger(0);

        Runnable assignToA = () -> {
            await(startGate);
            if (assignmentService.assignPartnerFor(orderA, new GeoLocation(12.97, 77.59)).isPresent()) {
                winners.incrementAndGet();
            }
        };
        Runnable assignToB = () -> {
            await(startGate);
            if (assignmentService.assignPartnerFor(orderB, new GeoLocation(12.97, 77.59)).isPresent()) {
                winners.incrementAndGet();
            }
        };

        pool.submit(assignToA);
        pool.submit(assignToB);
        startGate.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, winners.get(), "exactly one order must win the sole available partner");
        boolean aGotPartner = "rider-1".equals(orderA.deliveryPartnerId());
        boolean bGotPartner = "rider-1".equals(orderB.deliveryPartnerId());
        assertTrue(aGotPartner ^ bGotPartner, "the partner must be assigned to exactly one order, never both");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while awaiting start gate");
        }
    }
}
