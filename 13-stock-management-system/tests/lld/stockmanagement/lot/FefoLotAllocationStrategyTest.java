package lld.stockmanagement.lot;

import lld.stockmanagement.model.Lot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for {@link FefoLotAllocationStrategy}: expiry-ordered allocation. */
class FefoLotAllocationStrategyTest {

    private final FefoLotAllocationStrategy strategy = new FefoLotAllocationStrategy();
    private final LocalDate today = LocalDate.of(2026, 1, 1);

    @Test
    void allocatesFromSoonestExpiringLotFirst() {
        Lot soonExpiring = new Lot("LOT-A", "SKU-1", 10, today.plusDays(5));
        Lot laterExpiring = new Lot("LOT-B", "SKU-1", 10, today.plusDays(30));

        List<LotAllocationStrategy.LotAllocation> allocations =
                strategy.allocate(List.of(laterExpiring, soonExpiring), 8, today);

        assertEquals(1, allocations.size());
        assertEquals("LOT-A", allocations.get(0).lot().getLotId());
        assertEquals(8, allocations.get(0).quantityTaken());
        assertEquals(2, soonExpiring.getQuantity(), "soon-expiring lot must be depleted first");
        assertEquals(10, laterExpiring.getQuantity(), "later-expiring lot must remain untouched");
    }

    @Test
    void spillsOverIntoNextLotWhenFirstLotIsInsufficient() {
        Lot soonExpiring = new Lot("LOT-A", "SKU-1", 5, today.plusDays(5));
        Lot laterExpiring = new Lot("LOT-B", "SKU-1", 10, today.plusDays(30));

        List<LotAllocationStrategy.LotAllocation> allocations =
                strategy.allocate(List.of(laterExpiring, soonExpiring), 8, today);

        assertEquals(2, allocations.size());
        assertEquals(0, soonExpiring.getQuantity());
        assertEquals(7, laterExpiring.getQuantity());
    }

    @Test
    void ignoresExpiredLots() {
        Lot expired = new Lot("LOT-EXP", "SKU-1", 100, today.minusDays(1));
        Lot fresh = new Lot("LOT-FRESH", "SKU-1", 10, today.plusDays(10));

        List<LotAllocationStrategy.LotAllocation> allocations =
                strategy.allocate(List.of(expired, fresh), 5, today);

        assertEquals(1, allocations.size());
        assertEquals("LOT-FRESH", allocations.get(0).lot().getLotId());
    }

    @Test
    void throwsWhenRequestedQuantityExceedsNonExpiredStock() {
        Lot lot = new Lot("LOT-A", "SKU-1", 5, today.plusDays(5));
        assertThrows(IllegalArgumentException.class, () -> strategy.allocate(List.of(lot), 10, today));
    }
}
