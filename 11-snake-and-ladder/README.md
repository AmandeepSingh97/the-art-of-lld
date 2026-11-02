# Snake and Ladder — Staff-Level Low-Level Design

Package: `lld.snakeandladder` · Java 17+ · Source: `src/main/java/lld/snakeandladder` · Tests: `tests/lld/snakeandladder`

---

## 1. Problem Understanding

**Plain explanation.** Snake and Ladder (a.k.a. Chutes and Ladders) is a
board game played on an `N x N` grid of numbered cells (classically 10x10,
cells 1–100). Players take turns rolling a die and advancing their token by
the rolled amount. Certain cells have a "jump": landing exactly on a
**ladder's base** instantly moves the token forward to the ladder's top;
landing on a **snake's head** instantly moves the token backward to the
snake's tail. The first player whose token reaches the final cell wins.

**Real-world use case.** This is a canonical "model a board game as an
object graph with pluggable rules" interview problem. The same shape of
problem — fixed board, probabilistic/random state advancement, a
configurable win condition, round-robin turn-taking among N participants —
recurs in domains well beyond games: lottery/raffle simulators, randomized
A/B rollout schedulers, round-robin task dispatchers with probabilistic
retry/backoff, and state-machine-driven workflow engines with
non-deterministic transitions.

**What the interviewer is evaluating.**
- Can you spot the *unifying abstraction* (snake and ladder are the same
  mechanic) instead of writing two parallel, duplicated code paths?
- Do you correctly separate **mechanism** (turn loop, board resolution)
  from **policy** (dice randomness, win condition) so that variants
  ("exact landing required", "two dice", "deterministic test dice") are
  configuration, not code changes?
- Do you reason about **termination** — a probabilistic game loop is not
  obviously bounded, and most candidates never mention this.
- Do you produce genuinely testable code — this is the problem's hidden
  difficulty. "Simple" problems like this are frequently *under*-engineered
  by candidates who skip determinism and therefore cannot write a single
  non-flaky unit test.
- Basic OOP hygiene: immutability where it matters (`Board`, `Jump`),
  encapsulated mutable state (`Player.position`), and no leaking of
  internal collections.

**In scope.**
- Configurable square board size.
- Unified snake/ladder ("Jump") abstraction with validation.
- Pluggable dice strategy (single die, multiple dice, seeded/deterministic
  dice for tests).
- N-player round-robin turn management.
- Pluggable win condition (overshoot-clamp vs. exact-landing-required).
- Observable game events (snake bite, ladder climb, turn skipped, player
  won) via an Observer-style listener API.
- A runnable demo (`Demo.main`) exercising the full stack.

**Out of scope (named explicitly, not silently ignored).**
- Networking / multiplayer transport (WebSocket, REST session management).
- Persistence of game state across process restarts (see §12).
- A graphical board renderer.
- Player authentication / matchmaking.
- "Must roll a 6 to enter the board" house rule (noted as a clean
  extension point in §17, not implemented, to keep scope bounded).
- Concurrent multi-threaded access to a single live game (see §11 for why,
  and what would change if this became an in-scope requirement).

---

## 2. Requirement Gathering

### Functional — essential
1. Configurable board size (`N x N`), default 10x10 (100 cells).
2. Configurable set of snakes and ladders, validated against the board.
3. Support 2–N players (no theoretical upper bound beyond memory).
4. A pluggable dice mechanism: at minimum, a single six-sided die.
5. Round-robin turn order; every player gets exactly one roll per round
   until someone wins.
6. A player who reaches the final cell exactly or by overshoot (depending
   on the active rule) wins immediately; the game ends.
7. Landing on a snake's head or ladder's base instantly relocates the
   token, within the same turn, before the next player rolls.

### Functional — optional / variant
8. "Exact landing required" win condition (overshoot forfeits the roll).
9. Multiple dice summed per roll (e.g. two six-sided dice).
10. Deterministic/seeded dice for reproducible simulations and tests.
11. Observable events for UI/logging integrations (snake bite, ladder
    climb, turn skipped, player won).
12. Chained jump resolution (a house-rule variant where landing on a
    ladder that leads directly into a snake's head resolves both in one
    turn) — implemented as an opt-in alternate resolution method
    (`Board.resolveChained`), not wired into the default game loop.

### Non-functional
- **Performance.** A single turn must be O(1) amortized (dice roll +
  one hash-map lookup for jump resolution + one deque rotation). A full
  game on a 10x10 board with typical dice resolves in well under a
  millisecond of CPU time; this is not a performance-bound problem.
- **Scalability.** The design must support an arbitrary board size and an
  arbitrary number of players without algorithmic changes (see §14 for
  the one real scaling axis: *number of concurrently running independent
  games*, not the size of any single game).
- **Availability.** N/A for a pure in-memory library; becomes relevant
  only in the production-service extension (§17).
- **Consistency.** Within a single game, strict sequential consistency is
  trivial because there is exactly one writer thread per game (see §11).
  Across many games in a service, each game's state is independent — no
  cross-game consistency requirement exists.
- **Extensibility.** Adding a new dice strategy or win-condition strategy
  must require zero changes to `Game`, `Board`, or `TurnManager`. This is
  the primary non-functional requirement this design optimizes for, since
  the problem statement explicitly calls out variants.
- **Reliability.** The game loop must provably terminate or fail loudly
  (`GameDidNotTerminateException`) rather than hang — see §5 and §13.
- **Thread-safety.** A single `Game` is single-writer by design (see §11).
  Immutable value objects (`Board`, `Jump`, `MoveOutcome`, events) are
  trivially safe to share and read from any thread.

