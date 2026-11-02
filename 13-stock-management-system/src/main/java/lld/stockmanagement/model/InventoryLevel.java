package lld.stockmanagement.model;

/**
 * An immutable point-in-time snapshot of one inventory ledger row.
 *
 * <p>Two numbers matter for every stock decision:
 * <ul>
 *   <li>{@code available} — physically on-hand quantity not yet promised to anyone.
 *       This is what gets decremented on a reservation and what reorder-point logic
 *       watches.</li>
 *   <li>{@code reserved} — quantity already promised to pending orders but not yet
 *       physically shipped. {@code onHand = available + reserved}.</li>
 * </ul>
 *
 * <p>{@code version} is an optimistic-concurrency fencing token. It has no business
 * meaning; it exists purely so a writer can detect "the row changed since I read it"
 * (see {@link lld.stockmanagement.ledger.OptimisticVersionedInventoryLedger}).
 */
public final class InventoryLevel {

    private final int available;
    private final int reserved;
    private final long version;

    public InventoryLevel(int available, int reserved, long version) {
        if (available < 0) {
            throw new IllegalArgumentException("available cannot be negative: " + available);
        }
        if (reserved < 0) {
            throw new IllegalArgumentException("reserved cannot be negative: " + reserved);
        }
        this.available = available;
        this.reserved = reserved;
        this.version = version;
    }

    public int getAvailable() {
        return available;
    }

    public int getReserved() {
        return reserved;
    }

    public int getOnHand() {
        return available + reserved;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return "InventoryLevel{available=" + available + ", reserved=" + reserved
                + ", version=" + version + "}";
    }
}
