package lld.parkinglot.model;

import lld.parkinglot.enums.SpotSize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Descriptive entity representing one level of the garage and the spots it
 * contains. {@code Floor} itself holds no allocation logic or mutable
 * concurrency state - it is a read-mostly structural grouping used for
 * reporting (occupancy per floor) and to seed the repository's allocation
 * indices at startup. All concurrent mutation happens on {@link ParkingSpot}.
 */
public final class Floor {

    private final int floorNumber;
    private final List<ParkingSpot> spots;

    public Floor(int floorNumber, List<ParkingSpot> spots) {
        this.floorNumber = floorNumber;
        this.spots = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(spots, "spots")));
    }

    public int getFloorNumber() {
        return floorNumber;
    }

    public List<ParkingSpot> getSpots() {
        return spots;
    }

    public long countBySize(SpotSize size) {
        return spots.stream().filter(s -> s.getSize() == size).count();
    }

    @Override
    public String toString() {
        return "Floor " + floorNumber + " (" + spots.size() + " spots)";
    }
}
