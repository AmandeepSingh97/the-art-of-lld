package lld.vendingmachine.payment;

import lld.vendingmachine.change.GreedyChangeMaker;
import lld.vendingmachine.core.VendingMachine;
import lld.vendingmachine.domain.CashBox;
import lld.vendingmachine.domain.Inventory;
import lld.vendingmachine.domain.Money;
import lld.vendingmachine.exceptions.PaymentDeclinedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardPaymentStrategyTest {

    @Test
    void settleReturnsAuthorizationReferenceAndNoChangeOnSuccess() throws PaymentDeclinedException {
        VendingMachine machine = new VendingMachine(
                new Inventory(), new CashBox(), new GreedyChangeMaker(), new MockCardPaymentGateway());
        PaymentOutcome outcome = new CardPaymentStrategy().settle(Money.ofCents(150), machine);
        assertTrue(outcome.changeToDispense().isEmpty());
        assertTrue(outcome.authorizationReference().startsWith("AUTH-"));
    }

    @Test
    void settlePropagatesGatewayDeclineWithoutMutatingAnyState() {
        VendingMachine machine = new VendingMachine(
                new Inventory(), new CashBox(), new GreedyChangeMaker(), new MockCardPaymentGateway(true));
        assertThrows(PaymentDeclinedException.class,
                () -> new CardPaymentStrategy().settle(Money.ofCents(150), machine));
    }
}
