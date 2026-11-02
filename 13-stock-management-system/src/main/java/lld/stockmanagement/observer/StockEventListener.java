package lld.stockmanagement.observer;

/**
 * Observer pattern subscriber for stock events. {@code InventoryService} (the
 * subject) notifies every registered listener after each successful mutation;
 * listeners decide independently whether the event is relevant to them (low-stock
 * alerting, reorder automation, metrics, etc.).
 *
 * <p>Notification happens synchronously and on the caller's thread in this design
 * (see README "Concurrency and Thread Safety" for why) — implementations must
 * therefore be fast and must not throw, since {@code InventoryService} does not
 * suppress listener exceptions (a misbehaving listener is a bug that should be
 * visible, not silently swallowed).
 */
public interface StockEventListener {

    void onStockEvent(StockEvent event);
}
