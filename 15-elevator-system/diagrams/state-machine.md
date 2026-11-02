# Elevator State Machine (per car)

This is the State pattern transition graph implemented by
`IdleState` / `MovingUpState` / `MovingDownState` / `DoorOpenState` /
`MaintenanceState`. It is the SCAN/LOOK algorithm expressed as states: a car
only reverses when its current-direction queue is empty.

```mermaid
stateDiagram-v2
    [*] --> Idle

    Idle --> MovingUp: upStops non-empty
    Idle --> MovingDown: upStops empty AND downStops non-empty
    Idle --> Idle: both queues empty

    MovingUp --> DoorOpen: arrived at a floor in upStops
    MovingUp --> MovingDown: reached top / upStops exhausted AND downStops non-empty
    MovingUp --> Idle: reached top / upStops exhausted AND downStops empty
    MovingUp --> MovingUp: more upStops ahead

    MovingDown --> DoorOpen: arrived at a floor in downStops
    MovingDown --> MovingUp: reached bottom / downStops exhausted AND upStops non-empty
    MovingDown --> Idle: reached bottom / downStops exhausted AND upStops empty
    MovingDown --> MovingDown: more downStops ahead

    DoorOpen --> MovingUp: door closed AND upStops non-empty
    DoorOpen --> MovingDown: door closed AND upStops empty AND downStops non-empty
    DoorOpen --> Idle: door closed AND both queues empty
    DoorOpen --> DoorOpen: door still opening/holding/closing/obstructed

    Idle --> Maintenance: enterMaintenance()
    MovingUp --> Maintenance: enterMaintenance()
    MovingDown --> Maintenance: enterMaintenance()
    DoorOpen --> Maintenance: enterMaintenance()
    Maintenance --> Idle: exitMaintenance()
    Maintenance --> Maintenance: step() is a no-op while under maintenance
```

## Door sub-state machine (nested inside `DoorOpen`)

```mermaid
stateDiagram-v2
    [*] --> Opening
    Opening --> Open: tick (1 tick)
    Open --> Open: holding (HOLD_TICKS left > 0)
    Open --> Closing: hold exhausted AND sensor clear
    Open --> Obstructed: hold exhausted AND sensor obstructed
    Obstructed --> Open: re-open, fresh hold window
    Closing --> Closed: tick (1 tick)
    Closed --> [*]: elevator state machine resumes (moves or goes idle)
```
