# Architecture — Component Diagram

```mermaid
graph TB
    subgraph Client
        Demo[SchedulerDemo / API caller]
    end

    subgraph Facade
        SS[SchedulerService]
    end

    subgraph "Coordination & Algorithms"
        RLC[ResourceLockCoordinator]
        RES[RecurrenceExpansionService]
        FSF[FreeSlotFinder]
        STRAT1[DailyExpansionStrategy]
        STRAT2[WeeklyExpansionStrategy]
    end

    subgraph "Per-Resource Calendars (DSA core)"
        TMIC_R[TreeMapIntervalCalendar per Room]
        TMIC_U[TreeMapIntervalCalendar per User]
    end

    subgraph Repositories
        UR[UserRepository]
        RR[RoomRepository]
        MR[MeetingRepository]
    end

    subgraph Notification
        BEL[BookingEventListener]
        CBEL[ConsoleBookingEventListener]
    end

    Demo --> SS
    SS --> RLC
    SS --> RES
    SS --> FSF
    SS --> UR
    SS --> RR
    SS --> MR
    SS --> TMIC_R
    SS --> TMIC_U
    SS --> BEL
    RES --> STRAT1
    RES --> STRAT2
    BEL <|.. CBEL
    RLC -. "ordered locks guard" .-> TMIC_R
    RLC -. "ordered locks guard" .-> TMIC_U
```

**Reading guide:** `SchedulerService` is the only class a client talks to (Facade pattern). It
never touches raw locks or `TreeMap`s directly without going through `ResourceLockCoordinator`
and the per-resource `IntervalCalendar` instances. `RecurrenceExpansionService` is stateless and
delegates to a `RecurrenceExpansionStrategy` per `Frequency` (Strategy pattern). Notifications
are published through the `BookingEventListener` interface (Observer pattern) so the scheduler
has zero knowledge of *how* a booking is announced.
