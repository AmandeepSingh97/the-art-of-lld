package lld.bookmyshow.strategy;

import lld.bookmyshow.enums.SeatCategory;
import lld.bookmyshow.model.Show;
import lld.bookmyshow.repository.SeatInventoryRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lld.bookmyshow.enums.SeatStatus;
import lld.bookmyshow.model.ShowSeat;

/**
 * Demand- and time-sensitive pricing: applies a surge multiplier that grows
 * as (a) the show fills up and (b) the showtime approaches, mirroring how
 * ride-hailing/ticketing surge pricing commonly works. This is intentionally
 * simple (linear surge, capped) rather than a full revenue-management model
 * — good enough to demonstrate the Strategy seam without turning the
 * interview into a pricing-science discussion.
 */
public class DynamicPricingStrategy implements PricingStrategy {

    private static final BigDecimal MAX_SURGE = BigDecimal.valueOf(1.75);
    private static final Duration SURGE_WINDOW = Duration.ofHours(6);

    private final SeatInventoryRepository seatInventoryRepository;

    public DynamicPricingStrategy(SeatInventoryRepository seatInventoryRepository) {
        this.seatInventoryRepository = seatInventoryRepository;
    }

    @Override
    public BigDecimal priceFor(Show show, SeatCategory category, Instant now) {
        BigDecimal base = show.basePriceFor(category).multiply(category.priceMultiplier());
        BigDecimal occupancySurge = occupancySurge(show.getId());
        BigDecimal proximitySurge = proximitySurge(show.getStartTime(), now);
        BigDecimal surge = occupancySurge.max(proximitySurge).min(MAX_SURGE);
        return base.multiply(surge).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal occupancySurge(String showId) {
        List<ShowSeat> seats = seatInventoryRepository.findAllForShow(showId);
        if (seats.isEmpty()) {
            return BigDecimal.ONE;
        }
        long booked = seats.stream().filter(s -> s.getEffectiveStatus(System.currentTimeMillis()) == SeatStatus.BOOKED).count();
        double occupancyRatio = (double) booked / seats.size();
        // Linear ramp: 0% occupancy -> 1.0x, 100% occupancy -> 1.5x.
        return BigDecimal.valueOf(1.0 + occupancyRatio * 0.5);
    }

    private BigDecimal proximitySurge(Instant showStart, Instant now) {
        Duration untilShow = Duration.between(now, showStart);
        if (untilShow.isNegative() || untilShow.compareTo(SURGE_WINDOW) >= 0) {
            return BigDecimal.ONE;
        }
        double fractionElapsedOfWindow = 1.0 - ((double) untilShow.toMinutes() / SURGE_WINDOW.toMinutes());
        // Linear ramp inside the last 6 hours before showtime: up to +25%.
        return BigDecimal.valueOf(1.0 + fractionElapsedOfWindow * 0.25);
    }
}
