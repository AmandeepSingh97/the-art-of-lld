package lld.bookmyshow.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A physical theatre (cinema) located in a {@link City}, composed of one or
 * more {@link Screen}s. Theatre <b>owns</b> its screens (composition): a
 * screen cannot exist without a theatre and is created through
 * {@link #addScreen(Screen)}.
 */
public final class Theatre {
    private final String id;
    private final String name;
    private final City city;
    private final List<Screen> screens = new ArrayList<>();

    public Theatre(String id, String name, City city) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.city = Objects.requireNonNull(city, "city");
    }

    public void addScreen(Screen screen) {
        screens.add(Objects.requireNonNull(screen, "screen"));
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public City getCity() {
        return city;
    }

    public List<Screen> getScreens() {
        return Collections.unmodifiableList(screens);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Theatre theatre)) return false;
        return id.equals(theatre.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
