package lld.parkinglot.model;

import java.util.Objects;

/**
 * A physical entry or exit point of the lot. {@code floorNumber} is the
 * floor the gate physically opens onto, and is used by the nearest-spot
 * allocation strategy to order floors by proximity to this gate rather than
 * always scanning from floor 0 - a lot with gates on multiple floors (e.g. a
 * street-level gate and a rooftop gate) should prefer spots close to the
 * gate actually used.
 */
public final class Gate {

    public enum Kind { ENTRY, EXIT }

    private final String id;
    private final String name;
    private final int floorNumber;
    private final Kind kind;

    public Gate(String id, String name, int floorNumber, Kind kind) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.floorNumber = floorNumber;
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getFloorNumber() {
        return floorNumber;
    }

    public Kind getKind() {
        return kind;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Gate)) return false;
        Gate gate = (Gate) o;
        return id.equals(gate.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return kind + " Gate " + name + " (floor " + floorNumber + ")";
    }
}
