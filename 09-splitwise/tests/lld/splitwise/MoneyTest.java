package lld.splitwise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import lld.splitwise.model.Money;

class MoneyTest {

    @Test
    void ofMajorUnitsConvertsExactlyToMinorUnits() {
        assertEquals(1250L, Money.ofMajorUnits("12.50").minorUnits());
        assertEquals(0L, Money.ZERO.minorUnits());
    }

    @Test
    void arithmeticIsExactWithNoFloatingPointDrift() {
        Money total = Money.ZERO;
        // 0.1 + 0.1 + 0.1 famously != 0.3 in double arithmetic; verify Money avoids this.
        Money tenCents = Money.ofMajorUnits("0.10");
        for (int i = 0; i < 3; i++) {
            total = total.add(tenCents);
        }
        assertEquals(Money.ofMajorUnits("0.30"), total);
    }

    @Test
    void negateAndAbsBehaveCorrectly() {
        Money m = Money.ofMajorUnits("5.00");
        assertEquals(Money.ofMajorUnits("-5.00"), m.negate());
        assertEquals(m, m.negate().abs());
        assertTrue(m.isPositive());
        assertFalse(m.isNegative());
        assertTrue(m.negate().isNegative());
    }

    @Test
    void equalsAndHashCodeAreValueBased() {
        assertEquals(Money.ofMinorUnits(500), Money.ofMajorUnits("5.00"));
        assertEquals(Money.ofMinorUnits(500).hashCode(), Money.ofMajorUnits("5.00").hashCode());
    }

    @Test
    void compareToOrdersByMinorUnits() {
        assertTrue(Money.ofMajorUnits("1.00").compareTo(Money.ofMajorUnits("2.00")) < 0);
        assertTrue(Money.ofMajorUnits("2.00").compareTo(Money.ofMajorUnits("1.00")) > 0);
        assertEquals(0, Money.ofMajorUnits("2.00").compareTo(Money.ofMajorUnits("2.00")));
    }

    @Test
    void overflowProtectionOnAdd() {
        Money max = Money.ofMinorUnits(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> max.add(Money.ofMinorUnits(1)));
    }

    @Test
    void ofMajorUnitsAcceptsBigDecimalDirectly() {
        assertEquals(Money.ofMinorUnits(199), Money.ofMajorUnits(new BigDecimal("1.99")));
    }
}
