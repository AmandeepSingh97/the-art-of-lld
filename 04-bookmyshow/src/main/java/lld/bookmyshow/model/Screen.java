package lld.bookmyshow.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A single auditorium inside a {@link Theatre}. Owns the fixed physical
 * layout of {@link Seat}s. The layout does not change per-show; what
 * changes per-show is seat *availability*, modelled separately by
 * {@code ShowSeat} so the same physical seat can be simultaneously free for
 * one show and booked for another.
 */
public final class Screen {
    private final String id;
    private final String name;
    private final Theatre theatre;
    private final List<Seat> seats = new ArrayList<>();

    public Screen(String id, String name, Theatre theatre) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.theatre = Objects.requireNonNull(theatre, "theatre");
    }

    public void addSeat(Seat seat) {
        seats.add(Objects.requireNonNull(seat, "seat"));
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Theatre getTheatre() {
        return theatre;
    }

    public List<Seat> getSeats() {
        return Collections.unmodifiableList(seats);
    }

    public int capacity() {
        return seats.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Screen screen)) return false;
        return id.equals(screen.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
