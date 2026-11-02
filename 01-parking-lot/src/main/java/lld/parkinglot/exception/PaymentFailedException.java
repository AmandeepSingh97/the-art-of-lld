package lld.parkinglot.exception;

/** Thrown when a payment attempt is declined or otherwise fails to settle. */
public class PaymentFailedException extends ParkingLotException {
    public PaymentFailedException(String message) {
        super(message);
    }
}
