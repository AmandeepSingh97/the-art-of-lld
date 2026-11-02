# Implementation Guide — Snake and Ladder

A practical, sequence-driven guide to actually writing this code under
interview time pressure, including a 20–30 minute minimal-viable path.

---

## Which classes to write first

In dependency order, so every class you write compiles and is testable
before you move to the next:

1. `JumpType` (enum) — trivial, zero dependencies.
2. `Jump` (record) — depends only on `JumpType`; write its validating
   compact constructor immediately, don't defer validation.
3. `Board` — depends on `Jump`; keep its constructor dumb (just store
   what it's given) since validation belongs in the builder, not here.
4. `BoardBuilder` — depends on `Board`/`Jump`; this is where all the
   interesting validation logic lives.
5. `Player` — depends on nothing; simple mutable-position entity.
6. `GameState` (enum) — trivial.
7. `TurnManager` — depends on `Player`.
8. `Dice` (interface) + `SingleDie` — depends on nothing but the JDK.
9. `MoveOutcome` (record) + `WinCondition` (interface) +
   `OvershootClampWinCondition` — depends on nothing but the JDK.
10. `Game` + `GameBuilder` — the integration point; depends on everything
    above.
11. `GameEvent` hierarchy + `GameEventListener` — purely additive, write
    last since `Game` is fully functional and testable without it.
12. `ExactLandingWinCondition`, `CompositeDice`, `FixedSequenceDice`,
    `ConsoleGameEventListener` — variants/extras, write only if time
    remains after the core loop works and is tested.

---

## Minimal compilable implementation outline

The smallest version that actually works end-to-end (strip everything not
in this list for a time-boxed round):

```java
// JumpType.java
public enum JumpType { SNAKE, LADDER }

// Jump.java
public record Jump(int start, int end, JumpType type) {
    public Jump {
        if (start == end) throw new IllegalArgumentException("start == end");
        if (type == JumpType.LADDER && end <= start) throw new IllegalArgumentException("bad ladder");
        if (type == JumpType.SNAKE && end >= start) throw new IllegalArgumentException("bad snake");
    }
}

// Board.java
public final class Board {
    private final int totalCells;
    private final Map<Integer, Jump> jumps;
    public Board(int size, Map<Integer, Jump> jumps) {
        this.totalCells = size * size;
        this.jumps = Map.copyOf(jumps);
    }
    public int totalCells() { return totalCells; }
    public int resolve(int cell) {
        Jump j = jumps.get(cell);
        return j == null ? cell : j.end();
    }
}

// Player.java
public final class Player {
    private final String id;
    private int position = 0;
    public Player(String id) { this.id = id; }
    public int position() { return position; }
    public void moveTo(int p) { this.position = p; }
}

// Dice.java
public interface Dice { int roll(); }

// SingleDie.java
public final class SingleDie implements Dice {
    private final Random random;
    public SingleDie(Random random) { this.random = random; }
    public int roll() { return random.nextInt(6) + 1; }
}

// Game.java (minimal loop — no WinCondition strategy, no events, no builder)
public final class Game {
    private final Board board;
    private final Deque<Player> turnOrder;
    private final Dice dice;
    private Player winner;

    public Game(Board board, List<Player> players, Dice dice) {
        this.board = board;
        this.turnOrder = new ArrayDeque<>(players);
        this.dice = dice;
    }

    public void playSingleTurn() {
        Player current = turnOrder.pollFirst();
        int target = current.position() + dice.roll();
        if (target >= board.totalCells()) {
            current.moveTo(board.totalCells());
            winner = current;
            return;
        }
        current.moveTo(board.resolve(target));
        turnOrder.addLast(current);
    }

    public Player playUntilWinner() {
        while (winner == null) playSingleTurn();
        return winner;
    }
}
```

This compiles, runs, and demonstrates the core mechanic end-to-end in
roughly 60 lines. Everything in the full implementation is additive on top
of this: builders for validation, strategy interfaces for pluggability,
events for observability, and the `maxTurns` safety valve for guaranteed
termination.

---

## Suggested implementation sequence (narrated, time-boxed)

1. **(2 min)** Write `JumpType` and `Jump` together — say "I'll validate
   direction correctness right in the constructor so an invalid `Jump`
   can never exist."
2. **(3 min)** Write `Board` as a dumb data holder — say "construction
   validation belongs in a builder, which I'll add once the shape of
   `Board` is settled."
3. **(2 min)** Write `Player` — say "position is the one mutable field in
   this whole model; everything else I'm about to write will be
   immutable."
4. **(3 min)** Write `Dice` interface + `SingleDie` — say "I'm pulling
   this out as an interface now because I know I'll want a deterministic
   version for testing in a few minutes."
5. **(5 min)** Write the minimal `Game` loop (as above, no strategy for
   win condition yet) — get something *running* before polishing.
6. **(3 min)** Add `BoardBuilder` with validation — narrate each check as
   you add it ("no duplicate starts," "no jump at the entry or final
   cell").
7. **(3 min)** Extract `WinCondition` as a strategy, moving the inline
   overshoot-clamp logic out of `Game` into
   `OvershootClampWinCondition`, then add `ExactLandingWinCondition` as
   the second implementation — this is the moment to say "now the
   'exact landing' variant from the prompt is just a class, not a rewrite
   of `Game`."
8. **(2 min)** Extract `TurnManager` out of `Game`'s inline `Deque` if not
   already separate.
9. **(3 min)** Add `GameBuilder` for validated construction.
10. **(3 min)** Add the `maxTurns` safety valve to `playUntilWinner()` —
    narrate *why*: "a pathological dice/win-condition combination could
    reject every roll forever under exact-landing rules, so I need a hard
    ceiling, and it has to count every attempt, not just successful
    moves."
11. **(5+ min, if time allows)** Add the events/Observer layer.
12. **(remaining time)** Write tests — prioritize one deterministic
    `FixedSequenceDice`-based test over several `SingleDie`-based ones,
    since the former is actually reliable.

---

## Important methods (the ones worth getting exactly right)

- **`Jump`'s compact constructor** — this is the cheapest, highest-leverage
  correctness investment in the whole design: get this right and an
  entire category of invalid-board bugs becomes structurally impossible.
- **`BoardBuilder.build()`** — specifically the ordering of checks (size
  range, then per-jump cell range, then entry/final cell restriction,
  then cycle detection) — checking cheaper, more-local invariants before
  expensive/global ones (cycle detection) is both good practice and
  easier to narrate incrementally while coding live.
- **`Game.playSingleTurn()`** — the single most important method in the
  system; get the *order* of operations right and narrate it explicitly:
  roll → evaluate win condition → move → resolve board jump → check win
  → requeue (or don't, if won). Getting this order wrong (e.g. resolving
  the board jump before checking whether the dice-move alone already won)
  is the most common subtle bug candidates introduce live.
- **`playUntilWinner()`'s loop guard** — must count every turn *attempt*,
  not just successful moves (see the bug writeup in
  `STAFF_LEVEL_DEEP_DIVE.md` Q6) — if you're short on time, at minimum
  mention this requirement out loud even if you don't fully implement the
  separate counter.

---

## Common coding mistakes (and how this implementation avoids them)

| Mistake | Why it happens | How this implementation avoids it |
|---|---|---|
| Treating snakes and ladders as two parallel, duplicated code paths | Directly translating problem-statement nouns into classes without stepping back | Unified `Jump(start, end, type)` from the start |
| Hardcoding a single `new Random()` die directly inside `Game` | Not anticipating the "deterministic for testing" requirement until tests are being written | `Dice` extracted as an interface before `Game` is written |
| Checking "did I win" *after* applying the board jump, so a snake can un-win a player who already reached the final cell | Not reasoning carefully about operation order in the turn loop | `BoardBuilder` disallows any jump from starting on the final cell, so this is structurally impossible regardless of operation order — but the implementation order (win-check logic lives in `WinCondition`, evaluated on the raw dice-roll target, before jump resolution) is still deliberate |
| An infinite loop in `playUntilWinner()` when every roll is somehow rejected | Conflating "successful moves" with "loop iterations" in the safety-valve counter | Separate `turnAttempts` counter incremented unconditionally, independent of `turnsTaken` |
| A `Board`'s jump map being mutable and accidentally shared/corrupted across games | Returning a live reference to an internal collection instead of a defensive copy | `Map.copyOf(...)` in `Board`'s constructor, `List.copyOf(...)` for `Game.players()` |
| Flaky, non-deterministic tests asserting on real dice rolls | Not building a deterministic test double before writing tests | `FixedSequenceDice`, purpose-built for exact, repeatable test scenarios |
| A cyclic jump configuration silently causing an infinite loop the first time a rarely-hit chaining code path runs | Validating "this jump is individually well-formed" but never validating "the graph of all jumps together is well-formed" | `BoardBuilder.detectCycles()`, a DFS cycle check run once at build time |

---

## Edge cases to narrate even if not fully coded

- A roll that would overshoot the final cell (the central variant in the
  problem statement — never skip mentioning this, even under extreme time
  pressure).
- A jump starting at the very first cell or the final cell.
- Two jumps configured with the same start cell.
- A dice/win-condition combination that can never produce a winner.
- More than 2 players (explicitly verify your turn-rotation logic isn't
  hardcoded to exactly 2).
- A player landing exactly on a cell with no jump (the common case — make
  sure your jump-resolution code has a correct, cheap "no jump here"
  fast path, not an exception-based control flow).

---

## How to narrate the code while writing it

Narrate *decisions*, not syntax. Don't say "I'm writing a for loop" — say
"I'm validating every configured jump before I even know the board size
is valid, so I'll check size first and fail fast." Specifically:

- When writing `Jump`'s constructor: "I'm putting this validation directly
  on the value object, not in the builder, because I want it to be
  literally impossible to construct an invalid `Jump` from anywhere in
  the codebase, not just from this one entry point."
- When extracting `Dice`/`WinCondition` as interfaces: "I'm doing this now
  rather than retrofitting it later, because I already know from the
  requirements that both need to be swappable."
- When adding the `maxTurns` safety valve: "this game loop is
  probabilistically guaranteed to terminate, but not provably bounded —
  I want a hard ceiling so a pathological configuration fails loudly
  instead of hanging."
- When writing a test with `FixedSequenceDice`: "I'm scripting the exact
  dice sequence here so this test is deterministic — asserting on a real
  random die would make this test flaky."

---

## What can be safely omitted under time pressure

Ranked by how safe it is to cut, most-safe first:

1. `ConsoleGameEventListener` and the entire events/Observer layer —
   purely additive, the game works correctly without it.
2. `CompositeDice` and `FixedSequenceDice` — keep the `Dice` interface and
   `SingleDie`; mention the others verbally as "a 15-line follow-on."
3. `Board.resolveChained` and its cycle-detection — the game only needs
   single-hop resolution by default; mention chaining as a documented,
   deliberately-not-wired-in extension point.
4. `BoardBuilder`/`GameBuilder` as separate classes — fall back to direct
   constructors with inline validation if truly out of time, but say so
   explicitly rather than silently dropping validation.
5. **Never cut:** the core `Game.playSingleTurn()` loop, `Jump`'s
   validating constructor, and the overshoot-handling logic (inline is
   fine if `WinCondition` itself gets cut) — these three things are the
   actual substance of the problem.

---

## A simplified version implementable in 20–30 minutes

Use exactly the "Minimal compilable implementation outline" above as your
target, with these two additions if time allows a few more minutes:

1. Inline the overshoot-clamp logic directly in `Game.playSingleTurn()`
   (skip the `WinCondition` interface entirely) — but say out loud:
   "I'd extract this into a `WinCondition` strategy if the interviewer
   wants the 'exact landing' variant, since right now it's hardcoded."
2. Skip `BoardBuilder` entirely; validate inline in `Board`'s constructor
   with a few `if` checks (duplicate starts, cell range) — say: "I'd
   normally pull this into a builder once there's more than two or three
   validation rules, to keep `Board`'s constructor from becoming a wall
   of `if` statements."

This gets a working, demonstrable game (construct a small board, two
players, play to a winner, print the result) in well under 30 minutes,
leaving the remainder of a 45- or 60-minute slot for the extensibility
story (Strategy interfaces), the safety-valve termination argument, and
tests.
