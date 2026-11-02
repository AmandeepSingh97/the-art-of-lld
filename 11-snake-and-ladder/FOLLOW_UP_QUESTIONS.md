# Follow-Up Questions — Snake and Ladder

25+ realistic interviewer follow-ups, categorized, each with a complete
answer grounded in the actual implementation. These are distinct from
`STAFF_LEVEL_DEEP_DIVE.md`'s 24 architectural/strategic questions — this
file covers narrower, more conventional interview follow-ups across ten
standard categories.

---

## Object-Oriented Design

**1. Why is `Jump` a `record` instead of a regular class?**
A `record` is the idiomatic Java 17+ choice for an immutable value object
whose identity is entirely defined by its field values — `Jump(2, 23,
LADDER)` constructed anywhere is interchangeable with any other
`Jump(2, 23, LADDER)`. Records give structural `equals`/`hashCode`/
`toString` for free, which matters concretely here: `BoardBuilder` stores
jumps in a `Map<Integer, Jump>` and tests construct `Jump` instances
directly to assert board contents, both of which rely on correct value
equality. A regular class would require hand-writing (and keeping in sync)
the same `equals`/`hashCode` logic, with more room for the classic bug of
forgetting to update one after adding a field.

**2. Why does `Board` hold a `Map<Integer, Jump>` instead of two separate
lists, `List<Jump> snakes` and `List<Jump> ladders`?**
Because the resolution algorithm needs exactly one operation — "is there a
jump starting at cell X, and if so what's its destination" — regardless of
whether that jump happens to be a snake or a ladder. A single map keyed by
start cell makes that operation O(1) and single-code-path; two separate
lists would require checking both (either as two lookups or by merging
them into one lookup structure anyway, just doing the work BoardBuilder
already does once at construction time, repeatedly at resolution time).
The `JumpType` field already preserves which flavor a jump is for anyone
who needs to distinguish them (e.g. `ConsoleGameEventListener` reporting
"snake bite" vs. "ladder climb").

**3. Why is `TurnManager` a separate class instead of a `Deque<Player>`
field directly on `Game`?**
Single Responsibility: `Game` already orchestrates five collaborators
(`Board`, `Dice`, `WinCondition`, listeners, and now turn order) —
extracting turn-order management into its own class keeps `Game` focused
on *sequencing a turn*, not also on *the mechanics of whose turn is next*.
It also makes turn rotation independently unit-testable
(`TurnManagerTest`) without needing to construct a full `Game`, `Board`,
and `Dice` just to verify that three players rotate in the right order.

**4. Why does `Player` implement identity-based `equals`/`hashCode` on
`id` alone, excluding `name` and `position`?**
Because `Player` is a domain *entity*, not a value object — its identity
must remain stable across its entire lifecycle even as `position` changes
every turn. If `equals` included `position`, a `Set<Player>` or
`Map<Player, ...>` entry would silently become unreachable the moment that
player moved (its hash code would change), which is exactly the kind of
subtle collection-corruption bug that identity-based equality for mutable
entities is designed to prevent. Excluding `name` specifically
future-proofs against a hypothetical rename feature never silently
breaking object identity.

**5. Why does `GameBuilder` default to `OvershootClampWinCondition` rather
than requiring the caller to always specify one explicitly?**
To make the common case a one-liner: most callers (and the `Demo` class)
just want "a working game," and the friendlier, more casual overshoot-clamp
behavior is the more common real-world default rule. Requiring every
caller to explicitly choose between two equally-valid strategies for every
single game would be unnecessary ceremony for the 95% case; callers who
need `ExactLandingWinCondition` opt in explicitly via
`.withWinCondition(...)`.

---

## SOLID Principles

**6. Where does Single Responsibility show up most clearly in this design?**
`BoardBuilder` validates and constructs boards; `Board` only stores and
resolves; `TurnManager` only manages rotation; `Dice` only produces rolls;
`WinCondition` only evaluates moves. Each class has exactly one reason to
change: `BoardBuilder` changes if validation rules change; `Board` changes
only if the resolution algorithm itself changes (e.g. adding chaining);
these are genuinely independent axes of change, which is the real test of
SRP (not just "small classes").

