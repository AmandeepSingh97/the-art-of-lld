package lld.fooddelivery;

import lld.fooddelivery.domain.Money;
import lld.fooddelivery.domain.Order;
import lld.fooddelivery.domain.OrderItem;
import lld.fooddelivery.domain.OrderStatus;
import lld.fooddelivery.exception.InvalidOrderStateTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Order State pattern enforces exactly the valid transitions documented in
 * {@code OrderStatus} and rejects everything else.
 */
class OrderStateMachineTest {

    private Order order;

    @BeforeEach
    void setUp() {
        order = newOrder();
    }

    private static Order newOrder() {
        List<OrderItem> items = List.of(new OrderItem("item-1", "Pizza", Money.of("300.00"), 1));
        return new Order("order-1", "cust-1", "rest-1", items, Money.of("300.00"), Money.of("300.00"));
    }

    @Test
    void newOrderStartsInPlacedStatus() {
        assertEquals(OrderStatus.PLACED, order.status());
    }

    @Test
    void happyPathTransitionsThroughEveryStatusInOrder() {
        order.accept();
        assertEquals(OrderStatus.ACCEPTED, order.status());
        order.startPreparing();
        assertEquals(OrderStatus.PREPARING, order.status());
        order.markReadyForPickup();
        assertEquals(OrderStatus.READY_FOR_PICKUP, order.status());
        order.pickUp();
        assertEquals(OrderStatus.PICKED_UP, order.status());
        order.deliver();
        assertEquals(OrderStatus.DELIVERED, order.status());
    }

    @Test
    void restaurantCanRejectAPlacedOrder() {
        order.reject("kitchen closed");
        assertEquals(OrderStatus.REJECTED, order.status());
        assertEquals("kitchen closed", order.terminationReason());
    }

    @Test
    void customerCanCancelBeforePreparingStarts() {
        order.accept();
        order.cancel("changed my mind");
        assertEquals(OrderStatus.CANCELLED, order.status());
    }

    @Test
    void cancelAfterPreparingStartedIsRejected() {
        order.accept();
        order.startPreparing();
        assertThrows(InvalidOrderStateTransitionException.class, () -> order.cancel("too late"));
        // The order must remain in PREPARING: a failed/rejected transition must never
        // partially mutate state.
        assertEquals(OrderStatus.PREPARING, order.status());
    }

    @Test
    void cannotPickUpAnOrderThatHasNotBeenAcceptedOrPrepared() {
        InvalidOrderStateTransitionException ex =
                assertThrows(InvalidOrderStateTransitionException.class, () -> order.pickUp());
        assertEquals(OrderStatus.PLACED, ex.currentStatus());
        assertEquals("pickUp", ex.attemptedAction());
    }

    @Test
    void cannotAcceptAnAlreadyRejectedOrder() {
        order.reject("no capacity");
        assertThrows(InvalidOrderStateTransitionException.class, order::accept);
    }

    @Test
    void cannotDeliverBeforePickup() {
        order.accept();
        order.startPreparing();
        order.markReadyForPickup();
        assertThrows(InvalidOrderStateTransitionException.class, order::deliver);
    }

    @Test
    void deliveredIsTerminalAndRejectsFurtherTransitions() {
        order.accept();
        order.startPreparing();
        order.markReadyForPickup();
        order.pickUp();
        order.deliver();
        assertThrows(InvalidOrderStateTransitionException.class, () -> order.cancel("too late"));
        assertThrows(InvalidOrderStateTransitionException.class, order::deliver);
    }

    @Test
    void deliveryPartnerAssignmentIsOnlyAllowedWhileAcceptedOrPreparing() {
        assertFalse(order.assignDeliveryPartner("rider-1"), "PLACED orders must not accept assignment");
        order.accept();
        assertTrue(order.assignDeliveryPartner("rider-1"), "ACCEPTED orders must accept assignment");
        assertEquals("rider-1", order.deliveryPartnerId());
    }

    @Test
    void assignmentIsRejectedOnceOrderIsRejected() {
        order.reject("closed");
        assertFalse(order.assignDeliveryPartner("rider-1"));
    }
}