### Interview-necessary vs. production-extension split
| Interview-necessary (build this in 45–60 min) | Production extension (discuss, don't build) |
|---|---|
| Board, Jump, Player, Dice strategy, WinCondition strategy, TurnManager, Game loop, event observer | Persistence, distributed session state, matchmaking, concurrent multi-writer access, metrics/observability, authentication |

---

## 3. Clarifying Questions

Each question below materially changes the design, and the answer *assumed*
for this implementation is stated explicitly so the design is unambiguous.

1. **Is the board always square, and is 10x10 the only size?**
   *Why it matters:* a non-square board changes cell-numbering (boustrophedon
   "snake" row traversal vs. simple row-major) and total-cell computation.
   *Assumed:* square `N x N`, cells numbered 1..N² in simple row-major reading
   order (the specific left-to-right/right-to-left row-snaking visual layout
   is a rendering concern, not a model concern, since the model only deals
   with integer cell numbers).

2. **Can two jumps share a start or end cell? Can jumps chain?**
   *Why it matters:* determines whether `Board` needs a `Map` (one jump per
   start cell, simplest) or a `Multimap`/graph, and whether resolution must
   loop or can be a single lookup.
   *Assumed:* at most one jump may start at any given cell (duplicate starts
   are a configuration error, rejected at build time); jumps *may* share an
   end cell; **classic single-hop resolution** is the default behavior
   (chaining is exposed as an explicit alternate method,
   `Board.resolveChained`, for variants, with cycle-detection at
   construction time so chaining can never loop forever).

3. **What happens if a roll would overshoot the final cell?**
   *Why it matters:* this is the crux of the "exact landing on 100"
   variant named in the problem statement, and it is the single most
   commonly mishandled edge case in candidate implementations.
   *Assumed:* configurable via the `WinCondition` strategy — default
   behavior clamps to the final cell and wins (`OvershootClampWinCondition`);
   the variant rejects the roll and the player does not move
   (`ExactLandingWinCondition`).

4. **Can a jump start on the final cell or the first cell?**
   *Why it matters:* a jump starting on the winning cell creates an
   ambiguous "did you win or get bitten by a snake" situation; a jump
   starting on cell 1 removes meaningful choice from the very first roll
   for whichever unlucky player lands there first after entering.
   *Assumed:* disallowed in both cases, enforced in `BoardBuilder.build()`.

5. **How many dice, and are they always six-sided?**
   *Why it matters:* determines whether `Dice` must be an interface
   (strategy) or can be a single hardcoded class.
   *Assumed:* fully pluggable via the `Dice` strategy interface; shipped
   implementations cover single die (any face count), multiple summed
   dice, and a fully scripted deterministic sequence for tests.

6. **Does a player need to roll a specific number (e.g. a 6) to leave the
   starting area and enter the board?**
   *Why it matters:* this is a common real-world house rule that adds an
   "not yet entered" sub-state per player.
   *Assumed:* not required — every player starts at position 0 and any
   roll advances them (documented explicitly as an extension point in
   §17, not implemented, to keep the core problem bounded).

7. **Is an extra turn granted for rolling the maximum face value (e.g. a
   6) or for landing on a ladder?**
   *Why it matters:* changes whether `TurnManager.requeue` is
   unconditional or conditional, and whether a player can roll multiple
   times in a single logical "turn."
   *Assumed:* not implemented (keeps `TurnManager` and `Game` simpler); the
   design calls out exactly where this would hook in (`Game.playSingleTurn`,
   conditionally skip the `requeue` call and loop again) if asked live.

8. **Must the game be safe for multiple threads to drive the same game
   concurrently (e.g. two HTTP requests racing to submit a roll for the
   same game)?**
   *Why it matters:* this is the single biggest architectural fork in the
   whole problem — see §11 for the full analysis.
   *Assumed:* no — one game is driven by one logical actor at a time, which
   mirrors how the physical game is played and is almost always the
   interviewer's actual intent unless they explicitly say "build a
   multiplayer server."

9. **Should a disconnected/removed player be supported mid-game?**
   *Why it matters:* `TurnManager`'s `Deque`-based rotation makes
   mid-queue removal awkward (O(n) scan); a `List` + circular index would
   support removal more naturally but complicates "move to back of line."
   *Assumed:* not required; noted as a trade-off in §16.

---

## 4. Domain Modelling

| Type | Kind | Responsibility |
|---|---|---|
| `Player` | Entity (mutable identity: `id`) | Identity + current board position. The *only* mutable domain object. |
| `Jump` | Value object (`record`) | Immutable `(start, end, type)` triple unifying snakes and ladders; self-validating via a compact constructor. |
| `JumpType` | Enum | Discriminates `SNAKE` vs `LADDER` purely for observability (event naming); carries no resolution-time behavior. |
| `Board` | Value object (immutable aggregate) | Owns the size, total cell count, and the `start -> Jump` map; exposes `resolveSingleHop`/`resolveChained`. |
| `BoardBuilder` | Builder | Validates and assembles a `Board` (size range, cell range, duplicate starts, entry/final cell restrictions, cycle detection). |
| `GameState` | Enum | `NOT_STARTED` / `IN_PROGRESS` / `FINISHED` lifecycle marker on `Game`. |
| `Dice` | Strategy interface | Produces the next roll and declares its possible range. |
| `SingleDie`, `CompositeDice`, `FixedSequenceDice` | Strategy implementations | Single die, summed multi-dice, and scripted-for-tests dice respectively. |
| `MoveOutcome` | Value object (`record`) | Result of evaluating one roll: new position, whether the player actually moved, whether it was a winning move. |
| `WinCondition` | Strategy interface | Encapsulates overshoot handling and win detection. |
| `ExactLandingWinCondition`, `OvershootClampWinCondition` | Strategy implementations | The two named variants from the problem statement. |
| `TurnManager` | Domain service | Owns round-robin ordering over the live player roster via a `Deque`. |
| `GameEvent` (sealed) + 5 record implementations | Value objects | Immutable, typed notifications of in-game occurrences. |
| `GameEventListener` | Observer interface | Receives `GameEvent`s; `ConsoleGameEventListener` is the shipped reference implementation. |
| `Game` | Domain service / aggregate root | Orchestrates one game: owns `Board`, `players`, `TurnManager`, `Dice`, `WinCondition`, listeners; drives the turn loop. |
| `GameBuilder` | Builder | Validates and assembles a `Game` (board required, ≥2 players, sensible defaults for dice/win-condition/maxTurns). |
| `InvalidBoardConfigurationException`, `InsufficientPlayersException`, `GameNotInProgressException`, `GameDidNotTerminateException` | Exceptions | Fail-fast signals for the four distinct ways this domain can be misused. |

**Composition / association / ownership.**
- `Game` **composes** `TurnManager` (created in the `Game` constructor,
  no independent lifecycle, never shared) and the `players` list (owned
  copy via `List.copyOf`).
- `Game` **aggregates** `Board`, `Dice`, `WinCondition`, and
  `GameEventListener`s — these are injected, may outlive the `Game`, and
  (in the case of an immutable `Board`) may be legitimately shared across
  multiple concurrently-running `Game` instances.
- `Board` **composes** its `Jump` map — jumps have no meaning or identity
  outside the board that validated them.
- `TurnManager` **aggregates** `Player` references — players are created
  by the application, not by `TurnManager`, and the same `Player` objects
  are also referenced directly by `Game.players()`.
- No inheritance hierarchies exist in the design except the natural
  interface-implementation relationships (`Dice`, `WinCondition`,
  `GameEventListener`, the sealed `GameEvent` hierarchy). This is
  deliberate: composition over inheritance, and there is no natural
  "is-a" hierarchy among snakes, ladders, or dice types that classical
  subclassing would model better than a `record`/enum + Strategy
  interface.

---

## 5. Domain Invariants

| # | Invariant | Enforced where |
|---|---|---|
| 1 | A board's dimension is within `[3, 100]`. | `BoardBuilder.build()` |
| 2 | Every jump's `start` and `end` lie within `[1, totalCells]`. | `BoardBuilder.build()` → `validateCellRange` |
| 3 | A ladder always has `end > start`; a snake always has `end < start`; `start != end` is never allowed for either. | `Jump`'s compact constructor (cannot be bypassed — enforced at the value-object level, not just at the builder) |
| 4 | No two jumps share the same `start` cell. | `BoardBuilder.addJump` (checked eagerly, at the moment of addition, not deferred to `build()`) |
| 5 | No jump starts on cell 1 (the entry square) or on the final cell. | `BoardBuilder.build()` |
| 6 | The jump graph contains no cycles (so chained resolution can never loop forever). | `BoardBuilder.detectCycles()` (DFS with a visiting/visited coloring) |
| 7 | A `Game` requires at least 2 players to be built. | `GameBuilder.build()` → `InsufficientPlayersException` |
| 8 | A `Game` requires a `Board` to be built. | `GameBuilder.build()` → `NullPointerException` via `Objects.requireNonNull` |
| 9 | A turn can only be played while `state == IN_PROGRESS`. | `Game.playSingleTurn()` → `GameNotInProgressException` |
| 10 | Exactly one `PlayerWonEvent` is ever published per game, and no turn is playable afterward. | `Game.playSingleTurn()` sets `state = FINISHED` and returns immediately on a win; the next call's state guard rejects further turns |
| 11 | A player's `position` is never negative. | `Player.moveTo()` |
| 12 | `playUntilWinner()` always terminates (either with a winner or by throwing). | `Game.playUntilWinner()`'s `turnAttempts >= maxTurns` guard, counting **every** turn attempt including rejected/overshoot turns — see §13 for why this had to count attempts, not just successful moves |

---

## 6. Architecture and Class Diagram

See `diagrams/component-diagram.md` for the package-level dependency
diagram, `diagrams/class-diagram.md` for the full class diagram (matches
the implementation 1:1, including method signatures), and
`diagrams/sequence-diagrams.md` for four key flows: a ladder-climb turn,
a full `playUntilWinner()` run, board construction/validation, and game
assembly via `GameBuilder`.

The short version of the architecture: `Game` is the aggregate root and
the only class that "knows everything." Everything it depends on —
`Board`, `Dice`, `WinCondition`, `TurnManager`, `GameEventListener` — is
either an immutable value object or an interface implemented by a small,
independently-testable strategy class. This is what makes the strategies
swappable and the overall system testable without mocking frameworks:
every collaborator can be instantiated directly (e.g.
`FixedSequenceDice.of(6, 6, 3)`) rather than needing a mock.

---

## 7. Design Patterns

| Pattern | Why needed | Where applied | Alternative considered | Why rejected |
|---|---|---|---|---|
| **Strategy** | The problem statement explicitly calls for pluggable dice and a pluggable win condition ("exact landing" variant). | `Dice` (+ `SingleDie`/`CompositeDice`/`FixedSequenceDice`); `WinCondition` (+ `ExactLandingWinCondition`/`OvershootClampWinCondition`) | Hardcode a single die and an `if (overshoot)` branch directly in `Game`, with a boolean flag for the variant. | A boolean flag metastasizes the moment a *second* variant appears (e.g. "bounce back" overshoot handling), and testing requires constructing a full `Game` rather than a 5-line unit test against the strategy in isolation. |
| **Builder** | `Board` and `Game` each have several optional fields and must validate cross-field invariants (cycle detection, player count) before becoming usable. | `BoardBuilder`, `GameBuilder` | Telescoping constructors, or a mutable "bean" with setters and a separate `validate()` call the caller might forget. | Telescoping constructors are unreadable past 3–4 parameters; a bean with a forgettable `validate()` call lets invalid objects escape into the system. The builder makes "validated at construction" the only reachable path. |
| **Observer** | UI, logging, and test assertions all need to react to in-game occurrences (snake bite, ladder climb, win) without `Game` knowing or caring who's listening. | `GameEventListener` + sealed `GameEvent` hierarchy; `ConsoleGameEventListener` | Have `Game.playSingleTurn()` return a rich result object describing everything that happened, and let the caller inspect it. | A single return value works for a synchronous caller but doesn't scale to multiple independent consumers (e.g. a UI renderer *and* a metrics counter *and* a test harness) without each caller re-deriving "what happened" from the same struct; Observer lets each concern subscribe independently, and the sealed hierarchy still gives exhaustiveness checking. |
| **Immutable Value Object** (not GoF-named, but a deliberate, explicit pattern choice) | `Board`, `Jump`, `MoveOutcome`, and every `GameEvent` never need to change after construction and are frequently passed across threads/layers (e.g. to a listener). | `Jump` (record), `Board` (effectively immutable, defensive `Map.copyOf`), `MoveOutcome` (record), all `GameEvent` implementations (records) | Mutable DTOs with getters/setters. | Mutability here would only invite bugs (e.g. a listener accidentally mutating a shared `Board`'s jump map) with zero corresponding benefit, since none of these objects ever need to change post-construction. |

**Pattern deliberately *not* used: full GoF State pattern for `GameState`.**
`GameState` has exactly three values and no per-state polymorphic behavior
beyond "is a turn allowed right now" — a single enum plus one guard clause
in `Game.playSingleTurn()` expresses this completely. Introducing
`NotStartedState`/`InProgressState`/`FinishedState` classes with a
`handleTurn()` method each would be classic overengineering: three classes
and an interface to replace one `if` statement, for a lifecycle that is
never going to grow a fourth state with genuinely different turn-handling
behavior. If a future requirement introduced states with materially
different *behavior* (not just different "unlocked" operations) — e.g. a
`PAUSED` state that buffers incoming rolls — the full State pattern would
become justified, and `README.md §16` revisits this exact trade-off.

---

## 8. API and Method Design

### Public construction API
```java
Board board = new BoardBuilder()
        .withSize(10)
        .addLadder(2, 23)
        .addSnake(32, 10)
        .build(); // throws InvalidBoardConfigurationException on any invariant violation

Game game = Game.builder()
        .withBoard(board)
        .addPlayer(Player.of("Alice"))
        .addPlayer(Player.of("Bob"))
        .withDice(SingleDie.seeded(6, 42L))           // optional, defaults to a real die
        .withWinCondition(new ExactLandingWinCondition()) // optional, defaults to overshoot-clamp
        .addListener(new ConsoleGameEventListener())  // optional, zero or more
        .withMaxTurns(5_000)                          // optional, defaults to 10,000
        .build(); // throws InsufficientPlayersException / NullPointerException
```

### Public runtime API (`Game`)
| Method | Signature | Throws | Notes |
|---|---|---|---|
| `start` | `void start()` | `GameNotInProgressException` if not `NOT_STARTED` | Transitions `NOT_STARTED -> IN_PROGRESS`. |
| `playSingleTurn` | `void playSingleTurn()` | `GameNotInProgressException` if not `IN_PROGRESS` | Plays exactly one player's turn; publishes events as a side effect. |
| `playUntilWinner` | `Player playUntilWinner()` | `GameDidNotTerminateException` if `maxTurns` exceeded | Auto-starts if `NOT_STARTED`; loops `playSingleTurn()`. |
| `state` | `GameState state()` | — | Current lifecycle state. |
| `winner` | `Optional<Player> winner()` | — | Empty until a player wins. |
| `players` | `List<Player> players()` | — | Unmodifiable (`List.copyOf` at construction). |
| `board` | `Board board()` | — | The immutable board in play. |
| `turnsTaken` | `int turnsTaken()` | — | Count of turns in which a player actually moved. |
| `addListener` | `void addListener(GameEventListener listener)` | `NullPointerException` | Can be called after construction (e.g. attach a listener mid-game for debugging). |

### Validation responsibilities
- **`Jump`'s compact constructor**: structural validity of a single jump
  (cannot construct an invalid `Jump` under any circumstance, by any
  caller, ever — the strongest possible guarantee).
- **`BoardBuilder`**: cross-jump and board-wide invariants (ranges,
  duplicates, entry/final cell restriction, cycles).
- **`TurnManager`**: minimum player count (duplicated as a second line of
  defense; `GameBuilder` already enforces this, but `TurnManager` does not
  trust its caller blindly since it is a public, independently
  constructible class).
- **`GameBuilder`**: board presence, minimum player count, defaults for
  optional collaborators.
- **`Game`**: lifecycle-state preconditions for every state-sensitive
  operation (`playSingleTurn`, `start`).

### Exceptions (all unchecked — see §9 rationale for why)
`InvalidBoardConfigurationException`, `InsufficientPlayersException`,
`GameNotInProgressException` (extends `IllegalStateException`),
`GameDidNotTerminateException`.

---

## 9. Core Implementation

The full, compiling implementation lives in `src/main/java/lld/snakeandladder`.
The components most central to the design are reproduced here for
reference; see the source files for complete Javadoc.

### `model/Jump.java` — the unifying abstraction
```java
public record Jump(int start, int end, JumpType type) {

    public Jump {
        if (start <= 0 || end <= 0) {
            throw new IllegalArgumentException(
                    "Jump cells must be positive: start=" + start + ", end=" + end);
        }
        if (start == end) {
            throw new IllegalArgumentException(
                    "Jump start and end must differ (got " + start + " -> " + end + ")");
        }
        if (type == JumpType.LADDER && end <= start) {
            throw new IllegalArgumentException("Ladder must move forward: " + start + " -> " + end);
        }
        if (type == JumpType.SNAKE && end >= start) {
            throw new IllegalArgumentException("Snake must move backward: " + start + " -> " + end);
        }
    }

    public static Jump ladder(int start, int end) { return new Jump(start, end, JumpType.LADDER); }
    public static Jump snake(int start, int end) { return new Jump(start, end, JumpType.SNAKE); }
}
```

### `engine/Game.java` — the turn loop (core logic, abridged)
```java
public void playSingleTurn() {
    if (state != GameState.IN_PROGRESS) {
        throw new GameNotInProgressException("Cannot play a turn while game is in state " + state);
    }
    turnAttempts++;

    Player current = turnManager.pollCurrent();
    int rolled = dice.roll();
    int fromPosition = current.position();

    MoveOutcome outcome = winCondition.evaluate(fromPosition, rolled, board.totalCells());

    if (!outcome.moved()) {
        turnManager.requeue(current);
        publish(new TurnSkippedEvent(current, fromPosition, rolled,
                "roll would overshoot the final cell " + board.totalCells()));
        return;
    }

    int afterDiceMove = outcome.newPosition();
    current.moveTo(afterDiceMove);
    publish(new PlayerMovedEvent(current, fromPosition, afterDiceMove, rolled));

    int finalPosition = applyBoardJumpIfAny(current, afterDiceMove);
    turnsTaken++;

    if (outcome.isWinningMove()) {
        current.moveTo(finalPosition);
        winner = current;
        state = GameState.FINISHED;
        publish(new PlayerWonEvent(current, turnsTaken));
        return;
    }

    turnManager.requeue(current);
}
```

Full files: [`model/Player.java`](src/main/java/lld/snakeandladder/model/Player.java),
[`model/Board.java`](src/main/java/lld/snakeandladder/model/Board.java),
[`board/BoardBuilder.java`](src/main/java/lld/snakeandladder/board/BoardBuilder.java),
[`dice/Dice.java`](src/main/java/lld/snakeandladder/dice/Dice.java) and its
three implementations,
[`rules/WinCondition.java`](src/main/java/lld/snakeandladder/rules/WinCondition.java)
and its two implementations,
[`turn/TurnManager.java`](src/main/java/lld/snakeandladder/turn/TurnManager.java),
[`events/`](src/main/java/lld/snakeandladder/events) (sealed event
hierarchy + observer interface),
[`engine/Game.java`](src/main/java/lld/snakeandladder/engine/Game.java),
[`engine/GameBuilder.java`](src/main/java/lld/snakeandladder/engine/GameBuilder.java),
and the runnable [`Demo.java`](src/main/java/lld/snakeandladder/Demo.java).

---

## 10. Data Structures and Algorithms

| Structure | Used for | Why | Alternative | Complexity |
|---|---|---|---|---|
| `HashMap<Integer, Jump>` (`Board.jumpsByStart`) | Resolving "is there a jump starting at cell X" | O(1) average lookup is exactly what's needed every single turn; the key space (`1..totalCells`) is small and dense, so a plain array (`Jump[totalCells+1]`) would also work and is arguably a micro-optimization worth mentioning live — rejected here in favor of `Map` because `totalCells` can be up to 10,000 (`size=100`) and most boards configure far fewer jumps than that, so a sparse map avoids allocating a largely-empty array. | `Jump[]` indexed array | O(1) lookup either way; `HashMap` O(k) space for *k* configured jumps vs. O(totalCells) for the array |
| `ArrayDeque<Player>` (`TurnManager.order`) | Round-robin turn rotation | Push-to-back / pop-from-front is the exact "line of players waiting their turn" semantics, both O(1) | Circular index over `List<Player>` | O(1) either way for rotation; the array-index alternative is O(1) for "who's next" but O(n) for removing a specific player mid-game (disconnect handling), whereas the deque is O(n) for *both* — see §16 for the full trade-off table |
| `Set<Integer>` (`visiting`/`visited` in `BoardBuilder.detectCycles`) | Cycle detection in the jump graph | Standard DFS 2-color (white/gray/black, collapsed to two sets since "white" is simply "absent from both") cycle detection over the directed graph implied by `start -> end` edges restricted to jump-start nodes | Union-Find (for undirected cycle detection) | Not applicable — Union-Find detects cycles in *undirected* graphs; jump edges are directed (`A -> B` does not imply `B -> A` participates the same way), so DFS coloring is the correct and simplest tool, O(V+E) where V, E are bounded by the number of configured jumps |
| `List.copyOf(...)` (`Game.players`, `Board` jump map via `Map.copyOf`) | Defensive immutability | O(n) one-time copy at construction trades a small upfront cost for an ironclad "no caller can ever mutate this from outside" guarantee, which matters because `Board` may be shared across games | Returning the caller's own mutable list/map directly | O(1) but allows an external caller to corrupt shared state after the fact — rejected given `Board`'s shareability goal |
| `AtomicInteger` cursor (`FixedSequenceDice`) | Test-dice scripted sequence index | Lets the fixed-sequence dice be safely shared across threads if a test ever needs that, at negligible cost | Plain `int` field | Plain `int` would be fine for the documented single-threaded-per-game usage, but costs nothing to make atomic here and removes one more thing a future reader has to double check |

**Algorithmic note on the win condition.** Both `WinCondition`
implementations are O(1) — a single addition and comparison. There is no
asymptotic complexity story here; this problem's complexity entirely comes
from state-machine correctness (the turn loop, the lifecycle guards, the
jump resolution ordering), not from algorithmic heavy lifting. Candidates
who spend interview time optimizing an O(1) operation are optimizing the
wrong thing.

---

## 11. Concurrency and Thread Safety

**Baseline model: single-writer-per-game.** Exactly one logical "turn
taker" drives a given `Game` instance at a time — this mirrors the
physical game (one person rolls the dice at a time) and is the assumption
stated explicitly in §3 Q8. Under this model:

- **`Player.position`** is declared `volatile`. This is a deliberate,
  narrow guarantee: it ensures that if a *different* thread later reads a
  player's position (e.g. a monitoring thread periodically snapshotting
  all players' positions for a dashboard, without itself driving turns),
  it sees the latest write without needing its own lock, because `volatile`
  establishes a happens-before edge on every write. It does **not** make
  `Player` safe for concurrent *mutation* — two threads calling
  `moveTo()` concurrently would still race (last-write-wins, no atomicity
  of read-modify-write), which is acceptable because the documented
  contract is single-writer. This is a case where a `volatile` field gives
  a genuinely useful, narrow, correctly-justified guarantee (safe
  *publication* to concurrent readers) without the cost or complexity of
  a lock, precisely because the actual concurrent-mutation case is out of
  contract.
- **`Board`** is fully immutable after `BoardBuilder.build()` returns
  (`Map.copyOf` defensively snapshots the jump map). This means a single
  `Board` instance can be legitimately shared across many concurrently
  running `Game` instances — e.g. a server holding one canonical "standard
  10x10 board" constant reused by every new game room — with zero
  synchronization needed, because there is no mutable state to race on.
- **`TurnManager`** is backed by a plain `ArrayDeque`, which is explicitly
  documented as not thread-safe. This is intentional and matches the
  single-writer contract: synchronizing a structure that only one thread
  ever touches would be pure overhead with no safety benefit.
- **`Game` itself** holds no explicit locks. Its fields (`state`, `winner`,
  `turnsTaken`, `turnAttempts`) are ordinary (non-volatile, non-atomic)
  because they are only ever read-or-written by the single turn-taking
  thread, consistent with the documented contract. If a future requirement
  needed a *monitoring* thread to safely read `Game.state()` /
  `Game.winner()` concurrently with the turn-taking thread writing them
  (analogous to the `Player.position` case above), the fix is the same
  narrow tool: make those three fields `volatile` (or wrap `state` in an
  `AtomicReference<GameState>`), **not** a lock around the whole turn loop
  — locking the turn loop would only be necessary if *multiple* threads
  needed to *drive* turns concurrently, which is explicitly out of
  contract.

**What would have to change for true multi-writer concurrency** (e.g. a
server where a race between two near-simultaneous HTTP requests for the
same game must be resolved safely, not just prevented by convention):

1. `Game.playSingleTurn()` would need to become atomic as a whole —
   wrapping the entire method body in a `synchronized` block (or an
   explicit `ReentrantLock`) guarded by the `Game` instance itself, since
   the method performs a multi-step read-modify-write sequence
   (poll player → roll → evaluate → mutate position → maybe mutate
   `state`/`winner` → requeue) that must not be interleaved.
2. A `synchronized` method-level lock is the right granularity here, not
   finer-grained per-field locking: the whole point of one turn is that
   its steps are causally dependent (you cannot evaluate a win condition
   before you know the roll; you cannot apply a jump before you know the
   post-roll position), so finer-grained locking would still require
   holding multiple locks for the duration of the method, i.e., no real
   benefit, only more opportunities to introduce a lock-ordering bug.
3. This single coarse lock per `Game` is **not** a scalability problem in
   practice: `playSingleTurn()`'s body does no I/O and no unbounded work
   (one dice roll, one map lookup, a handful of field writes, and
   notifying a small, bounded list of listeners), so it is held for
   nanoseconds. Lock contention would only matter under a pathological
   workload where many concurrent "submit my roll" requests pile up
   against the *same single game* — which is not a realistic access
   pattern (a board game has 2–N players, not thousands of concurrent
   requesters).
4. **Idempotency** becomes relevant once network retries enter the
   picture (e.g. a client retries a "submit roll" request after a timeout,
   not knowing whether the first attempt already succeeded). The current
   design has no request-id concept because it is a pure in-memory
   library, not a request-handling service; a production wrapper would
   need to assign each `playSingleTurn()` invocation a client-supplied
   idempotency key and short-circuit a duplicate before touching game
   state — see §17.
5. **Deadlock risk** with a single per-`Game` lock is zero by construction
   (a single lock can never deadlock against itself; deadlock requires at
   least two locks acquired in inconsistent order, which cannot happen
   here since no method ever needs to hold two different `Game` locks
   simultaneously — one game never calls into another game's locked
   method).

**Why no `java.util.concurrent` collections were introduced speculatively.**
It is tempting to reach for `ConcurrentHashMap` or `CopyOnWriteArrayList`
"to be safe." Given the single-writer contract, every such substitution
would add synchronization overhead for zero correctness benefit and would
also be *actively misleading* to the next reader, who would reasonably
infer from a `ConcurrentHashMap` field that multiple threads are expected
to write concurrently, when they are not. Thread-safety claims in this
codebase are meant to be exactly as strong as justified, and no stronger.

---

## 12. Persistence and Database Design

**This design is intentionally pure in-memory, and a database is
unnecessary for the interview-scoped problem.** The entire state of a game
— board configuration, player positions, turn order, lifecycle state — fits
comfortably in a few kilobytes of heap and has no requirement to survive a
process restart within the stated scope (§1 explicitly lists persistence
as out of scope).

**If persistence became a requirement** (e.g. "the game server can be
restarted mid-game without losing state," or "players can resume a game
from a different device"), the natural extension is:

- **Tables** (relational sketch, for a service wrapping this library):
  - `games(id PK, board_size, win_condition_type, state, winner_player_id NULLABLE, created_at, updated_at)`
  - `jumps(game_id FK, start_cell, end_cell, type)` — composite PK
    `(game_id, start_cell)`, mirroring the uniqueness invariant already
    enforced in `BoardBuilder`.
  - `players(id PK, game_id FK, name, position, turn_order_index)` —
    `turn_order_index` replaces the in-memory `Deque` ordering with a
    persisted integer so turn order survives a restart.
  - `game_events(id PK, game_id FK, sequence_no, event_type, payload_json, created_at)`
    — an append-only audit log, useful for replay/debugging and for
    rebuilding in-memory state after a restart (event sourcing).
- **Indexes:** `games(state)` for "find all in-progress games" (a
  dashboard/cleanup-job query); `players(game_id, turn_order_index)` for
  reconstructing turn order; `game_events(game_id, sequence_no)` for
  ordered replay.
- **Transactions / isolation:** a single turn's state change (player
  position update + possible `games.state`/`winner_player_id` update +
  appending a `game_events` row) must be one atomic transaction —
  `READ COMMITTED` is sufficient because, per §11, only one logical writer
  ever touches a given game's rows at a time; no cross-row/cross-game
  transaction is ever required, so a lighter isolation level is safe and
  preferable for throughput.
- **In-memory vs. persistent trade-off:** persisting every turn to a
  database would convert an O(1)-per-turn in-memory operation into a
  network round-trip per turn, which is the dominant cost at that point
  (see §14) — an explicit, deliberate cost that only production deployment
  would justify paying.

---

## 13. Failure Scenarios

| Scenario | Handling |
|---|---|
| **A `Board` is configured with a cyclic jump graph** (e.g. ladder 10→20, snake 20→10). | Rejected at `BoardBuilder.build()` time via DFS cycle detection, *before* any `Game` can ever be constructed with it — the failure is caught at the earliest possible point, not discovered mid-game when `resolveChained` would otherwise loop forever. |
| **A roll is rejected under `ExactLandingWinCondition` every single turn** (pathological/adversarial dice, e.g. a `FixedSequenceDice` misconfigured by a caller to always overshoot). | This is exactly the "partial progress with no termination" failure mode. `Game.playUntilWinner()` counts **every** `playSingleTurn()` *attempt* (`turnAttempts`), including rejected ones, against `maxTurns` — this was a real bug caught during test-driven verification of this design (see note below) and is now covered by `GameTest.playUntilWinnerThrowsWhenMaxTurnsIsExceeded`, which deliberately configures a dice/win-condition combination that always overshoots and asserts `GameDidNotTerminateException` is thrown within a small `maxTurns` budget rather than hanging. |
| **A caller calls `playSingleTurn()` before `start()`, or after the game has already `FINISHED`.** | `GameNotInProgressException` (an `IllegalStateException` subtype), caught by `GameTest.playSingleTurnThrowsBeforeGameIsStarted` and the post-win assertion in `GameTest.reachingFinalCellEndsGameAndRecordsWinner`. |
| **`GameBuilder.build()` is called with fewer than 2 players.** | `InsufficientPlayersException`, before any `Game`/`TurnManager` object is even constructed — `TurnManager`'s own constructor enforces the same minimum as a second line of defense in case it is ever constructed directly outside `GameBuilder`. |
| **`GameBuilder.build()` is called with no board.** | `NullPointerException` via `Objects.requireNonNull`, with a descriptive message — deliberately *not* swallowed into a softer "default board" fallback, because silently substituting a board the caller never asked for would be a much worse failure mode than a loud, immediate `NullPointerException`. |
| **A player's computed position would go negative** (should be unreachable given the validated `Jump`/`WinCondition` invariants, but defended anyway). | `Player.moveTo()` throws `IllegalArgumentException` rather than silently clamping to 0 — a defensive invariant check at the lowest-level mutator, consistent with "fail loud, fail early" throughout this design. |
| **Resource exhaustion: an unbounded number of players or an enormous board.** | `BoardBuilder` caps `size` at 100 (10,000 cells) — chosen as a generous but finite ceiling; `Game`/`TurnManager` place no hard cap on player count (memory is the only practical limit, and a `List`/`Deque` of players is cheap even at very large N), which is an intentional asymmetry: board size directly drives the jump-validation and (for `resolveChained`) hop-count work, whereas player count does not multiply any per-turn cost beyond the O(1) deque rotation. |
| **Duplicate/retried turn submissions** (a production-service concern, not applicable to the in-memory library directly). | Out of scope for the library itself (no request/response boundary exists to retry across) — addressed as an idempotency-key design in §11 point 4 and §17 for the service-wrapper extension. |
| **Corruption: an external caller mutates a `Board`'s jump map or a `Game`'s player list after construction.** | Structurally prevented, not just discouraged: `Board.jumps()` returns the result of `Map.copyOf(...)` taken at construction time (an unmodifiable view that throws `UnsupportedOperationException` on any mutation attempt), and `Game.players()` similarly returns a `List.copyOf(...)` snapshot. |
| **Cleanup:** a `Game` or `Board` instance is simply dropped. | No explicit cleanup/close() is required — every object in this design is a plain heap object with no external resources (no file handles, sockets, or threads), so ordinary garbage collection suffices. |

---

## 14. Performance and Scalability

**Single-process, single-game performance.** Every operation on the hot
path of a turn is O(1): `dice.roll()`, `board.jumpStartingAt()` (hash map
lookup), `winCondition.evaluate()` (arithmetic), `turnManager.pollCurrent()`/
`requeue()` (deque operations), and notifying a small, bounded list of
listeners. A full game on a classic 10x10 board, even with an unlucky
sequence of snake bites, completes in a small, bounded number of turns in
practice (and is hard-capped by `maxTurns` in the worst case) — this is
simply not a performance-bound problem at the scale of "one game."

**Multi-threaded (single process, many concurrent games).** The real
scaling axis for this problem is *the number of independent, concurrently
running games*, not the size of any one game. Since each `Game` is
independently single-writer (§11) and shares no mutable state with any
other `Game` (a shared `Board` is immutable and therefore not a source of
contention), this scales embarrassingly in parallel: N games on N
independent threads have zero cross-game synchronization cost. The only
shared, mutable, process-wide resource that *could* become a bottleneck is
a naive "registry of all live games" (e.g. a single `HashMap<GameId, Game>`
guarded by one lock) — the fix is the standard one, a `ConcurrentHashMap`
keyed by game id, which trivially supports concurrent lookups/inserts
without games contending with each other.

**Distributed extension (discussed, explicitly not built here — "do not
over-distribute an LLD problem").** If game state needed to be shared
across multiple server processes (e.g. for horizontal scaling behind a
load balancer with no sticky sessions), each `Game` would need to live in
a shared store (Redis/a database) keyed by game id, and `playSingleTurn()`
would need the distributed-equivalent of the single-process lock from
§11 — e.g. a per-game distributed lock, or (preferably, to avoid lock
contention and lock-expiry edge cases entirely) routing every request for
a given game id to the *same* server process via consistent hashing, so
that within that process the original single-writer-per-game in-memory
model still applies unchanged. This is explicitly called out as *not*
needed for the stated problem scope, and introducing it unprompted would
be a staff-level anti-pattern (solving a scaling problem the interviewer
never asked for, at the cost of interview time better spent elsewhere).

**Bottleneck identification exercise (see `STAFF_LEVEL_DEEP_DIVE.md` Q13
for the full "10M users" discussion):** the first thing to break under
real load is never the per-turn game logic (O(1) and sub-microsecond) —
it is always either (a) a naive shared-lock game registry, or (b) whatever
I/O-bound layer wraps this library in production (a database write per
turn, a WebSocket broadcast fan-out, etc.), none of which exist in the
in-memory library itself.

---

## 15. Testing Strategy

The full test suite lives in `tests/lld/snakeandladder` (98 JUnit 5 tests,
all passing — see the repository's verification note at the end of this
document) and is organized by collaborator, mirroring the domain model:

- **`BoardTest`** — unit + boundary + negative: valid board construction,
  single-hop vs. chained resolution, every rejected configuration (ladder
  moving backward, snake moving forward, duplicate start cells, jump at
  the entry/final cell, out-of-range cells, cyclic jump graphs, board size
  outside `[3,100]`).
- **`DiceTest`** — unit + statistical boundary + determinism: a
  `@RepeatedTest(50)` statistical check that a real die always stays
  within `[1,6]`; a determinism test proving two `SingleDie` instances
  seeded identically produce an identical 20-roll sequence (the single
  most important test in this suite for demonstrating *why* the `Dice`
  abstraction was worth building); a complementary test that two
  differently-seeded dice are *not* expected to coincide; `CompositeDice`
  summation and range composition; `FixedSequenceDice` exact replay and
  cycling behavior.
- **`WinConditionTest`** — unit + boundary, the most under-tested area in
  typical candidate solutions: the exact boundary at the final cell for
  both strategies (landing exactly on it, overshooting it, landing short
  of it) — six tests covering every combination of the two strategies
  against the three cases.
- **`TurnManagerTest`** — unit: round-robin rotation order across a full
  cycle, `peekCurrent()` idempotency (peeking twice must not advance the
  queue), minimum-player-count rejection, and the "a winner is not
  requeued" scenario that the `Game` loop relies on.
- **`PlayerTest`** — unit: identity-based `equals`/`hashCode` (same id,
  different name ⇒ equal; different id, same name ⇒ not equal), starting
  position, blank-name rejection, and negative-position rejection.
- **`GameTest`** — integration, the backbone of the suite, entirely
  deterministic via `FixedSequenceDice`:
  - lifecycle guard before `start()`,
  - a single turn moving the current player and correctly rotating to the
    next,
  - a ladder climb resolving within the same turn, with events published
    in the correct order (`PlayerMovedEvent` then `LadderClimbEvent`),
  - reaching the final cell ending the game, recording the winner, and
    rejecting any further turn,
  - `ExactLandingWinCondition` correctly rejecting (not just ignoring) an
    overshooting roll, leaving the player's position unchanged and
    publishing `TurnSkippedEvent`,
  - **failure injection:** `playUntilWinnerThrowsWhenMaxTurnsIsExceeded`
    deliberately constructs a dice/win-condition combination that can
    *never* produce a winner (always overshoots under exact-landing rules)
    and asserts the engine fails loudly within a small `maxTurns` budget —
    this is the test that caught the real `turnsTaken` vs. `turnAttempts`
    bug described in §13,
  - `GameBuilder` validation (`InsufficientPlayersException`,
    `NullPointerException` for a missing board),
  - three-player round-robin rotation across more than one full cycle.

**Representative test** (from `GameTest.java`), chosen because it
exercises the trickiest ordering guarantee in the whole design — that a
board jump is resolved and its event published *within* the same turn
that triggered it, before the turn rotates to the next player:

```java
@Test
void ladderClimbIsAppliedDuringTheSameTurnAsTheRoll() {
    Board board = new BoardBuilder().withSize(10).addLadder(3, 23).build();
    Player alice = Player.of("Alice");
    Player bob = Player.of("Bob");
    List<GameEvent> events = new ArrayList<>();

    Game game = Game.builder()
            .withBoard(board)
            .addPlayer(alice)
            .addPlayer(bob)
            .withDice(FixedSequenceDice.of(3))
            .addListener(events::add)
            .build();
    game.start();
    game.playSingleTurn();

    assertEquals(23, alice.position());
    assertEquals(2, events.size()); // PlayerMovedEvent, then LadderClimbEvent
    assertTrue(events.get(0) instanceof PlayerMovedEvent);
    assertTrue(events.get(1) instanceof LadderClimbEvent);
}
```

**What is deliberately not tested:** true multi-threaded concurrent access
to a single `Game` — per §11, this is explicitly out of contract, so a
concurrency stress test would be testing a scenario the design does not
claim to support, which would produce a misleading green/red signal either
way. If multi-writer support were added (§11's extension), a dedicated
concurrency test (e.g. N threads calling `playSingleTurn()` on a shared,
now-synchronized `Game` and asserting no roll is lost/duplicated) would
become a required addition at that time.

---

## 16. Trade-offs and Alternatives

| Decision | Chosen approach | Alternative | Pros/cons | When the alternative wins |
|---|---|---|---|---|
| Snake/ladder modeling | Unified `Jump(start, end, type)` | Separate `Snake`/`Ladder` classes (or two parallel maps on `Board`) | Chosen: one validation path, one resolution path, trivially extensible to a third jump flavor. Alternative: more "obviously named" classes, but duplicates every piece of logic that doesn't actually differ. | If snakes and ladders ever needed genuinely different *behavior* (not just direction) — e.g. ladders cost a turn to climb but snakes are instant — separate classes implementing a shared interface would become the better fit, since then the behavioral divergence is real, not just cosmetic. |
| Turn rotation structure | `ArrayDeque<Player>` | Circular index (`int currentIndex`) over `List<Player>` | Chosen: O(1) natural "move to back of line" semantics; reads cleanly. Alternative: O(1) "who's next," O(1) rotation via modular index, and (crucially) O(1) mid-list removal is also awkward for *both* approaches without shifting indices or leaving tombstones. | If player count needed to support efficient *mid-game removal* at scale (e.g. hundreds of players with frequent disconnects), neither structure is great as-is; a doubly-linked structure with per-player removal handles would be worth the added complexity only at that scale. |
| Win-condition overshoot handling | Strategy interface (`WinCondition`) with two shipped implementations | A single boolean flag (`boolean requireExactLanding`) on `Game`/`GameBuilder` | Chosen: unlimited future variants (e.g. "bounce back") require zero changes to `Game`; each variant is independently unit-testable. Alternative: fewer files for exactly two variants, but a boolean flag doesn't scale past two and conflates policy with the orchestrator. | If the problem statement guaranteed *only ever two* variants, forever, the boolean flag would be marginally simpler — but that guarantee is never available in practice, and this problem statement explicitly asks for extensibility. |
| Jump chaining | Single-hop by default; chaining available via an explicit alternate method (`resolveChained`), not wired into `Game` | Always resolve chains automatically | Chosen: matches the classic rules most interviewers expect by default, while still demonstrating the chaining *capability* (and its cycle-safety) without silently changing game behavior for the common case. Alternative: "more complete" simulation of a house rule nobody asked for, at the cost of surprising behavior relative to the classic rules. | If the interviewer explicitly asks for chained jumps as the *default* behavior, flipping `Game` to call `resolveChained` instead of `resolveSingleHop` is a one-line change — the cycle-detection safety net in `BoardBuilder` was built in from the start specifically so this flip is safe. |
| `GameState` modeling | Plain 3-value enum + guard clauses | Full GoF State pattern (one class per state) | Chosen: proportionate to three states with no per-state polymorphic behavior. Alternative: "more extensible" in the abstract, but adds three classes and an interface for what is currently one `if` statement. | If a new state needed genuinely different *behavior* (not just different allowed operations) — e.g. a `PAUSED` state buffering incoming moves and replaying them on resume — the full State pattern earns its complexity at that point, not before. |
| Dice determinism for tests | A dedicated `FixedSequenceDice` strategy class | Mocking `Dice` with a mocking framework (e.g. Mockito) | Chosen: zero test dependencies beyond JUnit 5 itself, and the scripted-sequence behavior (including cycling) is itself a small piece of genuinely reusable, independently testable production code, not test-only scaffolding bolted on with a framework. Alternative: less code to write per test, but couples the test suite to a mocking framework for a case (a stateful, repeatable sequence) that a hand-written strategy expresses just as clearly. | If the system under test had many more collaborators needing similar scripting, a mocking framework's verification/stubbing DSL would reduce boilerplate faster than hand-writing a new `FixedXxx` class per collaborator. |
| `Player` mutability | A single mutable `volatile int position` field on an otherwise-identity-based entity | A fully immutable `Player` where every move creates a new `Player` instance, and `Game` tracks the *current* `Player` instance per id in a map | Chosen: avoids rebuilding map entries and reference bookkeeping every single turn, for a field that is conceptually "this player's state," which is the textbook case for a mutable entity rather than a value object. Alternative: perfectly consistent with "immutability everywhere," but adds bookkeeping cost and conceptual overhead for no safety benefit given the single-writer contract (§11). | In a system that needed to keep a full history of every past position (e.g. to support "undo" or full event-sourced replay from events alone, never touching the live `Player` object), the immutable-snapshot-per-move approach would directly provide that history essentially for free. |

---

## 17. Production Evolution

Concerns intentionally kept **separate** from the core interview solution,
to be discussed verbally rather than implemented:

- **Monitoring / metrics.** Per-game and aggregate counters: turns played,
  average turns to win, snake-bite / ladder-climb frequency (useful for
  balancing board design), `GameDidNotTerminateException` rate (a signal
  of a misconfigured or adversarial win-condition/dice combination in
  production), and active-game-count gauges for capacity planning.
- **Logging.** Replace `ConsoleGameEventListener` with a structured
  (JSON) logging listener attaching a `gameId`/`correlationId` to every
  `GameEvent`, suitable for ingestion by a log aggregation pipeline — the
  `GameEventListener` interface already provides exactly the seam needed
  for this, with zero changes to `Game`.
- **Distributed coordination.** Only relevant if games must be shared
  across multiple server processes (see §14); the recommended approach is
  routing by consistent hashing to keep the existing single-writer
  in-process model intact, rather than introducing distributed locks.
- **Persistence.** Full relational sketch in §12; an event-sourced
  `game_events` audit log doubles as both a durability mechanism and a
  natural input for analytics/replay tooling.
- **Fault tolerance.** A production wrapper would need: idempotency keys
  on "submit a roll" requests (per §11 point 4), a dead-letter path for
  games that throw `GameDidNotTerminateException` (flag for manual review
  rather than silently dropping), and health checks verifying the
  in-memory game registry and its backing store (if persisted) are in
  sync.
- **Operational concerns.** Graceful handling of a server restart
  mid-game (requires persistence, §12); rate-limiting "create game"
  requests per user to bound the live-game-registry's memory footprint;
  an admin API to forcibly end or inspect a stuck game.
- **API evolution / backward compatibility.** The sealed `GameEvent`
  hierarchy is a double-edged sword for API evolution: it gives
  compile-time exhaustiveness *within this codebase*, but a sealed
  interface's `permits` clause is a breaking change for any *external*
  consumer compiled against an older version if a new event type is added
  (such a consumer's exhaustive `instanceof` chain, if any, would need
  updating) — this is an explicit, acceptable trade-off for an
  interview-scoped, single-compilation-unit library, and would be
  revisited (e.g. by not sealing the public event type, or by publishing
  events as an open, additive-only hierarchy) if this code shipped as a
  public, independently-versioned API consumed by third parties.

---

### Clarifying Questions — assumptions log (cross-reference)

All assumptions from §3 are restated here for quick scanning: square board
(row-major numbering), at most one jump per start cell (no duplicate
starts), single-hop resolution by default with chaining available as an
explicit opt-in, configurable overshoot handling via `WinCondition`, no
jump may start at cell 1 or the final cell, dice fully pluggable via
`Dice`, no "must roll to enter" sub-state, no bonus-turn-on-6 rule, single
logical writer per game (no built-in multi-threaded access), and no
mid-game player removal.
