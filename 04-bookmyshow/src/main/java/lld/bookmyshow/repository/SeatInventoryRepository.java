package lld.bookmyshow.repository;

import lld.bookmyshow.model.Screen;
import lld.bookmyshow.model.Seat;
import lld.bookmyshow.model.Show;
import lld.bookmyshow.model.ShowSeat;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the per-show seat inventory: for every {@link Show}, one
 * {@link ShowSeat} per physical {@link Seat} on that show's screen.
 *
 * <p>Structured as {@code Map<showId, Map<seatId, ShowSeat>>} using nested
 * {@link ConcurrentHashMap}s. The outer map is populated once per show at
 * show-creation time ({@link #initializeInventory}); after that, only the
 * inner per-seat {@code ShowSeat} objects mutate (via their own lock-free
 * CAS operations — see {@link ShowSeat}), so no map-level synchronization
 * is ever required on the hot booking path, only on cold show setup.
 */
public class SeatInventoryRepository {

    private final ConcurrentHashMap<String, Map<String, ShowSeat>> inventoryByShow = new ConcurrentHashMap<>();

    /** Called once when a {@link Show} is scheduled, laying out one {@link ShowSeat} per screen seat. */
    public void initializeInventory(Show show) {
        Screen screen = show.getScreen();
        Map<String, ShowSeat> seatMap = new ConcurrentHashMap<>();
        for (Seat seat : screen.getSeats()) {
            seatMap.put(seat.getId(), new ShowSeat(show.getId(), seat));
        }
        inventoryByShow.put(show.getId(), seatMap);
    }

    public Optional<ShowSeat> findShowSeat(String showId, String seatId) {
        Map<String, ShowSeat> seatMap = inventoryByShow.get(showId);
        if (seatMap == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(seatMap.get(seatId));
    }

    public List<ShowSeat> findAllForShow(String showId) {
        Map<String, ShowSeat> seatMap = inventoryByShow.get(showId);
        return seatMap == null ? List.of() : List.copyOf(seatMap.values());
    }

    public Collection<String> allShowIds() {
        return List.copyOf(inventoryByShow.keySet());
    }
}
