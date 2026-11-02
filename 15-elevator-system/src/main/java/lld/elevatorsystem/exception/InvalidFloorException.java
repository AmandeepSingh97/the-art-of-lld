package lld.elevatorsystem.exception;

/**
 * Thrown when a floor number supplied by a caller falls outside the
 * building's configured floor range, or a hall call is submitted with
 * {@code Direction.IDLE}.
 */
public class InvalidFloorException extends RuntimeException {

    public InvalidFloorException(String message) {
        super(message);
    }
}
