# Sequence Diagram: Vehicle Entry (`parkVehicle`)

Matches `EntryGateService.processEntry` -> `ParkingLotService.parkVehicle`.
Two concurrent callers (Gate A, Gate B) are shown to make the race-handling
explicit, matching the "concurrent spot allocation from multiple entry
gates" scoping requirement.

```mermaid
sequenceDiagram
    actor DriverA as Driver (Gate A)
    actor DriverB as Driver (Gate B)
    participant EGA as EntryGateService (Gate A)
    participant EGB as EntryGateService (Gate B)
    participant PLS as ParkingLotService
    participant TR as TicketRepository
    participant SAS as SpotAllocationStrategy
    participant SR as SpotRepository
    participant SPOT as ParkingSpot

    DriverA->>EGA: processEntry(vehicleA)
    DriverB->>EGB: processEntry(vehicleB)
    par Gate A thread
        EGA->>PLS: parkVehicle(vehicleA, gateA)
        PLS->>TR: tryClaimPlate(plateA)
        TR-->>PLS: true
        PLS->>SAS: reserveSpotFor(vehicleA, gateA)
        SAS->>SR: reserveSpot(floor, size)
        SR->>SPOT: pollFirst() + tryReserve() [CAS]
        SPOT-->>SR: success
        SR-->>SAS: Optional[spotX]
        SAS-->>PLS: Optional[spotX]
        PLS->>SPOT: occupy(vehicleA)
        PLS->>TR: save(ticketA)
        PLS-->>EGA: ticketA
        EGA-->>DriverA: ticketA
    and Gate B thread
        EGB->>PLS: parkVehicle(vehicleB, gateB)
        PLS->>TR: tryClaimPlate(plateB)
        TR-->>PLS: true
        PLS->>SAS: reserveSpotFor(vehicleB, gateB)
        SAS->>SR: reserveSpot(floor, size)
        SR->>SPOT: pollFirst() + tryReserve() [CAS]
        Note over SR,SPOT: Same bucket as Gate A, but SR.reserveSpot<br/>pollFirst() atomically removed a *different*<br/>spot for Gate B - no contention on spotX.
        SPOT-->>SR: success (different spot, spotY)
        SR-->>SAS: Optional[spotY]
        SAS-->>PLS: Optional[spotY]
        PLS->>SPOT: occupy(vehicleB)
        PLS->>TR: save(ticketB)
        PLS-->>EGB: ticketB
        EGB-->>DriverB: ticketB
    end
```

**Failure branch (lot full / duplicate plate), not shown above for clarity:**
- If `tryClaimPlate` returns `false`, `parkVehicle` throws
  `VehicleAlreadyParkedException` immediately - no spot is touched.
- If `reserveSpotFor` returns empty, `parkVehicle` calls
  `ticketRepository.releaseClaim(plate)` to undo the claim, then throws
  `NoAvailableSpotException` - the plate is not "stuck" claimed.
