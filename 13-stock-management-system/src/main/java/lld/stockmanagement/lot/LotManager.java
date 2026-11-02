package lld.stockmanagement.lot;

import lld.stockmanagement.model.InventoryKey;
import lld.stockmanagement.model.Lot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which physical {@link Lot}s back each {@link InventoryKey} and delegates
 * withdrawal decisions to a {@link LotAllocationStrategy}.
 *
 * <p><b>Concurrency note:</b> this manager is intentionally <i>not</i> part of the
 * lock-free/optimistic/pessimistic comparison. Lot allocation only needs to run once
 * a reservation or stock-out has already been granted by the {@code InventoryLedger}
 * (which already enforced the no-overselling invariant on the aggregate count), so by
 * the time {@link #withdraw} runs, the caller holds a "ticket" for that many units —
 * only the question of *which* lots to deduct from remains, and that is serialized
 * per-key with a plain {@code synchronized} block since lot picking is comparatively
 * rare (once per pick/ship event, not once per concurrent reservation attempt) and
 * does not need the throughput of the ledger's hot path.
 */
public final class LotManager {

    private final Map<InventoryKey, List<Lot>> lotsByKey = new ConcurrentHashMap<>();
    private final LotAllocationStrategy strategy;

    public LotManager(LotAllocationStrategy strategy) {
        this.strategy = strategy;
    }

    public void receiveLot(InventoryKey key, Lot lot) {
        List<Lot> lots = lotsByKey.computeIfAbsent(key, k -> new ArrayList<>());
        synchronized (lots) {
            lots.add(lot);
        }
    }

    public List<LotAllocationStrategy.LotAllocation> withdraw(InventoryKey key, int quantity, LocalDate asOf) {
        List<Lot> lots = lotsByKey.getOrDefault(key, List.of());
        synchronized (lots) {
            return strategy.allocate(lots, quantity, asOf);
        }
    }

    public List<Lot> lotsFor(InventoryKey key) {
        return List.copyOf(lotsByKey.getOrDefault(key, List.of()));
    }
}
