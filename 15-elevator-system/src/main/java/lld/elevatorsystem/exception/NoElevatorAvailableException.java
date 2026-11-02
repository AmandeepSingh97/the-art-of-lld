package lld.elevatorsystem.exception;

/**
 * Thrown by the {@link lld.elevatorsystem.scheduler.SchedulingStrategy} when
 * every elevator in the building is in {@code MAINTENANCE} / out of service
 * and no car can be assigned to a hall call.
 */
public class NoElevatorAvailableException extends RuntimeException {

    public NoElevatorAvailableException(String message) {
        super(message);
    }
}
