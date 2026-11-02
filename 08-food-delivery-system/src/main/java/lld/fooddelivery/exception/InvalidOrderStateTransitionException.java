package lld.fooddelivery.exception;

import lld.fooddelivery.domain.OrderStatus;

/**
 * Thrown when an operation attempts an order-status transition that is not legal from the
 * order's current status (e.g. trying to {@code pickUp()} an order still {@code PLACED}, or
 * trying to {@code cancel()} an order that is already {@code PREPARING}). This is the
 * mechanism that makes illegal state transitions structurally impossible to apply: every
 * concrete {@code OrderState} only implements the transitions it legally supports, and the
 * {@code OrderState} interface's default methods throw this exception for everything else.
 */
public class InvalidOrderStateTransitionException extends RuntimeException {

    private final OrderStatus currentStatus;
    private final String attemptedAction;

    public InvalidOrderStateTransitionException(OrderStatus currentStatus, String attemptedAction) {
        super("Cannot perform '" + attemptedAction + "' while order is in status " + currentStatus);
        this.currentStatus = currentStatus;
        this.attemptedAction = attemptedAction;
    }

    public OrderStatus currentStatus() {
        return currentStatus;
    }

    public String attemptedAction() {
        return attemptedAction;
    }
}
