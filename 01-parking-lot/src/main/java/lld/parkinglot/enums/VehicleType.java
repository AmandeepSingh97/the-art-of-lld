package lld.parkinglot.enums;

/**
 * The categories of vehicles the parking lot accepts. Ordering is intentional:
 * declaration order is smallest-footprint to largest-footprint, which the
 * allocation logic in {@code SpotSize#canFit(VehicleType)} relies on.
 */
public enum VehicleType {
    MOTORCYCLE,
    CAR,
    BUS_OR_TRUCK
}
