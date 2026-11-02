# Architecture — Component Diagram

```mermaid
graph TB
    subgraph Client
        UI[Client / API caller]
    end

    subgraph "Read path"
        Catalog[CatalogService]
    end

    subgraph "Write path"
        Booking[BookingService]
        SeatLock[SeatLockService\nInMemorySeatLockService]
        Pricing[PricingStrategy]
        Payment[PaymentGateway\nMockPaymentGateway]
    end

    subgraph Repositories (in-memory, thread-safe)
        CityRepo[CityRepository]
        TheatreRepo[TheatreRepository]
        MovieRepo[MovieRepository]
        ShowRepo[ShowRepository]
        SeatInv[SeatInventoryRepository]
        BookingRepo[BookingRepository]
        PaymentRepo[PaymentRepository]
    end

    UI --> Catalog
    UI --> Booking

    Catalog --> ShowRepo
    Catalog --> SeatInv
    Catalog --> Pricing

    Booking --> SeatLock
    Booking --> Payment
    Booking --> Pricing
    Booking --> BookingRepo
    Booking --> PaymentRepo
    Booking --> ShowRepo
    Booking --> SeatInv

    SeatLock --> SeatInv

    TheatreRepo --> CityRepo
    ShowRepo --> MovieRepo
    ShowRepo --> TheatreRepo
```

**Notes**
- `CatalogService` is read-only; all mutation flows through `BookingService` and `SeatLockService`.
- `SeatLockService` is the sole owner of per-seat concurrency control (`ShowSeat` CAS state machine) — no other component mutates seat state directly.
- `PricingStrategy` is injected into both `CatalogService` (for display prices) and `BookingService` (for charge amount), guaranteeing the price a user sees is the price they pay.
