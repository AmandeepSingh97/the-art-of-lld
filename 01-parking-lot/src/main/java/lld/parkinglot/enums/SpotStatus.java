package lld.parkinglot.enums;

/**
 * Lifecycle states of a {@code ParkingSpot}. Valid transitions are enforced by
 * {@code ParkingSpot} itself (see its state-machine comment), not by callers.
 *
 * <pre>
 *   AVAILABLE --reserve--> RESERVED --occupy--> OCCUPIED --vacate--> AVAILABLE
 *   AVAILABLE --takeOutOfService--> OUT_OF_SERVICE --bringBackInService--> AVAILABLE
 *   RESERVED  --release (allocation abandoned)--> AVAILABLE
 * </pre>
 */
public enum SpotStatus {
    AVAILABLE,
    RESERVED,
    OCCUPIED,
    OUT_OF_SERVICE
}
