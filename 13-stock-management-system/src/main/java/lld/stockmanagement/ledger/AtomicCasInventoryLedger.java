package lld.stockmanagement.ledger;

import lld.stockmanagement.exception.InsufficientStockException;
import lld.stockmanagement.model.InventoryKey;
import lld.stockmanagement.model.InventoryLevel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free ledger using a single packed {@link AtomicLong} per row: the high 32 bits
 * store {@code available}, the low 32 bits store {@code reserved}. Both fields are
 * read and written together with one {@code compareAndSet} on the packed long, which
 * is what makes this strategy different from — and strictly stronger than — a naive
 * "one {@code AtomicInteger} per field" design.
 *
 * <p><b>Why pack both fields into one word:</b> {@code reserve()} must change
 * {@code available} and {@code reserved} together (decrement one, increment the
 * other) as one indivisible step. Two independent {@code AtomicInteger}s cannot do
 * that atomically — a reader between the two individual CAS calls could observe
 * {@code available} already decremented but {@code reserved} not yet incremented,
 * i.e. units that exist in neither bucket. Packing both fields into one 64-bit word
 * and CASing the whole word closes that window: every observer either sees the state
 * fully before or fully after a reservation, with no in-between.
 *
 * <p>This mirrors the database-level pattern {@code UPDATE inventory SET available =
 * available - ?, reserved = reserved + ? WHERE available >= ?} executed as one atomic
 * statement, except the "row" here is 8 bytes of memory and the primitive is a CPU
 * CAS instruction instead of a transaction.
 *
 * <p><b>Cost model:</b> this is the cheapest strategy per operation — no object
 * allocation on the hot path (unlike the optimistic strategy, which allocates a new
 * {@link InventoryLevel} per attempt), no lock/monitor (unlike the pessimistic
 * strategy). The price is reduced range (32 bits per field, i.e. ~2.1 billion units —
 * ample for physical inventory) and reduced extensibility: adding a third field that
 * must be updated atomically alongside these two (e.g. a version/fencing token) would
 * not fit in 64 bits and would force a redesign, whereas the optimistic strategy's
 * immutable-object CAS trivially absorbs new fields.
 */
public final class AtomicCasInventoryLedger implements InventoryLedger {

    private final Map<InventoryKey, AtomicLong> rows = new ConcurrentHashMap<>();

    private static long pack(int available, int reserved) {
        return (((long) available) << 32) | (reserved & 0xFFFFFFFFL);
    }

    private static int unpackAvailable(long packed) {
        return (int) (packed >>> 32);
    }

    private static int unpackReserved(long packed) {
        return (int) packed;
    }

    private static InventoryLevel toLevel(long packed, long version) {
        return new InventoryLevel(unpackAvailable(packed), unpackReserved(packed), version);
    }

    @Override
    public void initialize(InventoryKey key, int initialAvailable) {
        rows.putIfAbsent(key, new AtomicLong(pack(initialAvailable, 0)));
    }

    @Override
    public InventoryLevel snapshot(InventoryKey key) {
        return toLevel(rowFor(key).get(), 0L);
    }

    @Override
    public InventoryLevel stockIn(InventoryKey key, int quantity) {
        LedgerValidation.requirePositive(quantity);
        AtomicLong row = rowFor(key);
        while (true) {
            long current = row.get();
            int available = unpackAvailable(current);
            int reserved = unpackReserved(current);
            long updated = pack(available + quantity, reserved);
            if (row.compareAndSet(current, updated)) {
                return toLevel(updated, 0L);
            }
        }
    }

    @Override
    public InventoryLevel reserve(InventoryKey key, int quantity) {
        LedgerValidation.requirePositive(quantity);
        AtomicLong row = rowFor(key);
        while (true) {
            long current = row.get();
            int available = unpackAvailable(current);
            int reserved = unpackReserved(current);
            if (available < quantity) {
                // Re-check on the freshest read before failing: another thread may have
                // released stock concurrently. Only a truly current snapshot can decide this.
                throw new InsufficientStockException(key, quantity, available);
            }
            long updated = pack(available - quantity, reserved + quantity);
            if (row.compareAndSet(current, updated)) {
                return toLevel(updated, 0L);
            }
            // CAS lost the race; loop re-reads the now-current packed value and re-validates.
        }
    }

    @Override
    public InventoryLevel releaseReservation(InventoryKey key, int quantity) {
        LedgerValidation.requirePositive(quantity);
        AtomicLong row = rowFor(key);
        while (true) {
            long current = row.get();
            int available = unpackAvailable(current);
            int reserved = unpackReserved(current);
            int released = Math.min(quantity, reserved);
            long updated = pack(available + released, reserved - released);
            if (row.compareAndSet(current, updated)) {
                return toLevel(updated, 0L);
            }
        }
    }

    @Override
    public InventoryLevel fulfillReservation(InventoryKey key, int quantity) {
        LedgerValidation.requirePositive(quantity);
        AtomicLong row = rowFor(key);
        while (true) {
            long current = row.get();
            int available = unpackAvailable(current);
            int reserved = unpackReserved(current);
            if (reserved < quantity) {
                throw new InsufficientStockException(key, quantity, reserved);
            }
            long updated = pack(available, reserved - quantity);
            if (row.compareAndSet(current, updated)) {
                return toLevel(updated, 0L);
            }
        }
    }

    @Override
    public InventoryLevel adjust(InventoryKey key, int delta) {
        AtomicLong row = rowFor(key);
        while (true) {
            long current = row.get();
            int available = unpackAvailable(current);
            int reserved = unpackReserved(current);
            int newAvailable = available + delta;
            if (newAvailable < 0) {
                throw new InsufficientStockException(key, -delta, available);
            }
            long updated = pack(newAvailable, reserved);
            if (row.compareAndSet(current, updated)) {
                return toLevel(updated, 0L);
            }
        }
    }

    private AtomicLong rowFor(InventoryKey key) {
        AtomicLong row = rows.get(key);
        if (row == null) {
            throw new IllegalStateException("Inventory row not initialized for " + key);
        }
        return row;
    }
}
