# Sequence Diagram — Hall Call Dispatch

Flow for `ElevatorController.requestHallCall(floor, direction)`, including the
concurrent-safety notes called out in the controller's javadoc.

```mermaid
sequenceDiagram
    actor Passenger as Passenger (hall button)
    participant Ctrl as ElevatorController
    participant Strat as SchedulingStrategy
    participant E1 as Elevator #1
    participant E2 as Elevator #2

    Passenger->>Ctrl: requestHallCall(floor=7, UP)
    Ctrl->>Ctrl: validateFloor(7)
    Ctrl->>Ctrl: new HallRequest(7, UP, currentTick)
    Ctrl->>Strat: selectElevator(request, [E1, E2], tick)
    activate Strat
    Strat->>E1: isInMaintenance(), currentFloor(), direction(), pendingStopCount(), consecutiveMovementTicks()
    Strat->>E2: isInMaintenance(), currentFloor(), direction(), pendingStopCount(), consecutiveMovementTicks()
    Strat-->>Ctrl: chosen = E2 (lowest cost)
    deactivate Strat
    Ctrl->>E2: addStop(7)
    E2->>E2: upStops.add(7)  (ConcurrentSkipListSet, thread-safe insert)
    Ctrl-->>Passenger: Elevator (E2) [for display/logging only]

    Note over Ctrl,E2: A second passenger's thread calling requestHallCall()<br/>concurrently repeats steps independently; no lock spans<br/>selection + insertion (see ElevatorController javadoc).

    loop every controller tick
        Ctrl->>E2: tick()
        E2->>E2: state.step(this) — MovingUpState advances one floor
    end
    E2->>E2: currentFloor == 7 → upStops.remove(7) → door.beginOpening()
    Note over E2: State transitions to DoorOpen; passenger boards.
```
