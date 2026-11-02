package lld.bookmyshow;

import lld.bookmyshow.enums.SeatCategory;
import lld.bookmyshow.model.City;
import lld.bookmyshow.model.Movie;
import lld.bookmyshow.model.Screen;
import lld.bookmyshow.model.Seat;
import lld.bookmyshow.model.Show;
import lld.bookmyshow.model.Theatre;
import lld.bookmyshow.repository.SeatInventoryRepository;
import lld.bookmyshow.repository.ShowRepository;
import lld.bookmyshow.util.IdGenerator;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Shared test data builder: one small theatre/screen/show, wired into the given repositories. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static Show createShowWithSeats(ShowRepository showRepository, SeatInventoryRepository seatInventoryRepository,
                                            int seatCount, Instant startTime) {
        City city = new City(IdGenerator.uuid(), "TestCity");
        Theatre theatre = new Theatre(IdGenerator.uuid(), "TestTheatre", city);
        Screen screen = new Screen(IdGenerator.uuid(), "Screen 1", theatre);
        for (int i = 1; i <= seatCount; i++) {
            SeatCategory category = i % 3 == 0 ? SeatCategory.PLATINUM : i % 3 == 1 ? SeatCategory.SILVER : SeatCategory.GOLD;
            screen.addSeat(new Seat(IdGenerator.uuid(), screen.getId(), "A", i, category));
        }
        theatre.addScreen(screen);

        Movie movie = new Movie(IdGenerator.uuid(), "Test Movie", Duration.ofMinutes(120), "English", List.of("Drama"));
        Show show = new Show(IdGenerator.uuid(), movie, screen, startTime, startTime.plus(Duration.ofHours(2)),
                Map.of(SeatCategory.SILVER, BigDecimal.valueOf(100),
                        SeatCategory.GOLD, BigDecimal.valueOf(100),
                        SeatCategory.PLATINUM, BigDecimal.valueOf(100)));
        showRepository.save(show);
        seatInventoryRepository.initializeInventory(show);
        return show;
    }

    public static String seatIdByIndex(Show show, int oneBasedIndex) {
        return show.getScreen().getSeats().get(oneBasedIndex - 1).getId();
    }
}
