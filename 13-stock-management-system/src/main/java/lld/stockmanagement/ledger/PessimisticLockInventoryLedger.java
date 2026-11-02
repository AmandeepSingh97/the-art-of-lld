package lld.stockmanagement.ledger;

import lld.stockmanagement.exception.InsufficientStockException;
import lld.stockmanagement.model.InventoryKey;
import lld.stockmanagement.model.InventoryLevel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pessimistic-locking ledger: each row owns a dedicated {@link ReentrantLock}
 * ("lock striping" — one lock per key, not one global lock), and every mutation
 * acquires that row's lock before reading or writing its mutable fields. This is the
 * in-memory analogue of {@code SELECT ... FOR UPDATE}: a transaction takes an
 * exclusive row lock before it even decides whether the operation is valid, so no
 * other transaction can interleave a conflicting read or write until it commits.
 *
 * <p><b>Why this is correct:</b> {@code available}/{@code reserved}/{@code version}
 * are plain {@code int}/{@code long} fields, not volatile or atomic — they do not
 * need to be, because every read and write happens exclusively while holding the
 * row's lock, and {@link ReentrantLock#lock()}/{@link ReentrantLock#unlock()}
 * establish the same happens-before relationship a {@code synchronized} block would:
 * whatever the lock holder wrote is guaranteed visible to the next thread that
 * acquires the same lock.
 *
 * <p><b>Cost model:</b> unlike the optimistic strategy, pessimistic locking never
 * wastes work on doomed attempts — a thread either waits or proceeds, it never
 * computes a result that gets thrown away. This makes it the more predictable choice
 * under very high contention on a single hot key. The cost is that every operation
 * serializes on the row lock even when there is no actual conflict (e.g. two
 * {@code stockIn} calls that could in principle both succeed independently still
 * queue behind each other), and a slow/blocked holder (e.g. a thread that got
 * preempted mid-critical-section) stalls every other thread waiting on that same
 * row — something that cannot happen with the lock-free CAS strategy.
 */
public final class PessimisticLockInventoryLedger implements InventoryLedger {

    private static final class Row {
        private final ReentrantLock lock = new ReentrantLock();
        private int available;
        private int reserved;
        private long version;

        Row(int available) {
            this.available = available;
            this.reserved = 0;
            this.version = 0L;
        }

        InventoryLevel snapshotUnlocked() {
            return new InventoryLevel(available, reserved, version);
        }
    }

    private final Map<InventoryKey, Row> rows = new ConcurrentHashMap<>();

    @Override
    public void initialize(InventoryKey key, int initialAvailable) {
        rows.putIfAbsent(key, new Row(initialAvailable));
    }

    @Override
    public InventoryLevel snapshot(InventoryKey key) {
        Row row = rowFor(key);
        row.lock.lock();
        try {
            return row.snapshotUnlocked();
        } finally {
            row.lock.unlock();
        }
    }

    @Override
    public InventoryLevel stockIn(InventoryKey key, int quantity) {
        LedgerValidation.requirePositive(quantity);
        Row row = rowFor(key);
        row.lock.lock();
        try {
            row.available += quantity;
            row.version++;
            return row.snapshotUnlocked();
        } finally {
            row.lock.unlock();
        }
    }

    @Override
    public InventoryLevel reserve(InventoryKey key, int quantity) {
        LedgerValidation.requirePositive(quantity);
        Row row = rowFor(key);
        row.lock.lock();
        try {
            if (row.available < quantity) {
                throw new InsufficientStockException(key, quantity, row.available);
            }
            row.available -= quantity;
            row.reserved += quantity;
            row.version++;
            return row.snapshotUnlocked();
        } finally {
            row.lock.unlock();
        }
    }

    @Override
    public InventoryLevel releaseReservation(InventoryKey key, int quantity) {
        LedgerValidation.requirePositive(quantity);
        Row row = rowFor(key);
        row.lock.lock();
        try {
            int released = Math.min(quantity, row.reserved);
            row.reserved -= released;
            row.available += released;
            row.version++;
            return row.snapshotUnlocked();
        } finally {
            row.lock.unlock();
        }
    }

    @Override
    public InventoryLevel fulfillReservation(InventoryKey key, int quantity) {
        LedgerValidation.requirePositive(quantity);
        Row row = rowFor(key);
        row.lock.lock();
        try {
            if (row.reserved < quantity) {
                throw new InsufficientStockException(key, quantity, row.reserved);
            }
            row.reserved -= quantity;
            row.version++;
            return row.snapshotUnlocked();
        } finally {
            row.lock.unlock();
        }
    }

    @Override
    public InventoryLevel adjust(InventoryKey key, int delta) {
        Row row = rowFor(key);
        row.lock.lock();
        try {
            int newAvailable = row.available + delta;
            if (newAvailable < 0) {
                throw new InsufficientStockException(key, -delta, row.available);
            }
            row.available = newAvailable;
            row.version++;
            return row.snapshotUnlocked();
        } finally {
            row.lock.unlock();
        }
    }

    private Row rowFor(InventoryKey key) {
        Row row = rows.get(key);
        if (row == null) {
            throw new IllegalStateException("Inventory row not initialized for " + key);
        }
        return row;
    }
}
