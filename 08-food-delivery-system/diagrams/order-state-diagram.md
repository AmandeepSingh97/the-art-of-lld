# Order Lifecycle — State Diagram

```mermaid
stateDiagram-v2
    [*] --> PLACED: customer places order
    PLACED --> ACCEPTED: restaurant accepts
    PLACED --> REJECTED: restaurant rejects
    PLACED --> CANCELLED: customer cancels /\nacceptance-timeout auto-cancel
    ACCEPTED --> PREPARING: kitchen starts cooking
    ACCEPTED --> CANCELLED: customer/restaurant cancels\n(before cooking starts)
    PREPARING --> READY_FOR_PICKUP: food packed
    READY_FOR_PICKUP --> PICKED_UP: rider collects order
    PICKED_UP --> DELIVERED: rider hands off to customer
    REJECTED --> [*]
    CANCELLED --> [*]
    DELIVERED --> [*]

    note right of PREPARING
        Once PREPARING starts, cancel()
        is no longer a legal self-service
        transition (kitchen has committed
        ingredients/cook time) — see
        PreparingState, which does not
        override cancel().
    end note

    note right of ACCEPTED
        allowsDeliveryPartnerAssignment()
        is true only for ACCEPTED and
        PREPARING — this is the guard
        that prevents a partner being
        reserved against a PLACED,
        REJECTED, or CANCELLED order.
    end note
```

This diagram must exactly match `lld.fooddelivery.state.*` — each arrow corresponds to one
`OrderState` subclass overriding exactly that transition method; any transition not drawn here
is structurally unreachable because `OrderState`'s default methods throw
`InvalidOrderStateTransitionException`.
