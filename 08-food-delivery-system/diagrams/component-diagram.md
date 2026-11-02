# Architecture — Component Diagram

```mermaid
graph TB
    subgraph Clients
        CustApp[Customer App]
        RestApp[Restaurant Dashboard]
        RiderApp[Delivery Partner App]
    end

    subgraph "Application Services (lld.fooddelivery.service)"
        RS[RestaurantService]
        CS[CartService]
        OS[OrderService]
        DAS[DeliveryAssignmentService]
        PS[PaymentService]
    end

    subgraph "Domain (lld.fooddelivery.domain)"
        Restaurant
        Menu
        MenuItem
        Cart
        Order
        DeliveryPartner
        Payment
    end

    subgraph "State Pattern (lld.fooddelivery.state)"
        OrderState["OrderState hierarchy\n(Placed/Accepted/Preparing/\nReadyForPickup/PickedUp/\nDelivered/Rejected/Cancelled)"]
    end

    subgraph "Strategy + Decorator (lld.fooddelivery.strategy)"
        AssignStrategy["DeliveryPartnerAssignmentStrategy\n(NearestAvailable / RoundRobin)"]
        PriceCalc["PriceCalculator decorator chain\n(Base + %Discount + FlatDiscount + DeliveryFee)"]
    end

    subgraph "Observer (lld.fooddelivery.observer)"
        OrderObserver["OrderObserver\n(CustomerNotification / RestaurantDashboard /\nDeliveryPartnerNotification / OrderTracking)"]
    end

    CustApp --> CS
    CustApp --> OS
    RestApp --> RS
    RestApp --> OS
    RiderApp --> OS

    CS --> RS
    CS --> Cart
    OS --> CS
    OS --> RS
    OS --> DAS
    OS --> PS
    OS --> PriceCalc
    OS --> Order

    RS --> Restaurant
    Restaurant --> Menu
    Menu --> MenuItem

    DAS --> AssignStrategy
    DAS --> DeliveryPartner

    Order --> OrderState
    Order --> OrderObserver

    PS --> Payment
```

**Reading guide:** arrows show "depends on" / "calls into", not data flow direction. `OrderService`
is the only component that talks to every other service — it is the orchestration layer. Domain
objects (`Order`, `MenuItem`, `DeliveryPartner`) own their own thread-safety; services compose
them but do not re-implement locking around them (see README §11).
