package lld.elevatorsystem.exception;

/**
 * Thrown when a cabin request (a button press from inside a cabin) is made
 * against an elevator that is in {@code MAINTENANCE} and therefore cannot
 * accept new stops.
 */
public class ElevatorOutOfServiceException extends RuntimeException {

    public ElevatorOutOfServiceException(String message) {
        super(message);
    }
}
