package lld.vendingmachine.domain;

import lld.vendingmachine.exceptions.ProductNotFoundException;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The machine's catalog: a collection of {@link Slot}s keyed by slot id.
 *
 * <p>Backed by {@link ConcurrentHashMap} so that slot lookups and the
 * admin-driven "add a brand new slot" operation are thread-safe without a
 * coarse lock. Mutating a slot's <em>quantity</em> is delegated to the slot
 * itself (see {@link Slot}), which owns finer-grained synchronization — the
 * inventory map structure and an individual slot's count are different
 * concerns with different concurrency needs, so they get different locks.</p>
 */
public final class Inventory {

    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    public void addSlot(Slot slot) {
        slots.put(slot.getSlotId(), slot);
    }

    public Slot getSlot(String slotId) {
        Slot slot = slots.get(slotId);
        if (slot == null) {
            throw new ProductNotFoundException("No slot with id " + slotId);
        }
        return slot;
    }

    public Collection<Slot> allSlots() {
        return slots.values();
    }

    /**
     * Used by the state machine to decide whether the whole machine should
     * degrade into {@code OutOfStockState}. O(n) in the number of slots, which
     * is fine: real machines have dozens of slots, not millions.
     */
    public boolean isCompletelyEmpty() {
        return slots.values().stream().allMatch(Slot::isEmpty);
    }

    public Optional<Slot> findBySlotId(String slotId) {
        return Optional.ofNullable(slots.get(slotId));
    }
}
