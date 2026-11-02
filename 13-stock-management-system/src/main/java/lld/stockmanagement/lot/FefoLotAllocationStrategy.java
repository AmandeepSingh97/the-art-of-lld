package lld.stockmanagement.lot;

import lld.stockmanagement.model.Lot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * First-Expired-First-Out allocation: consumes from the soonest-to-expire
 * non-expired lot first, minimizing future write-offs from expiry. This is the
 * standard policy for perishable/date-sensitive inventory (food, pharma,
 * chemicals) — as opposed to FIFO-by-receipt-date, which is simpler but can leave an
 * older, earlier-expiring lot sitting unused behind a newer one.
 */
public final class FefoLotAllocationStrategy implements LotAllocationStrategy {

    @Override
    public List<LotAllocation> allocate(List<Lot> lots, int quantity, LocalDate asOf) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        List<Lot> candidates = lots.stream()
                .filter(lot -> !lot.isExpired(asOf) && lot.getQuantity() > 0)
                .sorted(Comparator.comparing(Lot::getExpiryDate))
                .toList();

        int totalAvailable = candidates.stream().mapToInt(Lot::getQuantity).sum();
        if (totalAvailable < quantity) {
            throw new IllegalArgumentException("Requested " + quantity
                    + " but only " + totalAvailable + " non-expired units available across lots");
        }

        List<LotAllocation> allocations = new ArrayList<>();
        int remaining = quantity;
        for (Lot lot : candidates) {
            if (remaining == 0) {
                break;
            }
            int taken = lot.deduct(remaining);
            if (taken > 0) {
                allocations.add(new LotAllocation(lot, taken));
                remaining -= taken;
            }
        }
        return allocations;
    }
}
