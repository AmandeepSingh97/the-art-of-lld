package lld.parkinglot.model;

import lld.parkinglot.enums.VehicleType;

import java.util.Objects;

/**
 * Immutable value object identifying a vehicle by its license plate.
 * Equality is by license plate only: two {@code Vehicle} instances
 * constructed independently for the same plate must be considered the same
 * vehicle (this matters for "is this vehicle already parked?" checks).
 */
public final class Vehicle {

    private final String licensePlate;
    private final VehicleType type;

    public Vehicle(String licensePlate, VehicleType type) {
        this.licensePlate = Objects.requireNonNull(licensePlate, "licensePlate").trim().toUpperCase();
        this.type = Objects.requireNonNull(type, "type");
        if (this.licensePlate.isEmpty()) {
            throw new IllegalArgumentException("licensePlate must not be blank");
        }
    }

    public String getLicensePlate() {
        return licensePlate;
    }

    public VehicleType getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Vehicle)) return false;
        Vehicle vehicle = (Vehicle) o;
        return licensePlate.equals(vehicle.licensePlate);
    }

    @Override
    public int hashCode() {
        return licensePlate.hashCode();
    }

    @Override
    public String toString() {
        return type + "[" + licensePlate + "]";
    }
}
