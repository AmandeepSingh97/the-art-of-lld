package lld.stockmanagement.model;

/**
 * The kind of stock movement recorded in the audit trail. Every mutation of an
 * inventory ledger row produces exactly one {@link StockMovement} with one of these
 * types — this is what makes the audit trail a complete, replayable history of how
 * the current {@code available}/{@code reserved} numbers were arrived at.
 */
public enum MovementType {
    /** Physical receipt of goods into a warehouse (increases available). */
    STOCK_IN,
    /** Physical removal of goods not tied to a reservation, e.g. damage write-off. */
    STOCK_OUT,
    /** Quantity moved from available to reserved for a pending order. */
    RESERVE,
    /** A reservation is cancelled; quantity moves back from reserved to available. */
    RELEASE_RESERVATION,
    /** A reservation is fulfilled (shipped); reserved quantity leaves the system. */
    FULFILL_RESERVATION,
    /** Manual correction, e.g. cycle-count adjustment. Can be positive or negative. */
    ADJUSTMENT
}
