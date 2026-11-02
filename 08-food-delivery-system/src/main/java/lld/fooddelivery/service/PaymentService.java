package lld.fooddelivery.service;

import lld.fooddelivery.domain.Order;
import lld.fooddelivery.domain.Payment;
import lld.fooddelivery.domain.PaymentStatus;
import lld.fooddelivery.exception.PaymentFailedException;
import lld.fooddelivery.util.IdGenerator;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles charging and refunding payments for orders.
 *
 * <p><b>Thread-safety and idempotency:</b> at most one {@link Payment} is stored per order id
 * ({@link ConcurrentHashMap#putIfAbsent}), and {@link Payment}'s own CAS-based status field
 * guarantees a refund can only ever be applied once even if {@link #refund(String)} is called
 * twice concurrently (e.g. a retried message from an at-least-once delivery queue, or a user
 * double-tapping "cancel order"): the second caller's compare-and-set fails harmlessly and
 * {@link #refund(String)} returns {@code false} rather than double-crediting the customer.
 */
public final class PaymentService {

    private final ConcurrentHashMap<String, Payment> paymentsByOrderId = new ConcurrentHashMap<>();
    private final IdGenerator idGenerator = new IdGenerator("pay");

    /**
     * Charges the order's total. In this in-memory simulation the charge always succeeds
     * unless the amount is zero (a defensive guard — a zero-value order should never reach
     * payment). A production implementation would call out to a payment gateway and handle
     * declines/timeouts — see README "Failure Scenarios".
     */
    public Payment charge(Order order) {
        if (order.total().isZero() || order.total().isNegative()) {
            throw new PaymentFailedException(order.id(), "invalid charge amount: " + order.total());
        }
        Payment payment = new Payment(idGenerator.next(), order.id(), order.total());
        Payment existing = paymentsByOrderId.putIfAbsent(order.id(), payment);
        if (existing != null) {
            // Idempotency guard: a retried placeOrder call for the same order must not double-charge.
            return existing;
        }
        payment.markSuccess();
        return payment;
    }

    public Optional<Payment> paymentFor(String orderId) {
        return Optional.ofNullable(paymentsByOrderId.get(orderId));
    }

    /** Idempotent: returns {@code false} if there was no successful payment to refund (including double-refund attempts). */
    public boolean refund(String orderId) {
        Payment payment = paymentsByOrderId.get(orderId);
        if (payment == null) {
            return false;
        }
        return payment.markRefunded();
    }

    public boolean wasRefunded(String orderId) {
        Payment payment = paymentsByOrderId.get(orderId);
        return payment != null && payment.status() == PaymentStatus.REFUNDED;
    }
}
