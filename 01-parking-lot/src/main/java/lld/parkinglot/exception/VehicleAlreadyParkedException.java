package lld.parkinglot.exception;

/** Thrown when an entry gate receives a vehicle that already holds an active ticket. */
public class VehicleAlreadyParkedException extends ParkingLotException {
    public VehicleAlreadyParkedException(String message) {
        super(message);
    }
}
