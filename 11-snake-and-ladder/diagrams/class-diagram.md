# Class Diagram — Snake and Ladder

This diagram matches the actual Java implementation in `src/main/java/lld/snakeandladder`
class-for-class and method-for-method (public API surface only; private
helpers are omitted for readability).

```mermaid
classDiagram
    direction LR

    class Player {
        -String id
        -String name
        -int position
        +of(name) Player$
        +withId(id, name) Player$
        +id() String
        +name() String
        +position() int
        +moveTo(newPosition) void
        +equals(Object) boolean
        +hashCode() int
    }

    class JumpType {
        <<enumeration>>
        SNAKE
        LADDER
    }

    class Jump {
        <<record>>
        +int start
        +int end
        +JumpType type
        +ladder(start, end) Jump$
        +snake(start, end) Jump$
    }

    class Board {
        -int size
        -int totalCells
        -Map~Integer,Jump~ jumpsByStart
        +size() int
        +totalCells() int
        +jumps() Map~Integer,Jump~
        +jumpStartingAt(cell) Optional~Jump~
        +resolveSingleHop(cell) int
        +resolveChained(cell) int
    }

    class GameState {
        <<enumeration>>
        NOT_STARTED
        IN_PROGRESS
        FINISHED
    }

    class BoardBuilder {
        -int size
        -Map~Integer,Jump~ jumps
        +withSize(size) BoardBuilder
        +addLadder(start, end) BoardBuilder
        +addSnake(start, end) BoardBuilder
        +build() Board
    }

    class Dice {
        <<interface>>
        +roll() int
        +minPossibleRoll() int
        +maxPossibleRoll() int
    }

    class SingleDie {
        -int faces
        -RandomGenerator randomGenerator
        +standardSixSided() SingleDie$
        +seeded(faces, seed) SingleDie$
        +roll() int
    }

    class CompositeDice {
        -List~Dice~ components
        +twoStandardDice() CompositeDice$
        +roll() int
    }

    class FixedSequenceDice {
        -List~Integer~ rolls
        -AtomicInteger cursor
        +of(rolls...) FixedSequenceDice$
        +roll() int
    }

    class MoveOutcome {
        <<record>>
        +int newPosition
        +boolean moved
        +boolean isWinningMove
    }

    class WinCondition {
        <<interface>>
        +evaluate(currentPosition, rolledTotal, totalCells) MoveOutcome
    }

    class ExactLandingWinCondition {
        +evaluate(...) MoveOutcome
    }

    class OvershootClampWinCondition {
        +evaluate(...) MoveOutcome
    }

    class TurnManager {
        -Deque~Player~ order
        +peekCurrent() Player
        +pollCurrent() Player
        +requeue(player) void
        +remainingPlayerCount() int
    }

    class GameEvent {
        <<sealed interface>>
    }
    class PlayerMovedEvent {
        <<record>>
        +Player player
        +int fromPosition
        +int toPosition
        +int rolled
    }
    class SnakeBiteEvent {
        <<record>>
        +Player player
        +int fromCell
        +int toCell
    }
    class LadderClimbEvent {
        <<record>>
        +Player player
        +int fromCell
        +int toCell
    }
    class TurnSkippedEvent {
        <<record>>
        +Player player
        +int atPosition
        +int rolled
        +String reason
    }
    class PlayerWonEvent {
        <<record>>
        +Player player
        +int turnsTaken
    }

    class GameEventListener {
        <<interface>>
        +onEvent(event) void
    }

    class ConsoleGameEventListener {
        +onEvent(event) void
    }

    class Game {
        -Board board
        -List~Player~ players
        -TurnManager turnManager
        -Dice dice
        -WinCondition winCondition
        -List~GameEventListener~ listeners
        -int maxTurns
        -GameState state
        -Player winner
        -int turnsTaken
        -int turnAttempts
        +builder() GameBuilder$
        +start() void
        +playSingleTurn() void
        +playUntilWinner() Player
        +state() GameState
        +winner() Optional~Player~
    }

    class GameBuilder {
        -Board board
        -List~Player~ players
        -Dice dice
        -WinCondition winCondition
        -List~GameEventListener~ listeners
        -int maxTurns
        +withBoard(board) GameBuilder
        +addPlayer(player) GameBuilder
        +withDice(dice) GameBuilder
        +withWinCondition(rule) GameBuilder
        +addListener(listener) GameBuilder
        +withMaxTurns(n) GameBuilder
        +build() Game
    }

    Jump --> JumpType : has
    Board "1" o-- "0..*" Jump : jumpsByStart
    BoardBuilder ..> Board : creates
    BoardBuilder ..> Jump : validates

    Dice <|.. SingleDie
    Dice <|.. CompositeDice
    Dice <|.. FixedSequenceDice
    CompositeDice "1" o-- "1..*" Dice : components

    WinCondition <|.. ExactLandingWinCondition
    WinCondition <|.. OvershootClampWinCondition
    WinCondition ..> MoveOutcome : returns

    TurnManager "1" o-- "2..*" Player : order

    GameEvent <|.. PlayerMovedEvent
    GameEvent <|.. SnakeBiteEvent
    GameEvent <|.. LadderClimbEvent
    GameEvent <|.. TurnSkippedEvent
    GameEvent <|.. PlayerWonEvent
    GameEventListener <|.. ConsoleGameEventListener
    GameEventListener ..> GameEvent : consumes

    Game "1" *-- "1" Board
    Game "1" *-- "2..*" Player
    Game "1" *-- "1" TurnManager
    Game "1" o-- "1" Dice
    Game "1" o-- "1" WinCondition
    Game "1" o-- "0..*" GameEventListener
    Game ..> GameState
    Game ..> MoveOutcome
    GameBuilder ..> Game : creates
```

**Relationship notes (match `README.md §4` exactly):**
- `Game *-- Board` / `Game *-- Player` / `Game *-- TurnManager`: composition
  — these are created for and owned exclusively by one `Game` instance and
  have no independent lifecycle outside it (with the one deliberate
  exception that a `Board` instance *can* be shared across multiple `Game`s
  since it is immutable — the UML composition arrow reflects the common
  case, not a hard constraint enforced by the type system).
- `Game o-- Dice` / `Game o-- WinCondition` / `Game o-- GameEventListener`:
  aggregation — these are injected collaborators that exist independently
  of any one `Game` and may be reused across many `Game` instances (e.g. a
  single `ConsoleGameEventListener` could, in principle, listen to several
  games at once).
- `Dice <|.. SingleDie` etc. and `WinCondition <|.. ExactLandingWinCondition`
  etc.: realization of a Strategy interface — this is the crux of the
  design's extensibility story.
