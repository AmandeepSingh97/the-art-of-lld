package lld.bookmyshow.service;

import lld.bookmyshow.enums.SeatStatus;
import lld.bookmyshow.exception.ShowNotFoundException;
import lld.bookmyshow.model.Show;
import lld.bookmyshow.model.ShowSeat;
import lld.bookmyshow.repository.SeatInventoryRepository;
import lld.bookmyshow.repository.ShowRepository;
import lld.bookmyshow.strategy.PricingStrategy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Read-side service for browsing: searching shows and viewing live seat
 * availability with current pricing. Deliberately has no write operations —
 * all mutation goes through {@code BookingService} / {@code SeatLockService}.
 */
public class CatalogService {

    private final ShowRepository showRepository;
    private final SeatInventoryRepository seatInventoryRepository;
    private final PricingStrategy pricingStrategy;
    private final Clock clock;

    public CatalogService(ShowRepository showRepository, SeatInventoryRepository seatInventoryRepository,
                           PricingStrategy pricingStrategy, Clock clock) {
        this.showRepository = showRepository;
        this.seatInventoryRepository = seatInventoryRepository;
        this.pricingStrategy = pricingStrategy;
        this.clock = clock;
    }

    /** Search shows, optionally filtered by movie and/or city, within a time window. */
    public List<Show> searchShows(String movieId, String cityId, Instant from, Instant to) {
        return showRepository.search(movieId, cityId, from, to);
    }

    public Show getShow(String showId) {
        return showRepository.findById(showId)
                .orElseThrow(() -> new ShowNotFoundException("No such show: " + showId));
    }

    /** Live per-seat availability + price for a show, as a user would see it on a seat map. */
    public List<SeatAvailabilityView> getSeatMap(String showId) {
        Show show = getShow(showId);
        Instant now = clock.instant();
        long nowMillis = clock.millis();
        return seatInventoryRepository.findAllForShow(showId).stream()
                .map(showSeat -> new SeatAvailabilityView(
                        showSeat.getSeat().getId(),
                        showSeat.getSeat().getLabel(),
                        showSeat.getCategory(),
                        showSeat.getEffectiveStatus(nowMillis),
                        pricingStrategy.priceFor(show, showSeat.getCategory(), now)))
                .toList();
    }

    /** Read-only view of one seat's availability and price, as shown in a seat-selection UI. */
    public record SeatAvailabilityView(String seatId, String label, lld.bookmyshow.enums.SeatCategory category,
                                        SeatStatus status, BigDecimal price) {
    }
}