**7. Where does Open/Closed show up most clearly?**
`Dice` and `WinCondition` are both closed for modification (neither
interface, nor `Game`, needs to change to support a new variant) and open
for extension (`CompositeDice`, `FixedSequenceDice`, and any future dice
strategy all just implement `Dice`). Adding "two dice" support required
zero changes to `Game`, `GameBuilder`, `Board`, or any existing class —
purely a new class implementing an existing interface.

**8. Does the Liskov Substitution Principle apply anywhere here, and is it
honored?**
Yes — any `Dice` implementation must be substitutable for any other
without `Game` behaving incorrectly, and the contract each implementation
must honor is "every `roll()` call returns a value within
`[minPossibleRoll(), maxPossibleRoll()]`." This is honored by all three
shipped implementations and is directly tested
(`DiceTest.singleDieAlwaysWithinDeclaredRange` via `@RepeatedTest(50)`
statistically verifying the contract for the randomized implementation,
and exact-value tests for the deterministic ones). The same applies to
`WinCondition`: any implementation must return a `MoveOutcome` whose
`newPosition` never exceeds `totalCells` (both shipped implementations
honor this — `ExactLandingWinCondition` by rejecting rather than clamping,
`OvershootClampWinCondition` by explicit clamping).

**9. Where does Interface Segregation apply?**
`GameEventListener` is a single-method functional interface
(`onEvent(GameEvent)`) rather than requiring implementors to provide five
separate callback methods (`onPlayerMoved`, `onSnakeBite`,
`onLadderClimb`, `onTurnSkipped`, `onPlayerWon`) — a listener that only
cares about wins doesn't need to implement four no-op methods; it just
pattern-matches on the one event type it cares about inside a single
`onEvent` method. This also makes the interface trivially usable as a
lambda in tests (`addListener(events::add)`).

**10. Where does Dependency Inversion apply?**
`Game` depends on the `Dice` and `WinCondition` *interfaces*, never on a
concrete implementation — `GameBuilder` is the one place concrete
implementations are chosen and injected (constructor injection into
`Game`). This is what makes `FixedSequenceDice` usable as a drop-in
substitute for `SingleDie` in every single `GameTest` without any
conditional/test-specific code inside `Game` itself.

---

## Design Patterns

**11. Is `GameBuilder.builder()` a Singleton?**
No — `Game.builder()` is a static factory method that returns a *new*
`GameBuilder` instance every call, not a shared singleton instance. This
is deliberate: a `GameBuilder` accumulates mutable configuration state
(players list, dice, etc.) across its fluent calls, so sharing one
instance across multiple game-construction call sites would leak
configuration between unrelated games — exactly the kind of unintended
shared-mutable-state bug an actual Singleton here would invite.

**12. Why is the Observer pattern implemented with a plain `List` of
listeners on `Game` rather than a more formal publish/subscribe bus (with
topics, unsubscribe, etc.)?**
Because the actual requirement is "notify a small, bounded set of
interested parties about this one game's events," not "route events
across an arbitrary number of topics to an arbitrary number of
subscribers across process boundaries" — the latter is a message-bus
problem, genuinely out of scope for an in-process game engine. A plain
`List<GameEventListener>` with no unsubscribe method is the simplest thing
that satisfies the actual requirement; adding unsubscribe or
topic-based routing before any requirement calls for it would be
speculative complexity.

**13. You rejected a full State pattern for `GameState` — what would
actually tip the balance toward using it?**
The moment a state needs genuinely different *behavior* for the same
operation, not just a different *allowed/disallowed* answer. Today, the
only state-dependent logic is "is `playSingleTurn()` allowed right now,"
which a guard clause expresses completely. If a `PAUSED` state were added
that needed to *buffer* incoming roll requests and replay them on resume
(different behavior for the same `playSingleTurn()` call depending on
state, not just allowed-vs-rejected), that's the point where a
`GameLifecycleState` interface with per-state `handleTurn()`
implementations would earn its complexity — this same trade-off is also
discussed in `README.md` §7 and §16.

