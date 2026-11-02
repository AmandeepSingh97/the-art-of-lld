# Component Diagram — Snake and Ladder

This diagram shows the package-level component structure of
`lld.snakeandladder` and the direction of dependencies between packages.
Dependencies only point "inward" toward `model` and "sideways" toward the
pluggable strategy packages (`dice`, `rules`) — nothing in `model` depends on
`engine`, `dice`, `rules`, `turn`, or `events`, which is what makes those
strategies swappable without touching the domain model.

```mermaid
flowchart TB
    subgraph app["Application / Demo"]
        Demo["Demo (Main)"]
    end

    subgraph engine_pkg["engine"]
        Game["Game"]
        GameBuilder["GameBuilder"]
    end

    subgraph board_pkg["board"]
        BoardBuilder["BoardBuilder"]
    end

    subgraph model_pkg["model"]
        Board["Board"]
        Player["Player"]
        Jump["Jump"]
        JumpType["JumpType"]
        GameState["GameState"]
    end

    subgraph dice_pkg["dice (Strategy)"]
        Dice["Dice (interface)"]
        SingleDie["SingleDie"]
        CompositeDice["CompositeDice"]
        FixedSequenceDice["FixedSequenceDice"]
    end

    subgraph rules_pkg["rules (Strategy)"]
        WinCondition["WinCondition (interface)"]
        ExactLandingWinCondition["ExactLandingWinCondition"]
        OvershootClampWinCondition["OvershootClampWinCondition"]
        MoveOutcome["MoveOutcome"]
    end

    subgraph turn_pkg["turn"]
        TurnManager["TurnManager"]
    end

    subgraph events_pkg["events (Observer)"]
        GameEvent["GameEvent (sealed)"]
        GameEventListener["GameEventListener"]
        ConsoleGameEventListener["ConsoleGameEventListener"]
    end

    subgraph exceptions_pkg["exceptions"]
        Exc["InvalidBoardConfigurationException\nInsufficientPlayersException\nGameNotInProgressException\nGameDidNotTerminateException"]
    end

    Demo --> GameBuilder
    Demo --> BoardBuilder
    Demo --> ConsoleGameEventListener

    GameBuilder --> Game
    GameBuilder --> board_pkg
    GameBuilder --> dice_pkg
    GameBuilder --> rules_pkg
    GameBuilder --> Exc

    Game --> Board
    Game --> Player
    Game --> TurnManager
    Game --> Dice
    Game --> WinCondition
    Game --> GameEventListener
    Game --> GameEvent
    Game --> Exc

    BoardBuilder --> Board
    BoardBuilder --> Jump
    BoardBuilder --> Exc

    SingleDie --> Dice
    CompositeDice --> Dice
    FixedSequenceDice --> Dice

    ExactLandingWinCondition --> WinCondition
    OvershootClampWinCondition --> WinCondition
    WinCondition --> MoveOutcome

    TurnManager --> Player

    ConsoleGameEventListener --> GameEventListener
    GameEventListener --> GameEvent

    Jump --> JumpType
    Board --> Jump
```

**Key dependency rule:** `model` has zero outgoing dependencies on any other
package in this diagram. `dice` and `rules` depend on nothing but the JDK
and (for `rules`) a tiny local record. `engine` is the only package that
depends on everything else, which is an intentional, acknowledged
concentration of coupling — see `STAFF_LEVEL_DEEP_DIVE.md` Q1 for the
trade-off discussion.
