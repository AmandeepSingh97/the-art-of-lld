package lld.vendingmachine.payment;

import lld.vendingmachine.domain.Denomination;

import java.util.Collections;
import java.util.Map;

/**
 * Result of successfully settling a payment: the change breakdown to
 * physically dispense (empty for card payments, since card charges the exact
 * price) and an optional authorization reference for audit/receipt purposes.
 */
public record PaymentOutcome(Map<Denomination, Integer> changeToDispense, String authorizationReference) {

    public PaymentOutcome {
        changeToDispense = Collections.unmodifiableMap(changeToDispense);
    }

    public static PaymentOutcome noChange(String authorizationReference) {
        return new PaymentOutcome(Collections.emptyMap(), authorizationReference);
    }
}
