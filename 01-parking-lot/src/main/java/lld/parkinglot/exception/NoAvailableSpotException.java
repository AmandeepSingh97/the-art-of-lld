package lld.parkinglot.exception;

/** Thrown when no compatible, in-service spot can be found/reserved for a vehicle. */
public class NoAvailableSpotException extends ParkingLotException {
    public NoAvailableSpotException(String message) {
        super(message);
    }
}
