# Sequence Diagram — Door Obstruction Safety

Flow inside `DoorOpenState.step()` / `Door.tick()` when an obstruction is
detected at the moment of a close attempt.

```mermaid
sequenceDiagram
    participant Elev as Elevator (DoorOpenState)
    participant Door as Door
    participant Sensor as ObstructionSensor

    Note over Elev: Car has arrived at a requested floor; door.beginOpening() was called.

    loop each controller tick while door is open
        Elev->>Door: tick()
        alt state == OPENING
            Door->>Door: state = OPEN, holdTicksRemaining = HOLD_TICKS
        else state == OPEN and holdTicksRemaining > 0
            Door->>Door: holdTicksRemaining--
        else state == OPEN and holdTicksRemaining == 0
            Door->>Sensor: isObstructed()
            alt obstructed == true
                Sensor-->>Door: true
                Door->>Door: consecutiveObstructions++, state = OBSTRUCTED
                Note over Door: Door will NOT report isClosed(); elevator stays in DoorOpen.
            else obstructed == false
                Sensor-->>Door: false
                Door->>Door: consecutiveObstructions = 0, state = CLOSING
            end
        else state == OBSTRUCTED
            Door->>Door: state = OPEN, holdTicksRemaining = HOLD_TICKS (retry window)
        else state == CLOSING
            Door->>Door: state = CLOSED
        end
        Elev->>Door: isClosed()?
        alt not closed
            Elev-->>Elev: remain in DoorOpenState
        else closed
            Elev-->>Elev: transition to MovingUp / MovingDown / Idle
        end
    end

    Note over Door: If consecutiveObstructions >= JAM_ALERT_THRESHOLD,<br/>isJammed() becomes true — a production system<br/>would raise a monitoring alert here, but the door<br/>keeps retrying and never force-closes.
```
