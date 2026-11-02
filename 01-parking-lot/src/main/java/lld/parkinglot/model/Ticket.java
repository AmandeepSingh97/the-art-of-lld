package lld.parkinglot.model;

import lld.parkinglot.enums.TicketStatus;
import lld.parkinglot.util.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * A parking ticket issued at entry and closed at exit. A ticket is mutable
 * only through its owning {@code TicketRepository}/service layer, and only
 * ever by the single thread handling that particular exit request (tickets
 * are looked up and closed under a per-ticket operation in
 * {@code ParkingLotService}, see its class-level concurrency note) - so no
 * internal synchronization is needed on this class itself.
 */
public final class Ticket {

    private final String id;
    private final Vehicle vehicle;
    private final ParkingSpot spot;
    private final String entryGateId;
    private final Instant entryTime;

    private volatile TicketStatus status;
    private volatile String exitGateId;
    private volatile Instant exitTime;
    private volatile Money feeCharged;

    public Ticket(String id, Vehicle vehicle, ParkingSpot spot, String entryGateId, Instant entryTime) {
        this.id = Objects.requireNonNull(id, "id");
        this.vehicle = Objects.requireNonNull(vehicle, "vehicle");
        this.spot = Objects.requireNonNull(spot, "spot");
        this.entryGateId = Objects.requireNonNull(entryGateId, "entryGateId");
        this.entryTime = Objects.requireNonNull(entryTime, "entryTime");
        this.status = TicketStatus.ACTIVE;
    }

    public String getId() {
        return id;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public ParkingSpot getSpot() {
        return spot;
    }

    public String getEntryGateId() {
        return entryGateId;
    }

    public Instant getEntryTime() {
        return entryTime;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public String getExitGateId() {
        return exitGateId;
    }

    public Instant getExitTime() {
        return exitTime;
    }

    public Money getFeeCharged() {
        return feeCharged;
    }

    /** Closes the ticket once payment has succeeded. Package-private-by-convention: only called from the service layer. */
    public void close(String exitGateId, Instant exitTime, Money feeCharged) {
        if (this.status != TicketStatus.ACTIVE) {
            throw new IllegalStateException("Ticket " + id + " is not ACTIVE (status=" + this.status + ")");
        }
        this.exitGateId = Objects.requireNonNull(exitGateId, "exitGateId");
        this.exitTime = Objects.requireNonNull(exitTime, "exitTime");
        this.feeCharged = Objects.requireNonNull(feeCharged, "feeCharged");
        this.status = TicketStatus.PAID_AND_CLOSED;
    }

    public void markLost() {
        this.status = TicketStatus.LOST;
    }

    @Override
    public String toString() {
        return "Ticket[" + id + ", vehicle=" + vehicle + ", spot=" + spot.getId() + ", status=" + status + "]";
    }
}
