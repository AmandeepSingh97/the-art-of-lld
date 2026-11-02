# Sequence Diagrams — Key Flows

## 1. Order placement (checkout)

```mermaid
sequenceDiagram
    actor Customer
    participant CartService
    participant RestaurantService
    participant OrderService
    participant PriceCalculator as "PriceCalculator (Decorator chain)"
    participant PaymentService
    participant Order

    Customer->>CartService: addItem(custId, restId, itemId, qty)
    CartService->>RestaurantService: requireRestaurant(restId)
    CartService->>CartService: validate item available
    Customer->>OrderService: placeOrder(custId, pricingPipeline, ...)
    OrderService->>CartService: cartFor(custId)
    OrderService->>RestaurantService: requireRestaurant(restId)
    OrderService->>RestaurantService: re-validate every item still available
    Note right of OrderService: Closes the "added to cart,\nthen went out of stock" race
    OrderService->>PriceCalculator: calculate(PricingContext)
    PriceCalculator-->>OrderService: total
    OrderService->>Order: new Order(..., subtotal, total)
    OrderService->>PaymentService: charge(order)
    PaymentService-->>OrderService: Payment(SUCCESS)
    OrderService->>CartService: clear(custId)
    OrderService->>OrderService: scheduleAcceptanceTimeout(order)
    OrderService-->>Customer: Order(PLACED)
```

## 2. Restaurant acceptance + async delivery-partner assignment

```mermaid
sequenceDiagram
    actor Restaurant
    participant OrderService
    participant Order
    participant DeliveryAssignmentService
    participant Strategy as "NearestAvailablePartnerStrategy"
    participant Partner as "DeliveryPartner"

    Restaurant->>OrderService: acceptOrder(orderId)
    OrderService->>OrderService: cancelAcceptanceTimeout(orderId)
    OrderService->>Order: accept()
    Order->>Order: synchronized: state PLACED -> ACCEPTED
    Order-->>OrderService: (observers notified)
    OrderService->>DeliveryAssignmentService: assignPartnerFor(order, pickupLocation) [async]

    loop until reserved or no candidates left
        DeliveryAssignmentService->>Strategy: selectPartner(pickup, candidates)
        Strategy-->>DeliveryAssignmentService: nearest AVAILABLE partner
        DeliveryAssignmentService->>Partner: tryReserve() [CAS AVAILABLE->BUSY]
        alt CAS lost (raced by another order)
            DeliveryAssignmentService->>DeliveryAssignmentService: retry with next candidate
        else CAS won
            DeliveryAssignmentService->>Order: assignDeliveryPartner(partnerId)
            Order->>Order: synchronized: check state.allowsDeliveryPartnerAssignment()
            alt order still ACCEPTED/PREPARING
                Order-->>DeliveryAssignmentService: true
            else order concurrently CANCELLED
                Order-->>DeliveryAssignmentService: false
                DeliveryAssignmentService->>Partner: release() [compensate]
            end
        end
    end
```

## 3. Concurrent cancellation racing delivery-partner assignment (the race this system must prevent)

```mermaid
sequenceDiagram
    participant ThreadA as "Thread A: cancelOrder()"
    participant ThreadB as "Thread B: assignPartnerFor()"
    participant Order
    participant Partner as "DeliveryPartner"

    par Thread A
        ThreadA->>Order: cancel("changed mind")
        activate Order
        Note over Order: synchronized(order) block
        Order->>Order: state ACCEPTED -> CANCELLED
        deactivate Order
    and Thread B
        ThreadB->>Partner: tryReserve() [wins CAS]
        ThreadB->>Order: assignDeliveryPartner(partnerId)
        activate Order
        Note over Order: blocks until Thread A's\nsynchronized block exits
        Order->>Order: state.allowsDeliveryPartnerAssignment()?
        Order-->>ThreadB: false (state is now CANCELLED)
        deactivate Order
        ThreadB->>Partner: release() [compensate]
    end
    Note over Order,Partner: Final state is deterministic: whichever thread\nentered the order's monitor first fully wins;\nthe partner is never left stranded as BUSY.
```

## 4. Payment refund idempotency under duplicate calls

```mermaid
sequenceDiagram
    participant Caller1 as "Caller #1 (e.g. retried request)"
    participant Caller2 as "Caller #2 (duplicate)"
    participant PaymentService
    participant Payment

    par Caller1
        Caller1->>PaymentService: refund(orderId)
        PaymentService->>Payment: compareAndSet(SUCCESS, REFUNDED)
        Payment-->>PaymentService: true
        PaymentService-->>Caller1: true
    and Caller2
        Caller2->>PaymentService: refund(orderId)
        PaymentService->>Payment: compareAndSet(SUCCESS, REFUNDED)
        Payment-->>PaymentService: false (already REFUNDED)
        PaymentService-->>Caller2: false
    end
```
