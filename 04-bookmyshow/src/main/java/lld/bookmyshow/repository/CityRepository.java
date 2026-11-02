package lld.bookmyshow.repository;

import lld.bookmyshow.model.City;

public class CityRepository extends InMemoryRepository<String, City> {
    public CityRepository() {
        super(City::getId);
    }
}
