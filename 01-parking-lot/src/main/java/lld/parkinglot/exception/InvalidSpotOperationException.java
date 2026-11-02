package lld.parkinglot.exception;

/**
 * Thrown when an admin or system operation attempts an illegal state
 * transition on a {@code ParkingSpot}, e.g. taking an occupied spot out of
 * service, or freeing a spot that is not currently occupied.
 */
public class InvalidSpotOperationException extends ParkingLotException {
    public InvalidSpotOperationException(String message) {
        super(message);
    }
}
