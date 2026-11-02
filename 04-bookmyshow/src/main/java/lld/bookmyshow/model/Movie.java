package lld.bookmyshow.model;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** A movie that can be scheduled across many {@link Show}s. Immutable. */
public final class Movie {
    private final String id;
    private final String title;
    private final Duration duration;
    private final String language;
    private final List<String> genres;

    public Movie(String id, String title, Duration duration, String language, List<String> genres) {
        this.id = Objects.requireNonNull(id, "id");
        this.title = Objects.requireNonNull(title, "title");
        this.duration = Objects.requireNonNull(duration, "duration");
        this.language = Objects.requireNonNull(language, "language");
        this.genres = List.copyOf(genres);
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Duration getDuration() {
        return duration;
    }

    public String getLanguage() {
        return language;
    }

    public List<String> getGenres() {
        return genres;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Movie movie)) return false;
        return id.equals(movie.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Movie{title='" + title + "', language='" + language + "'}";
    }
}
