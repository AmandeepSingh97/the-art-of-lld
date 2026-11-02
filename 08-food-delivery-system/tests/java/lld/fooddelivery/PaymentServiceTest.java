package lld.fooddelivery;

import lld.fooddelivery.domain.Money;
import lld.fooddelivery.domain.Order;
import lld.fooddelivery.domain.OrderItem;
import lld.fooddelivery.domain.Payment;
import lld.fooddelivery.domain.PaymentStatus;
import lld.fooddelivery.exception.PaymentFailedException;
import lld.fooddelivery.service.PaymentService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validates payment charging and the idempotent-refund guarantee under concurrent retries. */
class PaymentServiceTest {

    private static Order orderWithTotal(String id, String total) {
        List<OrderItem> items = List.of(new OrderItem("item-1", "Pizza", Money.of(total), 1));
        return new Order(id, "cust-1", "rest-1", items, Money.of(total), Money.of(total));
    }

    @Test
    void chargeSucceedsForAPositiveTotal() {
        PaymentService paymentService = new PaymentService();
        Order order = orderWithTotal("order-1", "299.00");
        Payment payment = paymentService.charge(order);
        assertEquals(PaymentStatus.SUCCESS, payment.status());
        assertEquals(Money.of("299.00"), payment.amount());
    }

    @Test
    void chargeRejectsAZeroAmountOrder() {
        PaymentService paymentService = new PaymentService();
        Order order = orderWithTotal("order-1", "0.00");
        assertThrows(PaymentFailedException.class, () -> paymentService.charge(order));
    }

    @Test
    void refundIsIdempotentUnderConcurrentDuplicateCalls() throws InterruptedException {
        PaymentService paymentService = new PaymentService();
        Order order = orderWithTotal("order-1", "500.00");
        paymentService.charge(order);

        int attempts = 20;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(attempts);
        AtomicInteger successfulRefunds = new AtomicInteger(0);

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    if (paymentService.refund(order.id())) {
                        successfulRefunds.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, successfulRefunds.get(), "exactly one of the concurrent duplicate refund calls must succeed");
        assertTrue(paymentService.wasRefunded(order.id()));
    }

    @Test
    void refundingAnOrderThatWasNeverChargedReturnsFalse() {
        PaymentService paymentService = new PaymentService();
        assertFalse(paymentService.refund("never-charged"));
    }

    @Test
    void chargeIsIdempotentForTheSameOrderId() {
        PaymentService paymentService = new PaymentService();
        Order order = orderWithTotal("order-1", "500.00");
        Payment first = paymentService.charge(order);
        Payment second = paymentService.charge(order);
        assertEquals(first.id(), second.id(), "a retried charge for the same order must not create a second payment");
    }
}
