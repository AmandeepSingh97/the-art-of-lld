package lld.fooddelivery.state;

import lld.fooddelivery.domain.Order;
import lld.fooddelivery.domain.OrderStatus;

/**
 * Kitchen is actively cooking. This is the point of no (self-service) return for the customer:
 * cancellation now requires a support/CS-mediated flow (modelled here as simply disallowed —
 * see README "Domain Invariants" for the business rationale). Delivery-partner assignment is
 * still permitted/expected to be in flight or already completed.
 */
public final class PreparingState implements OrderState {

    public static final PreparingState INSTANCE = new PreparingState();

    private PreparingState() {
    }

    @Override
    public OrderStatus status() {
        return OrderStatus.PREPARING;
    }

    @Override
    public OrderState markReadyForPickup(Order order) {
        return ReadyForPickupState.INSTANCE;
    }

    @Override
    public boolean allowsDeliveryPartnerAssignment() {
        return true;
    }
}
