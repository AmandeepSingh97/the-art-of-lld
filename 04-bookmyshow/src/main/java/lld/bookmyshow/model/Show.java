package lld.bookmyshow.model;

import lld.bookmyshow.enums.SeatCategory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A scheduled screening: one {@link Movie} playing on one {@link Screen} at
 * one {@code startTime}. Carries the base price per {@link SeatCategory}
 * used by the pricing strategy.
 *
 * <p>Immutable once created. Per-show, per-seat <em>availability</em> state
 * lives in {@code ShowSeat}, not here — Show itself has no mutable fields,
 * so it needs no synchronization.
 */
public final class Show {
    private final String id;
    private final Movie movie;
    private final Screen screen;
    private final Instant startTime;
    private final Instant endTime;
    private final Map<SeatCategory, BigDecimal> basePriceByCategory;

    public Show(String id, Movie movie, Screen screen, Instant startTime, Instant endTime,
                Map<SeatCategory, BigDecimal> basePriceByCategory) {
        this.id = Objects.requireNonNull(id, "id");
        this.movie = Objects.requireNonNull(movie, "movie");
        this.screen = Objects.requireNonNull(screen, "screen");
        this.startTime = Objects.requireNonNull(startTime, "startTime");
        this.endTime = Objects.requireNonNull(endTime, "endTime");
        this.basePriceByCategory = Map.copyOf(basePriceByCategory);
    }

    public String getId() {
        return id;
    }

    public Movie getMovie() {
        return movie;
    }

    public Screen getScreen() {
        return screen;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public BigDecimal basePriceFor(SeatCategory category) {
        BigDecimal price = basePriceByCategory.get(category);
        if (price == null) {
            throw new IllegalArgumentException("No base price configured for category " + category);
        }
        return price;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Show show)) return false;
        return id.equals(show.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Show{movie=" + movie.getTitle() + ", screen=" + screen.getName() + ", start=" + startTime + "}";
    }
}