**14. Could the Factory Method pattern be considered part of this design?**
Yes, in a lightweight, idiomatic form: `SingleDie.standardSixSided()`,
`SingleDie.seeded(faces, seed)`, `CompositeDice.twoStandardDice()`,
`Jump.ladder(start, end)`, `Jump.snake(start, end)`, and
`Player.of(name)`/`Player.withId(id, name)` are all static factory
methods providing named, discoverable construction paths that are more
readable than raw constructors (especially `Jump.ladder` vs. remembering
which `JumpType` enum constant corresponds to a forward move) — this is
Effective Java's "static factory methods" idiom rather than the full GoF
Factory Method pattern (there's no subclass-overridable creation logic
here), and it's worth being precise about that distinction if asked.

**15. Why didn't you use the Visitor pattern for handling the five
`GameEvent` subtypes in `ConsoleGameEventListener`?**
An `instanceof` chain was chosen over a formal Visitor (with an `accept`
method on `GameEvent` and a `GameEventVisitor` interface) because Java's
sealed interfaces already provide the main benefit Visitor is normally
used to simulate in languages without it — compiler-enforced
exhaustiveness is available today via an exhaustive `switch` over a sealed
type (stable since Java 21; this codebase targets Java 17+ as a baseline,
so an `instanceof` chain is used for portability, as documented directly
in `ConsoleGameEventListener`'s Javadoc) — introducing a full Visitor
hierarchy on top of an already-sealed, already-closed type hierarchy would
be redundant machinery solving a problem sealed interfaces already solve
more simply.

---

## Data Structures

**16. What's the time complexity of `BoardBuilder.detectCycles()`, precisely?**
O(V + E) where V and E are bounded by the number of configured jumps
(not the board's total cell count) — each jump contributes at most one
node (its start cell) and one outgoing edge (to its end cell) to the
graph being searched, and the DFS visits each node at most once due to the
`visited` set, with the `visiting` set providing O(1) back-edge (cycle)
detection during the active recursion path. For a realistic board with a
handful of jumps, this is effectively instantaneous.

**17. Why use a `Set<Integer>` pair (`visiting`/`visited`) instead of a
single `Map<Integer, Color>` with three states (white/gray/black)?**
They're equivalent in information content — "absent from both sets" is
exactly "white," presence in `visiting` is "gray," presence in `visited`
is "black" — the two-set formulation was chosen purely for readability
(checking `visiting.contains(cell)` reads more directly as "currently on
the active DFS path" than checking `colors.get(cell) == Color.GRAY` would,
without meaningfully changing the complexity or correctness of the
algorithm).

**18. Could `Board.jumpsByStart` be a simple array instead of a `HashMap`,
given cell numbers are small positive integers?**
Yes, functionally — a `Jump[totalCells + 1]` array indexed directly by
cell number would also give O(1) lookup, and would avoid `HashMap`'s
per-entry object overhead and hashing cost entirely. This was discussed
and deliberately not chosen (see README §10) because it trades a
sparse, pay-for-what-you-configure cost (`HashMap`, O(k) space for k
configured jumps) for a dense, pay-for-the-whole-board cost (array, O(n)
space for n total cells) — for the realistic case of a handful of jumps on
a board that could in principle be sized up to 10,000 cells, the sparse
structure is the better default; the array becomes preferable only if
profiling showed hash-map lookup overhead actually mattering, which it
does not at this problem's scale.

**19. Why `List.copyOf(...)` rather than `Collections.unmodifiableList(...)`
for `Game.players()`?**
`Collections.unmodifiableList` wraps the *original* mutable list — if the
caller who passed that list into `GameBuilder.addPlayers(...)` later
mutates their own original list reference, that mutation would leak
through the "unmodifiable" wrapper, since the wrapper is just a view, not
a copy. `List.copyOf(...)` takes a genuine defensive snapshot at the
moment it's called, fully decoupling `Game`'s internal state from
whatever the caller does with their own list afterward — a stronger,
more defensible immutability guarantee for the same amount of code.

**20. Why `AtomicInteger` for `FixedSequenceDice`'s cursor rather than a
`synchronized` method or a plain `int`?**
A plain `int` would suffice for this design's documented single-writer-
per-game contract (§11), but `AtomicInteger.getAndUpdate(...)` costs
essentially nothing extra and makes the class safely shareable across
threads if a future test or use case ever needed that (e.g. the same
`FixedSequenceDice` instance reused across multiple parallel test runs) —
a `synchronized` method would be strictly more expensive for the same
guarantee with no additional benefit, since `AtomicInteger` already
provides the needed atomicity for a single-field counter without lock
acquisition overhead.

---

## Java Internals

**21. How does Java 17's sealed interface feature actually help here
at the bytecode/compiler level, beyond documentation value?**
The `permits` clause is enforced by the compiler at the point every
implementing class is declared (each must either be `final`, `sealed`, or
`non-sealed`, and must be named in the permits list or in the same
compilation unit) — this means no code anywhere, in this module or a
future one, can introduce a sixth undocumented `GameEvent` implementation
without the compiler rejecting it unless `GameEvent`'s `permits` clause is
explicitly updated first. This is a genuine compile-time guarantee, not
just a naming convention, and it is what allows an exhaustive `switch`
expression over `GameEvent` (available without a `default` branch, on a
Java version where pattern-matching `switch` is stable) to be verified
complete by the compiler.

**22. Why does `java.util.Random` work as a `RandomGenerator` for
`SingleDie`, given the class predates the `RandomGenerator` interface by
decades?**
Java 17 retrofitted `java.util.Random` to implement the newer
`java.util.random.RandomGenerator` interface (introduced in JDK 17 as part
of JEP 356, the enhanced pseudo-random number generators effort) purely
via an interface addition — no change to `Random`'s existing behavior or
its `nextInt(bound)` semantics was needed, since `Random` already exposed
every method the new interface requires. This retrofit is exactly what
lets `SingleDie.seeded(faces, seed)` accept a plain seeded `new
Random(seed)` while still typing its field as the more general
`RandomGenerator` — a forward-looking design that would let a future
caller substitute a different `RandomGenerator` implementation (e.g. one
of the newer algorithms JEP 356 introduced, like `L64X128MixRandom`)
without any change to `SingleDie` itself.

**23. Are Java `record`s guaranteed to be immutable at the JVM level, or
just by convention?**
A record's fields are implicitly `private final` — genuinely enforced
immutability for the fields themselves, not merely a convention — but this
guarantee is shallow: if a record held a mutable field type (e.g. a
`List<Jump>` rather than `JumpType`), the record's own field reference
would be unreassignable, but the *referenced* mutable object could still
be mutated by any caller holding a reference to it. `Jump`'s fields
(`int`, `int`, `JumpType` enum) are all genuinely immutable primitive/enum
types, so `Jump` achieves true deep immutability, not just the shallow
guarantee records provide by default — this is exactly why `Board`'s
constructor defensively copies its `Map<Integer, Jump>` via
`Map.copyOf(...)` rather than relying on `Jump`'s immutability alone to
protect the *map* from external mutation (the map itself is a separate,
genuinely mutable structure unless defensively copied/wrapped).

**24. What does `@FunctionalInterface` on `GameEventListener` actually
enforce, versus just document?**
It's a compile-time check, not merely a comment: the compiler verifies
`GameEventListener` has exactly one abstract method, and would reject the
annotation (as a compile error) if a second abstract method were ever
accidentally added — this protects the lambda-usability property
(`addListener(events::add)` in tests, `event -> ...` in the demo) from
silently breaking if a future maintainer absent-mindedly added a second
method to the interface without realizing it would make the interface
no longer usable as a lambda target at every existing call site.

---

## Multithreading

**25. Is `SingleDie` thread-safe, given `java.util.Random` itself is
documented as thread-safe?**
`java.util.Random`'s own internal state (its seed) is indeed safely
updatable across concurrent threads via a compare-and-swap loop
internally — so multiple threads calling `roll()` on the *same*
`SingleDie` instance concurrently would not corrupt `Random`'s internal
state or crash. However, this does **not** mean concurrent rolls are
*meaningfully* safe for this design's purposes: two threads racing to call
`roll()` on behalf of what should be two sequential, ordered turns would
produce rolls in a non-deterministic interleaving order relative to which
player "should" get which value — which defeats the entire purpose of
`SingleDie.seeded(...)` for deterministic testing the moment more than one
thread is involved. This is precisely why the single-writer-per-game
contract (§11) applies to the *whole game*, not just to explicitly
mutable fields like `Player.position` — even a technically-thread-safe
collaborator like `Random` needs to be called in a defined sequence to
produce meaningful, reproducible results.

**26. Why does `Board` not need any `synchronized` or `volatile`
modifiers anywhere, given it may be shared across concurrently-running
games?**
Because every one of `Board`'s fields is assigned exactly once, in the
constructor, and never reassigned afterward (`size`, `totalCells`, and
`jumpsByStart` are all effectively final, with `jumpsByStart` additionally
being an unmodifiable copy) — the Java Memory Model guarantees that an
object whose fields are all set during construction and never mutated
afterward is safely published to any thread that obtains a reference to
it *after* construction completes, with no explicit synchronization
required on the reader's part. This is the direct, concrete payoff of
choosing immutability for `Board`: it's not just "safer to reason about,"
it is a specific, named Java Memory Model guarantee (safe publication of
effectively-final objects) that eliminates an entire category of
visibility bugs for free.

**27. If `Game.addListener(...)` is called from a different thread than
the one driving `playSingleTurn()`, is that safe?**
Not guaranteed by the current implementation — `listeners` is a plain
`ArrayList`, not a thread-safe collection, so a listener added
concurrently with an in-flight `publish(...)` iteration (a plain
for-each loop over the list) could, in the worst case, throw a
`ConcurrentModificationException` if the add happens mid-iteration, or
simply not be observed in time for events already in flight. Given the
documented single-writer-per-game contract, this scenario (a second
thread calling `addListener` while the first thread is mid-turn) is
already out of contract — but it's worth naming explicitly as a gap
rather than silently assuming `addListener` is safe to call from
"the UI thread" while a game is actively running on "the engine thread,"
which is a realistic-sounding but actually unsafe pattern under the
current implementation.

---

## Database and Persistence

**28. If you had to persist `Game` state to a relational database, what
would you use as the primary key, and why?**
A server-generated `gameId` (e.g. a UUID, matching the pattern already
used for `Player.id` via `Player.of(name)`), not any combination of
player ids or board configuration — a primary key must be stable for the
entire lifetime of the game regardless of what happens inside it (players
can't change the game's identity by playing it), and using a natural key
derived from mutable or non-unique attributes (e.g. "player names +
timestamp") would be fragile the moment two games started with
coincidentally similar configurations at nearly the same time.

**29. Would you store `Player.position` as a column on the `players`
table, or reconstruct it from an event log?**
Both, for different reasons — a direct `position` column gives O(1)
"what's the current state" reads (the overwhelmingly common query: "show
me this game's current board"), while the `game_events` append-only log
(README §12) gives full replay/audit capability and a recovery path if the
direct column ever became inconsistent (e.g. after a bug, replaying events
from scratch reconstructs ground truth). This is the classic CQRS-adjacent
trade-off: a denormalized "current state" table for fast reads, backed by
an authoritative event log for correctness/recovery — storing *only* the
event log and reconstructing position on every read would be correct but
needlessly slow for the overwhelmingly common "show current state" query.

**30. What isolation level would you use for the transaction that updates
a player's position and appends a game event, and why not something
stricter like `SERIALIZABLE`?**
`READ COMMITTED` is sufficient, as stated in README §12, because the
single-writer-per-game contract (§11) means no other transaction is ever
concurrently writing to the *same* game's rows — the classic anomalies
`SERIALIZABLE` guards against (phantom reads, non-repeatable reads
affecting the same data a concurrent transaction is also modifying)
simply cannot occur here by construction, since there is no concurrent
writer to produce them. Paying `SERIALIZABLE`'s performance cost
(typically implemented via more aggressive locking or optimistic
conflict-detection-and-retry) for a guarantee that's already true by the
application's own access pattern would be pure overhead with zero
additional safety.

---

## Performance

**31. What's the actual bottleneck if you ran a benchmark of
`playUntilWinner()` in a tight loop, millions of times?**
Almost certainly `java.util.Random.nextInt(bound)`'s internal
compare-and-swap loop for seed updates — not because it's slow in
absolute terms (nanoseconds per call), but because it's the only part of
the hot path that isn't pure, allocation-free arithmetic; everything else
(`HashMap.get` for jump lookup, `Deque` poll/offer, `MoveOutcome` record
construction) is similarly cheap but `Random`'s CAS loop is the one
operation with any contention-shaped cost profile at all, and that only
matters if the *same* `Random`/`SingleDie` instance were shared and raced
across many concurrent benchmark threads — which, per the single-writer
contract, shouldn't happen in realistic usage, making this a largely
theoretical bottleneck rather than a practically observed one.

**32. Does constructing a new `MoveOutcome` record on every single turn
create meaningful garbage-collection pressure?**
Not meaningfully, at any realistic scale — a `MoveOutcome` is a tiny
object (two `int`s and a `boolean`, likely under 24 bytes with object
header overhead) allocated once per turn and immediately eligible for
young-generation garbage collection the instant `playSingleTurn()`
returns, since nothing retains a reference to it afterward. Modern JVM
generational garbage collectors are specifically optimized for exactly
this pattern (many small, short-lived objects) — this would only become
worth profiling if games were being played at a rate of many millions of
turns per second on a single process, which is far outside this problem's
realistic scale.

---

## Failure Handling

**33. If `BoardBuilder.build()` throws partway through validation (say,
on the third configured jump), is the `BoardBuilder` instance left in a
usable state for the caller to fix and retry?**
Yes — `build()` only *reads* the accumulated `jumps` map and `size` field
to run validation; it never mutates or clears them, even on failure. A
caller can catch the thrown `InvalidBoardConfigurationException`, call
`.addLadder(...)`/`.addSnake(...)` again to fix or remove the offending
configuration (though removal isn't directly supported — a caller would
need to construct a fresh `BoardBuilder` to truly "remove" a jump, since
there's no `removeJump` method), and call `.build()` again. This matches
the general principle that a failed validation attempt should never leave
the object being validated in a worse or inconsistent state than before
the attempt.

**34. What happens if a listener passed to `addListener(...)` throws an
exception during event handling?**
Today, `Game.publish(...)`'s for-each loop over `listeners` has no
try/catch around each listener invocation — an exception thrown by one
listener propagates up through `publish`, through `playSingleTurn()`, to
the caller, and critically, **after** the turn's actual state mutations
(player position, turn requeue) have already happened. This means a
misbehaving listener can make a structurally-successful turn *look* like
a failure to the caller (since an exception propagates), even though game
state was already correctly updated — a real, currently-unaddressed gap
worth naming proactively: a production-hardened version should wrap each
listener invocation in its own try/catch, log the listener's failure, and
continue notifying the remaining listeners, so that one broken observer
(e.g. a flaky metrics listener) can never affect the correctness of the
core game loop for every other consumer.

---

## Production Architecture

**35. How would you expose this library over a REST API, at a high
level?**
A thin service layer wrapping `GameBuilder`/`Game`, not embedding HTTP
concerns into the library itself: `POST /games` (body: board config,
player names) calling `GameBuilder` and persisting the resulting `Game`'s
id in an in-memory or persisted registry; `POST /games/{id}/turns`
calling `playSingleTurn()` on the looked-up `Game` and returning the
resulting events as the response body; `GET /games/{id}` returning current
state via `players()`/`state()`/`winner()`. The library's existing
immutable event records serialize directly to JSON with no adaptation
layer needed, which is a direct benefit of having modeled events as
records from the start.

**36. What single metric would you want paged on if this ran in
production at scale?**
The rate of `GameDidNotTerminateException` being thrown — per
`STAFF_LEVEL_DEEP_DIVE.md` Q6/Q7, this is the one failure mode that's both
silent-until-triggered and indicates either a genuinely misconfigured
board/dice/win-condition combination reaching production, or (more
worryingly) a regression reintroducing the exact class of bug this
project's own test suite caught and fixed once already — a sudden spike in
this exception's rate is a strong, specific, actionable signal, unlike a
generic error-rate metric that would bury this signal among unrelated
noise.
