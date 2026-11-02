package lld.bookmyshow.model;

import lld.bookmyshow.enums.SeatCategory;

import java.util.Objects;

/**
 * An immutable physical seat within a {@link Screen}: a row/number pair and
 * a pricing category. Seat itself carries <b>no booking state</b> — that
 * belongs to {@code ShowSeat}, which is scoped to a specific show.
 *
 * <p>Immutable by design: a seat's physical attributes never change after
 * the screen is laid out, so there is no synchronization concern here at
 * all — it is safe to share across threads without any locking.
 */
public final class Seat {
    private final String id;
    private final String screenId;
    private final String row;
    private final int number;
    private final SeatCategory category;

    public Seat(String id, String screenId, String row, int number, SeatCategory category) {
        this.id = Objects.requireNonNull(id, "id");
        this.screenId = Objects.requireNonNull(screenId, "screenId");
        this.row = Objects.requireNonNull(row, "row");
        this.number = number;
        this.category = Objects.requireNonNull(category, "category");
    }

    public String getId() {
        return id;
    }

    public String getScreenId() {
        return screenId;
    }

    public String getRow() {
        return row;
    }

    public int getNumber() {
        return number;
    }

    public SeatCategory getCategory() {
        return category;
    }

    public String getLabel() {
        return row + number;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Seat seat)) return false;
        return id.equals(seat.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Seat{" + getLabel() + ", category=" + category + "}";
    }
}
