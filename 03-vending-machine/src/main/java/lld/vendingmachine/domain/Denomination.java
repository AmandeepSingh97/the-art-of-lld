package lld.vendingmachine.domain;

/**
 * The physical denominations a vending machine's cash acceptor and coin/bill
 * dispenser understand. Modeling this as an enum (rather than raw integers)
 * gives us type safety, an iteration order, and a single place to add a new
 * denomination (e.g. a $0.50 coin or a $50 bill) without touching change-making
 * logic elsewhere.
 *
 * <p>The ordinal/declaration order is descending by value, which the greedy
 * change-making algorithm relies on (see {@link lld.vendingmachine.change.GreedyChangeMaker}).
 * This value set is the standard US cash denomination system, which is
 * "canonical" — greedy change-making is provably optimal for it. See the
 * README's Data Structures and Algorithms section for the proof sketch and
 * what changes if a non-canonical denomination set is configured.</p>
 */
public enum Denomination {
    TWENTY_DOLLAR(2000),
    TEN_DOLLAR(1000),
    FIVE_DOLLAR(500),
    ONE_DOLLAR(100),
    QUARTER(25),
    DIME(10),
    NICKEL(5),
    PENNY(1);

    private final long valueInCents;

    Denomination(long valueInCents) {
        this.valueInCents = valueInCents;
    }

    public long valueInCents() {
        return valueInCents;
    }

    public boolean isCoin() {
        return this == QUARTER || this == DIME || this == NICKEL || this == PENNY;
    }
}
