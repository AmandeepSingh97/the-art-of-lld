package lld.vendingmachine.payment;

import lld.vendingmachine.domain.Money;
import lld.vendingmachine.exceptions.PaymentDeclinedException;

/**
 * Abstraction over an external card payment processor (the real-world
 * equivalent of a Stripe/Square terminal SDK). Isolating this behind an
 * interface means the vending machine's core logic never depends on a
 * concrete payment network, and it is trivially mockable in tests.
 */
public interface CardPaymentGateway {

    /**
     * Authorizes and captures exactly {@code amount}.
     *
     * @return an opaque authorization reference for receipts/audit
     * @throws PaymentDeclinedException if the network declines, times out, or errors
     */
    String charge(Money amount) throws PaymentDeclinedException;
}
