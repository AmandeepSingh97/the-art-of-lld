package lld.bookmyshow.repository;

import lld.bookmyshow.model.Show;

import java.time.Instant;
import java.util.List;

public class ShowRepository extends InMemoryRepository<String, Show> {
    public ShowRepository() {
        super(Show::getId);
    }

    /** Search shows by movie, theatre's city, and a time window — the core discovery query. */
    public List<Show> search(String movieId, String cityId, Instant from, Instant to) {
        return findAll().stream()
                .filter(s -> movieId == null || s.getMovie().getId().equals(movieId))
                .filter(s -> cityId == null || s.getScreen().getTheatre().getCity().getId().equals(cityId))
                .filter(s -> !s.getStartTime().isBefore(from) && !s.getStartTime().isAfter(to))
                .toList();
    }
}
