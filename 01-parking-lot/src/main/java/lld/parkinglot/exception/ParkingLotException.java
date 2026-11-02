package lld.parkinglot.exception;

/** Base, unchecked exception type for all parking-lot domain errors. */
public class ParkingLotException extends RuntimeException {
    public ParkingLotException(String message) {
        super(message);
    }

    public ParkingLotException(String message, Throwable cause) {
        super(message, cause);
    }
}
