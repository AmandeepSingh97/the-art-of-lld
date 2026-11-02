package lld.parkinglot.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Physical size classes for a {@code ParkingSpot}. A spot of a given size can
 * host any vehicle whose footprint is less than or equal to that size -
 * matching real garages where a motorcycle may use a compact car spot but a
 * bus cannot use a compact spot.
 *
 * <p>The compatibility table is centralised here (rather than scattered as
 * if/else checks in the allocation strategy) so that adding a new vehicle
 * type or spot size is a single, localised edit - this is the Open/Closed
 * Principle applied to a small lookup table instead of a chain of conditionals.
 */
public enum SpotSize {
    SMALL(EnumSet.of(VehicleType.MOTORCYCLE)),
    MEDIUM(EnumSet.of(VehicleType.MOTORCYCLE, VehicleType.CAR)),
    LARGE(EnumSet.of(VehicleType.MOTORCYCLE, VehicleType.CAR, VehicleType.BUS_OR_TRUCK));

    private final Set<VehicleType> compatibleVehicleTypes;

    SpotSize(Set<VehicleType> compatibleVehicleTypes) {
        this.compatibleVehicleTypes = compatibleVehicleTypes;
    }

    /**
     * @return true if a vehicle of {@code type} may legally occupy a spot of this size.
     */
    public boolean canFit(VehicleType type) {
        return compatibleVehicleTypes.contains(type);
    }
}
