# Component / Architecture Diagram

High-level module boundaries and the direction of dependency arrows (an arrow
from A to B means "A depends on / calls B"). Notice the `state` and `elevator`
packages depend on each other only through the narrow `ElevatorState` /
`Elevator` interface surface — this is the State pattern boundary — and that
`scheduler` depends only on read-only accessors of `Elevator`, never on its
mutation methods, enforcing "the scheduler decides, the elevator executes."

```mermaid
flowchart TB
    subgraph Client["Client (Main / Demo / Tests)"]
        Demo[ElevatorSystemDemo]
    end

    subgraph ControllerPkg["controller"]
        Controller[ElevatorController]
        Snapshot[ElevatorSnapshot]
    end

    subgraph SchedulerPkg["scheduler (Strategy pattern)"]
        Strategy[["«interface» SchedulingStrategy"]]
        Nearest[NearestCarScanStrategy]
        Naive[NaiveFcfsStrategy]
    end

    subgraph ElevatorPkg["elevator"]
        Elevator[Elevator]
    end

    subgraph StatePkg["state (State pattern)"]
        StateIf[["«interface» ElevatorState"]]
        Idle[IdleState]
        Up[MovingUpState]
        Down[MovingDownState]
        DoorOpen[DoorOpenState]
        Maint[MaintenanceState]
    end

    subgraph DoorPkg["door"]
        Door[Door]
        Sensor[["«interface» ObstructionSensor"]]
    end

    subgraph RequestPkg["request (value objects)"]
        ReqIf[["«interface» Request"]]
        Hall[HallRequest]
        Cabin[CabinRequest]
    end

    Demo --> Controller
    Controller --> Strategy
    Controller --> Elevator
    Controller --> Snapshot
    Snapshot --> Elevator
    Strategy -.implements.-> Nearest
    Strategy -.implements.-> Naive
    Nearest --> Elevator
    Nearest --> Hall
    Naive --> Elevator
    Controller --> Hall
    Hall -.implements.-> ReqIf
    Cabin -.implements.-> ReqIf
    Elevator --> StateIf
    Elevator --> Door
    StateIf -.implements.-> Idle
    StateIf -.implements.-> Up
    StateIf -.implements.-> Down
    StateIf -.implements.-> DoorOpen
    StateIf -.implements.-> Maint
    Idle --> Elevator
    Up --> Elevator
    Down --> Elevator
    DoorOpen --> Elevator
    Maint --> Elevator
    Door --> Sensor
```
