package lld.parkinglot.exception;

/** Thrown when a ticket id does not resolve to an active, known ticket. */
public class InvalidTicketException extends ParkingLotException {
    public InvalidTicketException(String message) {
        super(message);
    }
}
