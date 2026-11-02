# Sequence Diagrams — Snake and Ladder

## 1. A single turn that triggers a ladder climb

Matches `Game.playSingleTurn()` exactly: dice roll → `WinCondition`
evaluation → player move → board jump resolution → event publication →
turn rotation.

```mermaid
sequenceDiagram
    participant Caller
    participant Game
    participant TurnManager
    participant Dice
    participant WinCondition
    participant Player
    participant Board
    participant Listener as GameEventListener

    Caller->>Game: playSingleTurn()
    Game->>TurnManager: pollCurrent()
    TurnManager-->>Game: currentPlayer
    Game->>Dice: roll()
    Dice-->>Game: rolled = 3
    Game->>WinCondition: evaluate(fromPosition=0, rolled=3, totalCells=100)
    WinCondition-->>Game: MoveOutcome(newPosition=3, moved=true, isWinningMove=false)
    Game->>Player: moveTo(3)
    Game->>Listener: onEvent(PlayerMovedEvent(player, 0, 3, 3))
    Game->>Board: jumpStartingAt(3)
    Board-->>Game: Optional[Jump(3 -> 23, LADDER)]
    Game->>Player: moveTo(23)
    Game->>Listener: onEvent(LadderClimbEvent(player, 3, 23))
    Game->>TurnManager: requeue(currentPlayer)
```

## 2. A full game to completion via `playUntilWinner()`

```mermaid
sequenceDiagram
    participant Caller
    participant Game
    participant WinCondition
    participant Listener as GameEventListener

    Caller->>Game: playUntilWinner()
    Game->>Game: start() [NOT_STARTED -> IN_PROGRESS]
    loop while state == IN_PROGRESS and turnAttempts < maxTurns
        Game->>Game: playSingleTurn()
        alt roll overshoots under ExactLandingWinCondition
            Game->>Listener: onEvent(TurnSkippedEvent)
        else roll lands on or before final cell
            Game->>Listener: onEvent(PlayerMovedEvent) [+ SnakeBiteEvent/LadderClimbEvent if applicable]
            alt newPosition == totalCells
                Game->>Game: state = FINISHED, winner = currentPlayer
                Game->>Listener: onEvent(PlayerWonEvent)
            end
        end
    end
    Game-->>Caller: winner
```

## 3. Board construction with validation (`BoardBuilder.build()`)

```mermaid
sequenceDiagram
    participant Client
    participant BoardBuilder
    participant Board

    Client->>BoardBuilder: withSize(10)
    Client->>BoardBuilder: addLadder(2, 23)
    Client->>BoardBuilder: addSnake(32, 10)
    Client->>BoardBuilder: build()
    BoardBuilder->>BoardBuilder: validate size in [3, 100]
    loop for each configured Jump
        BoardBuilder->>BoardBuilder: validate start/end cell range
        BoardBuilder->>BoardBuilder: reject start == 1 or start == totalCells
    end
    BoardBuilder->>BoardBuilder: detectCycles() [DFS over start->end edges]
    BoardBuilder->>Board: new Board(size, jumps)
    Board-->>BoardBuilder: immutable Board
    BoardBuilder-->>Client: Board
```

## 4. Game assembly via `GameBuilder` (Builder pattern in action)

```mermaid
sequenceDiagram
    participant Client
    participant GameBuilder
    participant Game
    participant TurnManager

    Client->>GameBuilder: withBoard(board)
    Client->>GameBuilder: addPlayer(alice)
    Client->>GameBuilder: addPlayer(bob)
    Client->>GameBuilder: withDice(SingleDie.seeded(6, 42))
    Client->>GameBuilder: withWinCondition(new OvershootClampWinCondition())
    Client->>GameBuilder: addListener(new ConsoleGameEventListener())
    Client->>GameBuilder: build()
    GameBuilder->>GameBuilder: validate board non-null, players.size() >= 2
    GameBuilder->>Game: new Game(board, players, dice, winCondition, listeners, maxTurns)
    Game->>TurnManager: new TurnManager(players)
    Game-->>GameBuilder: Game instance
    GameBuilder-->>Client: Game instance
```
