# State Diagram: `ParkingSpot` Lifecycle

```mermaid
stateDiagram-v2
    [*] --> AVAILABLE
    AVAILABLE --> RESERVED: tryReserve() [CAS]
    RESERVED --> OCCUPIED: occupy(vehicle) [CAS]
    RESERVED --> AVAILABLE: releaseReservation() [CAS]
    OCCUPIED --> AVAILABLE: vacate() [CAS]
    AVAILABLE --> OUT_OF_SERVICE: tryMarkOutOfService() [CAS]
    OUT_OF_SERVICE --> AVAILABLE: bringBackInService() [CAS]
```

Every arrow is exactly one `AtomicReference.compareAndSet` call in
`ParkingSpot` - there is no state reachable by two sequential CASes, which
is what makes each transition individually atomic and race-free regardless
of how many threads call it concurrently. See README section 11 for the
full concurrency argument.
