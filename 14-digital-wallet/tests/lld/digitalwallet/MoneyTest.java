package lld.digitalwallet;

import lld.digitalwallet.model.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    private final Currency usd = Currency.getInstance("USD");

    @Test
    void majorUnitsConvertExactlyToMinorUnits() {
        Money m = Money.ofMajorUnits(new BigDecimal("19.99"), usd);
        assertEquals(1999L, m.minorUnits());
        assertEquals(new BigDecimal("19.99"), m.toMajorUnits());
    }

    @Test
    void addAndSubtractAreExactNoFloatingPointDrift() {
        // The classic 0.1 + 0.2 != 0.3 failure mode with double; must be exact here.
        Money a = Money.ofMajorUnits(new BigDecimal("0.10"), usd);
        Money b = Money.ofMajorUnits(new BigDecimal("0.20"), usd);
        Money sum = a.add(b);
        assertEquals(Money.ofMajorUnits(new BigDecimal("0.30"), usd), sum);
        assertEquals(30L, sum.minorUnits());
    }

    @Test
    void repeatedAdditionNeverDrifts() {
        Money tenCents = Money.ofMinorUnits(10, usd);
        Money running = Money.zero(usd);
        for (int i = 0; i < 100_000; i++) {
            running = running.add(tenCents);
        }
        assertEquals(1_000_000L, running.minorUnits());
    }

    @Test
    void differentCurrenciesCannotBeCombined() {
        Money usdMoney = Money.ofMinorUnits(100, usd);
        Money eurMoney = Money.ofMinorUnits(100, Currency.getInstance("EUR"));
        assertThrows(IllegalArgumentException.class, () -> usdMoney.add(eurMoney));
    }

    @Test
    void comparisonAndSignHelpers() {
        Money zero = Money.zero(usd);
        Money positive = Money.ofMinorUnits(1, usd);
        Money negative = Money.ofMinorUnits(-1, usd);
        assertTrue(positive.isPositive());
        assertTrue(negative.isNegative());
        assertFalse(zero.isPositive());
        assertTrue(zero.isZero());
        assertTrue(positive.compareTo(negative) > 0);
    }

    @Test
    void equalsAndHashCodeAreValueBased() {
        Money a = Money.ofMinorUnits(500, usd);
        Money b = Money.ofMinorUnits(500, usd);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
