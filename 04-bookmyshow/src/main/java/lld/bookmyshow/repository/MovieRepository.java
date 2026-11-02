package lld.bookmyshow.repository;

import lld.bookmyshow.model.Movie;

public class MovieRepository extends InMemoryRepository<String, Movie> {
    public MovieRepository() {
        super(Movie::getId);
    }
}
