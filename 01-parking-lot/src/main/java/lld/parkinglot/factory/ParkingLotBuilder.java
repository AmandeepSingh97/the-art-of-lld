package lld.parkinglot.factory;

import lld.parkinglot.enums.SpotSize;
import lld.parkinglot.model.Floor;
import lld.parkinglot.model.ParkingLot;
import lld.parkinglot.model.ParkingSpot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builder Pattern: assembles a {@link ParkingLot} (and the {@link Floor}/
 * {@link ParkingSpot} graph it owns) from a simple per-floor "how many
 * spots of each size" specification, hiding id-generation and ordering
 * details from callers. Chosen over a telescoping {@code ParkingLot}
 * constructor because the number of (floor, size, count) combinations is
 * unbounded and most callers only care about totals per floor, not
 * individual spot wiring.
 */
public final class ParkingLotBuilder {

    private final String name;
    private final List<FloorSpec> floorSpecs = new ArrayList<>();

    private ParkingLotBuilder(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public static ParkingLotBuilder named(String name) {
        return new ParkingLotBuilder(name);
    }

    /**
     * Adds one floor, specified as an ordered map of spot size -&gt; count,
     * e.g. {@code Map.of(SpotSize.SMALL, 5, SpotSize.MEDIUM, 20, SpotSize.LARGE, 3)}.
     */
    public ParkingLotBuilder withFloor(int floorNumber, Map<SpotSize, Integer> spotCountsBySize) {
        floorSpecs.add(new FloorSpec(floorNumber, new LinkedHashMap<>(spotCountsBySize)));
        return this;
    }

    public ParkingLot build() {
        List<Floor> floors = new ArrayList<>();
        for (FloorSpec spec : floorSpecs) {
            List<ParkingSpot> spots = new ArrayList<>();
            int spotNumber = 1;
            for (Map.Entry<SpotSize, Integer> entry : spec.spotCountsBySize.entrySet()) {
                for (int i = 0; i < entry.getValue(); i++) {
                    String id = "F" + spec.floorNumber + "-S" + spotNumber;
                    spots.add(new ParkingSpot(id, spec.floorNumber, spotNumber, entry.getKey()));
                    spotNumber++;
                }
            }
            floors.add(new Floor(spec.floorNumber, spots));
        }
        return new ParkingLot(name, floors);
    }

    private static final class FloorSpec {
        final int floorNumber;
        final Map<SpotSize, Integer> spotCountsBySize;

        FloorSpec(int floorNumber, Map<SpotSize, Integer> spotCountsBySize) {
            this.floorNumber = floorNumber;
            this.spotCountsBySize = spotCountsBySize;
        }
    }
}
