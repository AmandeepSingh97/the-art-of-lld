package lld.parkinglot.strategy;

import lld.parkinglot.model.Payment;
import lld.parkinglot.util.Money;

/**
 * Strategy Pattern: settles a charge through one payment rail. Each
 * {@link lld.parkinglot.enums.PaymentMethod} maps to one implementation,
 * selected by {@code PaymentService} - adding a new payment method (e.g. a
 * subscription/auto-debit rail) means adding one class and one registry
 * entry, never touching existing processors.
 */
public interface PaymentProcessor {

    /**
     * Attempts to charge {@code amount} against {@code ticketId}.
     *
     * @return a {@link Payment} record whose status reflects success or failure.
     *         Implementations should not throw for a normal decline; reserve
     *         exceptions for infrastructure failure (network, timeout).
     */
    Payment charge(String ticketId, Money amount);
}
