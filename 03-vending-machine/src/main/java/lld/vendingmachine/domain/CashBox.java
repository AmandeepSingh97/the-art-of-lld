package lld.vendingmachine.domain;

import lld.vendingmachine.change.ChangeMaker;
import lld.vendingmachine.exceptions.InsufficientChangeException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The machine's physical cash float: how many of each {@link Denomination}
 * are currently sitting in the coin tubes / bill stacker.
 *
 * <p><b>Thread safety:</b> every operation that reads-then-writes the counts
 * (deposit, withdraw-for-change, reverse-deposit) is a compound action that
 * must appear atomic, otherwise two concurrent transactions could both read
 * "3 quarters available", both decide that's enough, and overdraw the tube to
 * -1. We guard all compound operations with a single {@link ReentrantLock}
 * scoped to this object. The lock is deliberately <em>not</em> the same lock
 * used for machine-wide state transitions (see {@code VendingMachine}): cash
 * box mutations and front-panel state transitions are different invariants,
 * and merging them into one lock would only increase contention without
 * adding correctness.</p>
 */
public final class CashBox {

    private final Map<Denomination, Integer> counts = new EnumMap<>(Denomination.class);
    private final ReentrantLock lock = new ReentrantLock();

    public CashBox() {
        for (Denomination d : Denomination.values()) {
            counts.put(d, 0);
        }
    }

    /** Admin/initial-load operation to seed the float with a starting count. */
    public void load(Denomination denomination, int count) {
        lock.lock();
        try {
            counts.merge(denomination, count, Integer::sum);
        } finally {
            lock.unlock();
        }
    }

    /** Commits a customer's inserted cash into the box (called on successful payment settlement). */
    public void deposit(List<Denomination> denominations) {
        lock.lock();
        try {
            for (Denomination d : denominations) {
                counts.merge(d, 1, Integer::sum);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reverses a {@link #deposit(List)} when a transaction has to be aborted
     * after the money was already committed to the box (e.g. change could not
     * be made). This keeps the box's counts consistent with "what physically
     * left the machine as a refund."
     */
    public void reverseDeposit(List<Denomination> denominations) {
        lock.lock();
        try {
            for (Denomination d : denominations) {
                int current = counts.get(d);
                if (current <= 0) {
                    // Should be unreachable because we only reverse what we just deposited
                    // while holding no other mutation in between; defensive check kept for safety.
                    throw new IllegalStateException("Cannot reverse deposit: " + d + " count is already 0");
                }
                counts.put(d, current - 1);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to withdraw exact change for {@code amount} using the supplied
     * algorithm, atomically: either the full breakdown is computed and
     * deducted, or (on failure) nothing is mutated and an
     * {@link InsufficientChangeException} propagates.
     */
    public Map<Denomination, Integer> withdrawForChange(Money amount, ChangeMaker changeMaker)
            throws InsufficientChangeException {
        lock.lock();
        try {
            Map<Denomination, Integer> snapshot = new EnumMap<>(counts);
            Map<Denomination, Integer> change = changeMaker.computeChange(amount, snapshot);
            change.forEach((d, c) -> counts.merge(d, -c, Integer::sum));
            return change;
        } finally {
            lock.unlock();
        }
    }

    /** Read-only snapshot for admin reporting / cash collection. */
    public Map<Denomination, Integer> snapshot() {
        lock.lock();
        try {
            return new EnumMap<>(counts);
        } finally {
            lock.unlock();
        }
    }

    public Money totalValue() {
        lock.lock();
        try {
            long cents = counts.entrySet().stream()
                    .mapToLong(e -> e.getKey().valueInCents() * e.getValue())
                    .sum();
            return Money.ofCents(cents);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Admin operation: empties the box (e.g. a cash collection run) while
     * leaving a configured float behind so the machine can keep making change.
     */
    public Map<Denomination, Integer> collectExcept(Map<Denomination, Integer> floatToKeep) {
        lock.lock();
        try {
            Map<Denomination, Integer> collected = new EnumMap<>(Denomination.class);
            for (Denomination d : Denomination.values()) {
                int have = counts.get(d);
                int keep = floatToKeep.getOrDefault(d, 0);
                int take = Math.max(0, have - keep);
                collected.put(d, take);
                counts.put(d, have - take);
            }
            return collected;
        } finally {
            lock.unlock();
        }
    }
}
