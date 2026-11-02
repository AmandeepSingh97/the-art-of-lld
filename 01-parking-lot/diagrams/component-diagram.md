# Component Diagram

Shows the major packages/layers and the direction of dependencies. Arrows
point from a dependent to its dependency (standard "depends-on" direction).

```mermaid
flowchart TB
    subgraph Gates["Gate Layer (lld.parkinglot.service)"]
        EG[EntryGateService]
        XG[ExitGateService]
        ADM[AdminService]
    end

    subgraph Facade["Orchestration (lld.parkinglot.service)"]
        PLS[ParkingLotService]
        PS[PaymentService]
    end

    subgraph Strategies["Strategy Layer (lld.parkinglot.strategy)"]
        SAS[SpotAllocationStrategy]
        PRS[PricingStrategy]
        PP[PaymentProcessor]
    end

    subgraph Repos["Persistence Seam (lld.parkinglot.repository)"]
        SR[SpotRepository]
        TR[TicketRepository]
        PR[PaymentRepository]
    end

    subgraph Domain["Domain Model (lld.parkinglot.model)"]
        PL[ParkingLot / Floor]
        SPOT[ParkingSpot]
        VEH[Vehicle]
        TIX[Ticket]
        PAY[Payment]
        GATE[Gate]
    end

    subgraph Factory["Construction (lld.parkinglot.factory)"]
        PLB[ParkingLotBuilder]
    end

    EG --> PLS
    XG --> PLS
    ADM --> SR

    PLS --> SAS
    PLS --> PRS
    PLS --> PS
    PLS --> SR
    PLS --> TR
    PS --> PP
    PS --> PR

    SAS --> SR
    SAS --> Domain
    PRS --> Domain
    PP --> Domain

    SR --> PL
    SR --> SPOT
    TR --> TIX
    PR --> PAY

    PLB --> PL
    PLB --> SPOT

    EG --> GATE
    XG --> GATE
```

**Reading guide:** everything above the dashed "Domain" line is replaceable
without touching the domain model. Swapping `InMemorySpotRepository` for a
database-backed implementation, or `NearestAvailableSpotAllocationStrategy`
for a different allocation policy, requires no change to `ParkingSpot`,
`Ticket`, or any other entity - this is the payoff of the Strategy +
Repository seams described in README section 7.
