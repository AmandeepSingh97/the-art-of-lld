package lld.stockmanagement.observer;

import lld.stockmanagement.model.InventoryKey;
import lld.stockmanagement.model.ReorderPolicy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reorder-point automation: watches every stock event and, when a key's
 * {@code available} quantity drops to or below its configured
 * {@link ReorderPolicy#getReorderPoint()}, raises a single {@link ReorderRequest} for
 * {@link ReorderPolicy#getReorderQuantity()} units.
 *
 * <p><b>Debouncing:</b> without guarding against it, every subsequent stock-out event
 * while already below the reorder point would raise another duplicate request. A
 * {@code requestAlreadyRaised} flag per key suppresses repeats until the level climbs
 * back above the reorder point (e.g. the suggested PO arrives and is received via
 * {@code stockIn}), at which point the flag resets and a future dip can raise a fresh
 * request. This is a simple, explicit state machine rather than a time-based
 * debounce (e.g. "don't alert again for 1 hour") because the latter would either
 * suppress a genuinely new, worse stock-out or require a scheduler dependency this
 * exercise does not need.
 */
public final class ReorderAutomationListener implements StockEventListener {

    private final Map<InventoryKey, ReorderPolicy> policies = new ConcurrentHashMap<>();
    private final Map<InventoryKey, Boolean> requestAlreadyRaised = new ConcurrentHashMap<>();
    private final List<ReorderRequest> raisedRequests = new CopyOnWriteArrayList<>();

    public void registerPolicy(InventoryKey key, ReorderPolicy policy) {
        policies.put(key, policy);
    }

    @Override
    public void onStockEvent(StockEvent event) {
        ReorderPolicy policy = policies.get(event.getInventoryKey());
        if (policy == null) {
            return;
        }
        int available = event.getLevelAfter().getAvailable();
        if (policy.shouldReorder(available)) {
            Boolean alreadyRaised = requestAlreadyRaised.putIfAbsent(event.getInventoryKey(), Boolean.TRUE);
            if (alreadyRaised == null) {
                raisedRequests.add(new ReorderRequest(event.getInventoryKey(), policy.getReorderQuantity()));
            }
        } else {
            requestAlreadyRaised.remove(event.getInventoryKey());
        }
    }

    public List<ReorderRequest> getRaisedRequests() {
        return List.copyOf(raisedRequests);
    }
}
