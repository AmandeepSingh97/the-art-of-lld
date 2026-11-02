package lld.bookmyshow.repository;

import lld.bookmyshow.model.Theatre;

import java.util.List;

public class TheatreRepository extends InMemoryRepository<String, Theatre> {
    public TheatreRepository() {
        super(Theatre::getId);
    }

    public List<Theatre> findByCityId(String cityId) {
        return findAll().stream()
                .filter(t -> t.getCity().getId().equals(cityId))
                .toList();
    }
}
