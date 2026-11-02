package lld.parkinglot.service;

import lld.parkinglot.enums.PaymentMethod;
import lld.parkinglot.model.Gate;

import java.util.Objects;

/** Thin, stateless wrapper binding one physical exit {@link Gate} to the shared {@link ParkingLotService}. */
public final class ExitGateService {

    private final Gate gate;
    private final ParkingLotService parkingLotService;

    public ExitGateService(Gate gate, ParkingLotService parkingLotService) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.parkingLotService = Objects.requireNonNull(parkingLotService, "parkingLotService");
    }

    /** Processes one vehicle leaving through this gate, returning its settled receipt. */
    public ExitReceipt processExit(String ticketId, PaymentMethod paymentMethod) {
        return parkingLotService.unparkVehicle(ticketId, gate, paymentMethod);
    }

    public Gate getGate() {
        return gate;
    }
}
