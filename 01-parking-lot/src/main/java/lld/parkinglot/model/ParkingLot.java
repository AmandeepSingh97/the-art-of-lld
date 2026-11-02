package lld.parkinglot.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Aggregate root describing the static structure of one physical parking
 * facility: its name and the ordered list of floors it contains. Built once
 * at startup (see {@code ParkingLotBuilder} usage in the demo / tests) and
 * treated as effectively immutable thereafter - growing the garage (adding a
 * floor at runtime) is an out-of-scope production extension, not something
 * this aggregate needs to support concurrently.
 */
public final class ParkingLot {

    private final String name;
    private final List<Floor> floors;

    public ParkingLot(String name, List<Floor> floors) {
        this.name = Objects.requireNonNull(name, "name");
        this.floors = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(floors, "floors")));
    }

    public String getName() {
        return name;
    }

    public List<Floor> getFloors() {
        return floors;
    }

    public int totalSpots() {
        return floors.stream().mapToInt(f -> f.getSpots().size()).sum();
    }
}
