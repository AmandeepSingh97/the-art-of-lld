package lld.vendingmachine.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void ofDollarsRoundsToNearestCentAvoidingFloatingPointDrift() {
        // 1.1 dollars is a classic binary-floating-point trap (1.1 cannot be
        // represented exactly), so this guards against a naive dollars*100 cast.
        Money money = Money.ofDollars(1.10);
        assertEquals(110L, money.cents());
    }

    @Test
    void addIsCommutativeAndAccumulatesCents() {
        Money a = Money.ofCents(125);
        Money b = Money.ofCents(75);
        assertEquals(Money.ofCents(200), a.add(b));
        assertEquals(a.add(b), b.add(a));
    }

    @Test
    void subtractThrowsWhenResultWouldBeNegative() {
        Money small = Money.ofCents(50);
        Money large = Money.ofCents(100);
        assertThrows(IllegalArgumentException.class, () -> small.subtract(large));
    }

    @Test
    void negativeConstructionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Money(-1));
    }

    @Test
    void isGreaterThanOrEqualToIsInclusive() {
        Money m = Money.ofCents(100);
        assertTrue(m.isGreaterThanOrEqualTo(Money.ofCents(100)));
        assertTrue(m.isGreaterThanOrEqualTo(Money.ofCents(99)));
        assertFalse(m.isGreaterThanOrEqualTo(Money.ofCents(101)));
    }

    @Test
    void toStringFormatsAsDollarsAndCents() {
        assertEquals("$1.05", Money.ofCents(105).toString());
        assertEquals("$0.07", Money.ofCents(7).toString());
    }
}
