package lld.fooddelivery.state;

import lld.fooddelivery.domain.Order;
import lld.fooddelivery.domain.OrderStatus;
import lld.fooddelivery.exception.InvalidOrderStateTransitionException;

/**
 * State pattern contract for an {@link Order}'s lifecycle.
 *
 * <p>Each concrete state is a stateless singleton (held as a constant on {@link Order}) that
 * knows exactly which transitions are legal from it. Rather than one giant
 * {@code switch (status)} block scattered across the service layer — which grows unboundedly
 * and is easy to get subtly wrong as new statuses are added — each state class is a small,
 * independently reviewable unit: "what can happen to an order while it is PREPARING" lives
 * entirely in {@link PreparingState}.
 *
 * <p>Default methods throw {@link InvalidOrderStateTransitionException} for any transition a
 * concrete state does not explicitly override, so adding a new terminal-ish state (e.g.
 * {@link RejectedState}) automatically rejects every action by default — a deliberate
 * fail-closed design so a missing override can never silently allow an illegal transition.
 */
public interface OrderState {

    OrderStatus status();

    default OrderState accept(Order order) {
        throw invalid(order, "accept");
    }

    default OrderState reject(Order order, String reason) {
        throw invalid(order, "reject");
    }

    default OrderState startPreparing(Order order) {
        throw invalid(order, "startPreparing");
    }

    default OrderState markReadyForPickup(Order order) {
        throw invalid(order, "markReadyForPickup");
    }

    default OrderState pickUp(Order order) {
        throw invalid(order, "pickUp");
    }

    default OrderState deliver(Order order) {
        throw invalid(order, "deliver");
    }

    default OrderState cancel(Order order, String reason) {
        throw invalid(order, "cancel");
    }

    /**
     * Whether a delivery partner may be reserved for this order while it sits in this state.
     * Only {@code ACCEPTED} and {@code PREPARING} allow this; in particular {@code PLACED}
     * (not yet accepted) and any terminal/rejected/cancelled state must refuse, which is what
     * prevents the classic race of "restaurant rejects while a partner is mid-assignment".
     */
    default boolean allowsDeliveryPartnerAssignment() {
        return false;
    }

    private InvalidOrderStateTransitionException invalid(Order order, String action) {
        return new InvalidOrderStateTransitionException(status(), action);
    }
}
