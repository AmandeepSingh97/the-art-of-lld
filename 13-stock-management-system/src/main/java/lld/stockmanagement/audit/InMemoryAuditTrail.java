package lld.stockmanagement.audit;

import lld.stockmanagement.model.InventoryKey;
import lld.stockmanagement.model.StockMovement;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link AuditTrail}.
 *
 * <p>Thread-safety: each per-key list is a {@link CopyOnWriteArrayList}. Audit writes
 * are append-mostly and read-rarely-but-when-read-need-a-consistent-snapshot (e.g. an
 * ops dashboard paging through history while movements keep arriving) — exactly the
 * access pattern {@code CopyOnWriteArrayList} is designed for: writers pay a copy
 * cost per append, readers never block and never see a torn list. Because one audit
 * list exists per {@link InventoryKey} and movements against different keys are
 * independent, the per-key list is created lazily and atomically with
 * {@code computeIfAbsent} on a {@link ConcurrentHashMap}, so inserting a new key never
 * blocks appends to existing keys.
 */
public final class InMemoryAuditTrail implements AuditTrail {

    private final Map<InventoryKey, CopyOnWriteArrayList<StockMovement>> byKey = new ConcurrentHashMap<>();

    @Override
    public void record(StockMovement movement) {
        byKey.computeIfAbsent(movement.getInventoryKey(), k -> new CopyOnWriteArrayList<>())
                .add(movement);
    }

    @Override
    public List<StockMovement> history(InventoryKey key) {
        CopyOnWriteArrayList<StockMovement> list = byKey.get(key);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    @Override
    public List<StockMovement> all() {
        return byKey.values().stream()
                .flatMap(List::stream)
                .sorted((a, b) -> a.getOccurredAt().compareTo(b.getOccurredAt()))
                .toList();
    }
}
