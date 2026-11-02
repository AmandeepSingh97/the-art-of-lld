package lld.stockmanagement.lot;

import lld.stockmanagement.model.Lot;

import java.time.LocalDate;
import java.util.List;

/**
 * Strategy for picking which physical {@link Lot}s satisfy a withdrawal of a given
 * quantity. Part of the optional batch/lot tracking extension. Kept separate from
 * {@link lld.stockmanagement.ledger.InventoryLedger} because lot allocation is a
 * picking/fulfillment-time decision about *which* physical units move, while the
 * ledger only cares about aggregate counts — conflating the two would force every
 * concurrency strategy to also deal with per-lot mutable state.
 */
public interface LotAllocationStrategy {

    /**
     * Selects lots (in allocation order) and deducts {@code quantity} units from
     * them in place. Returns the list of (lot, quantityTaken) allocations actually
     * made; the sum of quantities taken is {@code min(quantity, totalAvailableAcrossLots)}.
     *
     * @throws IllegalArgumentException if {@code quantity} exceeds the sum of all
     *         non-expired lot quantities as of {@code asOf}.
     */
    List<LotAllocation> allocate(List<Lot> lots, int quantity, LocalDate asOf);

    /** One allocation decision: how many units were taken from which lot. */
    record LotAllocation(Lot lot, int quantityTaken) {
    }
}
