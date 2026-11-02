package lld.bookmyshow;

import lld.bookmyshow.enums.SeatCategory;
import lld.bookmyshow.model.Show;
import lld.bookmyshow.repository.SeatInventoryRepository;
import lld.bookmyshow.repository.ShowRepository;
import lld.bookmyshow.strategy.DynamicPricingStrategy;
import lld.bookmyshow.strategy.FixedPricingStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingStrategyTest {

    @Test
    void fixedPricingAppliesCategoryMultiplierToBasePrice() {
        ShowRepository showRepository = new ShowRepository();
        SeatInventoryRepository seatInventoryRepository = new SeatInventoryRepository();
        Show show = TestFixtures.createShowWithSeats(showRepository, seatInventoryRepository, 3,
                Instant.now().plus(Duration.ofHours(5)));

        FixedPricingStrategy strategy = new FixedPricingStrategy();

        BigDecimal silverPrice = strategy.priceFor(show, SeatCategory.SILVER, Instant.now());
        BigDecimal platinumPrice = strategy.priceFor(show, SeatCategory.PLATINUM, Instant.now());

        // Base price is 100 for every category in the fixture; multipliers differ (1.0 vs 2.25).
        assertEquals(0, new BigDecimal("100.00").compareTo(silverPrice));
        assertEquals(0, new BigDecimal("225.00").compareTo(platinumPrice));
    }

    @Test
    void dynamicPricingSurgesAsShowtimeApproaches() {
        ShowRepository showRepository = new ShowRepository();
        SeatInventoryRepository seatInventoryRepository = new SeatInventoryRepository();
        Instant showStart = Instant.now().plus(Duration.ofHours(6));
        Show show = TestFixtures.createShowWithSeats(showRepository, seatInventoryRepository, 3, showStart);

        DynamicPricingStrategy strategy = new DynamicPricingStrategy(seatInventoryRepository);

        BigDecimal priceFarFromShowtime = strategy.priceFor(show, SeatCategory.SILVER, Instant.now());
        BigDecimal priceNearShowtime = strategy.priceFor(show, SeatCategory.SILVER, showStart.minus(Duration.ofMinutes(30)));

        assertTrue(priceNearShowtime.compareTo(priceFarFromShowtime) > 0,
                "Price closer to showtime should be at least as high as price far from showtime");
    }

    @Test
    void dynamicPricingSurgesAsOccupancyIncreases() {
        ShowRepository showRepository = new ShowRepository();
        SeatInventoryRepository seatInventoryRepository = new SeatInventoryRepository();
        Show show = TestFixtures.createShowWithSeats(showRepository, seatInventoryRepository, 10,
                Instant.now().plus(Duration.ofDays(2))); // far from showtime, isolates occupancy effect

        DynamicPricingStrategy strategy = new DynamicPricingStrategy(seatInventoryRepository);
        BigDecimal priceBeforeBooking = strategy.priceFor(show, SeatCategory.SILVER, Instant.now());

        // Book out most of the show to drive occupancy up.
        for (int i = 1; i <= 9; i++) {
            String seatId = TestFixtures.seatIdByIndex(show, i);
            var showSeat = seatInventoryRepository.findShowSeat(show.getId(), seatId).orElseThrow();
            showSeat.tryLock("lock-" + i, "user-" + i, Long.MAX_VALUE, 0L);
            showSeat.confirm("lock-" + i, 1L);
        }

        BigDecimal priceAfterBooking = strategy.priceFor(show, SeatCategory.SILVER, Instant.now());

        assertTrue(priceAfterBooking.compareTo(priceBeforeBooking) > 0,
                "Price should rise as the show fills up");
    }
}
