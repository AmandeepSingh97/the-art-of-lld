package lld.parkinglot.service;

import lld.parkinglot.model.Gate;
import lld.parkinglot.model.Ticket;
import lld.parkinglot.model.Vehicle;

import java.util.Objects;

/**
 * Thin, stateless wrapper binding one physical {@link Gate} to the shared
 * {@link ParkingLotService}. Modelled as its own class (rather than callers
 * invoking {@code ParkingLotService} directly with a gate id each time) so
 * that gate-specific concerns - e.g. a boom-barrier hardware driver, gate
 * open/close signalling, or per-gate metrics - have an obvious place to live
 * without bloating the facade. Multiple {@code EntryGateService} instances
 * (one per physical gate) safely share one {@code ParkingLotService}.
 */
public final class EntryGateService {

    private final Gate gate;
    private final ParkingLotService parkingLotService;

    public EntryGateService(Gate gate, ParkingLotService parkingLotService) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.parkingLotService = Objects.requireNonNull(parkingLotService, "parkingLotService");
    }

    /** Processes one vehicle arriving at this gate, returning the printed/issued ticket. */
    public Ticket processEntry(Vehicle vehicle) {
        return parkingLotService.parkVehicle(vehicle, gate);
    }

    public Gate getGate() {
        return gate;
    }
}
