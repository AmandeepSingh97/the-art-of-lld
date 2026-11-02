# Staff-Level Deep Dive — Snake and Ladder

This problem is routinely underestimated because the surface mechanics are
trivial (roll a die, move a token). The staff-level signal is entirely in
how the candidate handles *extensibility*, *termination*, *concurrency
boundaries*, and *what breaks first at scale* — not in the core game loop
itself. Twenty-four problem-specific questions follow, each with reasoning,
not a one-line answer.

---

### Q1. `Game` depends on every other package — isn't that a god class?

It is the single largest concentration of coupling in the design, and that
is a conscious, named trade-off rather than an oversight. The alternative
— splitting `Game` into, say, a `TurnOrchestrator` plus a
`JumpResolutionService` plus a `WinEvaluationService` — was considered and
rejected because none of those pieces have independent *state* or an
independent *reason to change*: they all exist purely to sequence calls
into `Board`, `Dice`, `WinCondition`, and `TurnManager` in one specific
order for one specific reason (play a turn correctly). Splitting a
sequencing responsibility across multiple classes when there's exactly one
sequence and one caller doesn't reduce coupling, it just relocates it and
adds indirection. The actual test for "is this a god class problem" is:
does `Game` contain *business logic* that could be extracted into an
independently-reusable, independently-testable unit? The business logic —
jump resolution, win evaluation, dice randomness — has *already* been
extracted into `Board`, `WinCondition`, and `Dice` respectively, each
independently unit-tested without touching `Game`. What's left in `Game`
is pure sequencing, which is exactly what an aggregate root should contain.
If asked to defend this live, the strongest answer is: "I'd worry about
god-class risk here the moment `Game` started accumulating *decision
logic* rather than *sequencing* — right now, every decision is delegated."

### Q2. Walk me through what happens if two HTTP requests race to submit a roll for the same game id.

This is the direct challenge to the single-writer assumption. Today: both
requests would call `playSingleTurn()` on the same `Game` instance with no
synchronization, and the two invocations could interleave arbitrarily —
e.g. both could call `turnManager.pollCurrent()` before either calls
`dice.roll()`, both polling the *same* player (a `Deque` poll is not
reentrant-safe without external synchronization) and corrupting turn order,
or worse, both could read `current.position()` before either writes it,
causing one roll's move to be silently overwritten by the other
(a classic lost-update race). The fix is not "add more locks everywhere" —
it's exactly one `synchronized` boundary around the entire body of
`playSingleTurn()`, keyed on the `Game` instance, because the method's
steps are causally dependent on each other (you can't evaluate a win
condition before you have a roll; you can't apply a jump before you know
the post-roll position) — partial interleaving of those steps is never
safe, so the lock has to span the whole method, not a sub-part of it.
Finer-grained locking (e.g. a separate lock for `TurnManager` and a
separate lock for `Player.position`) would not help and would actively
hurt, because correctness here requires *all* of those steps to happen
atomically together, so you'd end up holding multiple locks for the
method's full duration anyway — with zero benefit and a new category of
lock-ordering bugs. The one-line practical answer for a live interview:
"I'd wrap the whole method, and I can defend why a coarser lock is
actually *more* correct here, not just simpler."

### Q3. The dice roll happens mid-method, with a side effect (consuming randomness) that can't be undone. What if `WinCondition.evaluate()` threw an exception after the roll was already consumed?

Today, `WinCondition.evaluate()` is a pure, total function over three ints
— it cannot throw for any well-formed input, by construction (no
nulls, no I/O, no partial failure modes). But the question is worth
reasoning through generally, because it's exactly the kind of
mid-operation-crash scenario a staff interviewer probes for: if a
strategy *could* throw mid-turn, the roll would already have been
"spent" (for a stateful dice implementation, e.g. a future card-draw-style
dice that removes cards from a deck) with no corresponding position
update — a classic "effect without a matching cause" bug. The general
principle this illustrates: any method that performs a *non-idempotent
side effect* (consuming a resource, incrementing a counter, drawing from
a finite pool) followed by logic that could fail must either (a) make the
side effect itself undoable/transactional, or (b) defer the side effect
until after every possible failure point has already passed. In this
design, `dice.roll()` is deliberately called *before* any conditional logic
that could reject the result — if the result is rejected (overshoot), the
roll was never "wasted" in a meaningful sense because `Dice` strategies in
this design are all stateless or trivially re-rollable (`SingleDie`,
`CompositeDice`) or intentionally stateful-but-safe-to-"waste"
(`FixedSequenceDice` simply advances its cursor, which is fine to have
happened even on a rejected roll, since the next turn should get the
next scripted value regardless).

### Q4. How would you add "landing on a ladder/snake grants an extra roll" without changing existing code?

This tests whether the Strategy boundaries are actually where they claim
to be. The honest answer: this change *cannot* be made purely by adding a
new `Dice` or `WinCondition` implementation — it's a change to the turn
*sequencing* itself (should the current player roll again before
`requeue`), which genuinely lives in `Game.playSingleTurn()`, not in a
pluggable strategy. This is an important, honest admission rather than
forcing a fake extension point: not every variant is a strategy swap, and
claiming otherwise would be dishonest about the design's actual
boundaries. The lowest-blast-radius way to add it: introduce a small
`TurnOutcomePolicy` (yet another Strategy) with a method like
`boolean grantsExtraTurn(MoveOutcome outcome, Jump appliedJumpOrNull)`,
consulted once per turn right before the `requeue` call — if it returns
true, skip `requeue` and loop the *same* player through `playSingleTurn`
again internally. This isolates the *new* decision as its own strategy
while being honest that `Game`'s control flow needs a small, additive
change (one new `if`) to consult it — not zero changes, but a minimal,
well-scoped one.

### Q5. What's the actual probability distribution of game length, and could a game run essentially forever in practice?

With a standard 10x10 board and a fair six-sided die under
`OvershootClampWinCondition`, the game is a finite-state absorbing Markov
chain (101 states: positions 0–100, with jump cells immediately
transitioning to their destination state) — it has a single absorbing
state (100) and is guaranteed to terminate with probability 1 in a
mathematical sense, with a well-defined (if not small) expected number of
turns. Under `ExactLandingWinCondition`, the chain is *not* guaranteed to
terminate in finite expected time in all configurations — if, say, cell 99
is unreachable by any forward-moving combination of jumps and a 6-sided
die, a player sitting at 95–98 might need a very specific roll, which is
still probability 1 in the limit but with higher variance. In no
realistic configuration does the probability of non-termination actually
reach zero probability of eventual termination — but "probability 1 given
infinite time" is not the same guarantee as "will terminate within any
finite bound I can promise a caller," which is exactly why `maxTurns`
exists as a hard engineering safety valve rather than relying on the
probabilistic argument. This is the single most important insight in the
whole problem and the one most candidates never mention unprompted.

### Q6. You found and fixed a real bug where `maxTurns` never tripped under certain conditions — walk me through it as if I were doing a blameless postmortem.

**What happened:** `Game.playUntilWinner()`'s loop guard originally checked
`turnsTaken >= maxTurns`, but `turnsTaken` was only incremented on *moves
that actually happened* (`outcome.moved() == true`), while the early-return
branch for a rejected/overshooting roll (`!outcome.moved()`) returned
without touching `turnsTaken` at all. Under `ExactLandingWinCondition`
combined with a dice/board configuration that can only ever overshoot
(constructed deliberately in the regression test, but just as reachable
by an unlucky real board/dice combination), *every single call* to
`playSingleTurn()` takes the rejected branch, `turnsTaken` never moves off
zero, the guard `0 >= maxTurns` never fires, and `playUntilWinner()` spins
forever. **How it was caught:** not by code review or by reasoning about
the code in isolation — by actually writing and running
`playUntilWinnerThrowsWhenMaxTurnsIsExceeded`, a failure-injection test
that deliberately constructs the pathological dice/win-condition
combination and asserts the engine fails loudly within a small `maxTurns`
budget; running the full suite against the *original* implementation hung
indefinitely rather than failing fast, which is exactly the signal that
exposed the bug. **The fix:** introduce a second counter, `turnAttempts`,
incremented unconditionally at the top of `playSingleTurn()` before any
branch, and gate the `maxTurns` check on `turnAttempts` instead of
`turnsTaken` — preserving `turnsTaken`'s original meaning ("turns in which
a player actually moved," used for `PlayerWonEvent`'s turn count) while
giving the safety valve a counter that actually reflects "how many times
has this loop spun, successful or not." **The generalizable lesson:** any
safety-valve counter guarding against infinite loops must count *loop
iterations*, not *successful outcomes* — conflating the two is a subtle,
easy-to-write bug precisely because the happy-path tests (which always
succeed) can never expose it; only a deliberately adversarial/failure-mode
test can.

### Q7. If I handed you this code after a different engineer wrote it and told me "it's been in production for a year with no incidents," would you trust that as evidence it's correct?

No, and Q6 is the direct proof why not: the original buggy version would
also have run "fine" in production for a year, *provided* no game ever
happened to hit a dice/board/win-condition combination that always
overshoots — which is a real but low-probability configuration, the kind
of thing that could plausibly go unnoticed until a specific board
configuration (e.g. a custom board with an unreachable final approach
under exact-landing rules) got deployed and a game silently hung,
consuming a thread or event-loop slot forever with no error, no log line,
no metric — just quietly stuck. "No incidents" for a bug that depends on a
specific, rare input combination is exactly the kind of false confidence
that only a deliberately adversarial test (not more time in production
under typical/lucky inputs) can actually falsify. This is the core
argument for failure-injection testing as a category, not just unit
testing of the happy path.

### Q8. Your `Jump` validation lives in three places conceptually — the record's compact constructor, `BoardBuilder.addJump`, and `BoardBuilder.build()`. Isn't that duplicated/scattered validation?

They're enforcing three genuinely different invariants, not the same one
three times, which is worth distinguishing precisely: (1) `Jump`'s compact
constructor enforces invariants that depend *only* on the single `Jump`
instance's own fields (direction correctness, non-zero, non-equal
start/end) — these can never be relaxed by context, so they belong at the
narrowest possible scope, the value object itself, giving the strongest
possible guarantee ("this `Jump` cannot exist in an invalid state, ever,
regardless of caller"). (2) `BoardBuilder.addJump` enforces an invariant
that depends on *other jumps already added* (no duplicate start cell) —
this is inherently a builder-time, cross-object concern and cannot live on
`Jump` itself, since a single `Jump` has no visibility into its siblings.
(3) `BoardBuilder.build()` enforces invariants that depend on the *board's*
configuration, which isn't known until `withSize()` is called (cell range
validity, entry/final cell restrictions) — these literally cannot be
checked earlier, since a `Jump` can be added before `withSize()` is called
in the builder's fluent API. This is "defense distributed at the correct
scope for each invariant," not duplicated validation of the same fact —
the test for whether validation is scattered vs. correctly layered is
whether each check could, even in principle, be enforced one layer down;
here, none of them could.

### Q9. Your cycle detection only guards `resolveChained`, which `Game` never calls by default. Isn't that dead code / wasted engineering effort?

It's intentionally built proactively rather than reactively, and the
reasoning matters: `BoardBuilder` has no way of knowing, at board-build
time, whether *this specific* `Board` instance will later be used with
`resolveSingleHop` (the default) or `resolveChained` (the documented
extension point for a chaining house-rule variant) — a `Board` is just
data; the decision of *how* to resolve jumps lives entirely in the caller
(`Game`, or any future code). Since the invariant "this jump graph has no
cycles" is a property of the *board configuration itself*, not of how any
particular caller chooses to use it, the only sound place to enforce it is
at board-construction time, once, regardless of how the board will later
be consumed — deferring the check to `resolveChained` itself would mean
every *caller* of `resolveChained` would need to defensively handle a
potential infinite loop (impossible to truly "handle" an infinite loop at
the call site), and worse, a `Board` that happens to never be run through
`resolveChained` in today's codebase could still be unsafe the moment
someone adds a second call site tomorrow. This is exactly the right kind
of "engineering for a documented but not-yet-exercised extension point" —
it costs a bounded amount of implementation effort (one DFS pass) and
converts a potential future production incident (an actual infinite loop)
into a loud, immediate `InvalidBoardConfigurationException` at
configuration time, before any game is ever played.

### Q10. Could you have detected cycles implicitly, by capping `resolveChained`'s hop count instead of running a separate DFS at build time?

Yes, and in fact `Board.resolveChained` *does* carry that defensive cap
(`hops <= totalCells`) as a second, independent line of defense — but
relying on the hop cap *alone*, without the build-time DFS, would be
strictly worse for two reasons. First, timing: the hop-cap approach only
discovers the problem the first time some player's token actually lands on
the start of a cyclic chain *during a live game* — which could be turn 1 or
turn 10,000, i.e., the failure mode shifts from "loud and immediate at
configuration time" to "silent until a specific runtime event finally
triggers it," which is a strictly worse failure mode for exactly the same
reason discussed in Q6/Q7. Second, semantics: when the hop cap *is* hit at
runtime, the only available response is to throw from the middle of an
in-progress turn (after the dice has already been rolled and partially
processed), which is a far messier failure to handle and explain to a
caller than rejecting an invalid board before any game involving it was
ever constructed. The two checks are complementary, not redundant: the
DFS is the primary, preventive control; the hop cap is "belt and
suspenders" in case a future refactor ever bypasses `BoardBuilder`
(e.g. a hypothetical alternate `Board` constructor added later without
going through validation).

### Q11. How would you support adding a brand-new jump flavor — say, a "teleporter" that moves a player to a *random* cell each time, not a fixed one — without breaking the existing design?

This is a genuine test of whether "unifying snakes and ladders" was a real
abstraction or a coincidental one. A random-destination jump breaks one
assumption baked into `Jump` as a `record`: that `end` is a single fixed
value known at board-configuration time. The honest answer is that
`Jump(start, end, type)` as currently typed *cannot* represent this
directly — forcing it in (e.g. `end = -1` meaning "random, compute at
resolution time") would be a type-unsafe hack. The clean extension: change
`Board`'s resolution to depend on a `JumpEffect` strategy interface
(`int resolveDestination(int start)`) rather than a fixed `end` field, with
`Jump` holding a `JumpEffect` instead of a raw `int end` — a
`FixedDestinationEffect` covers the existing snake/ladder behavior
unchanged, and a new `RandomDestinationEffect` covers the teleporter case.
This is presented honestly as a *refactor*, not a free extension — the
current `Jump` record's fields would change shape, `BoardBuilder`'s range
validation would need to special-case "destination not known until
resolution time" (you can no longer validate a random teleporter's
destination range at build time the same way), and the cycle-detection DFS
would need to either special-case non-fixed-destination jumps (treat them
as "cannot participate in a static cycle, since their destination isn't
knowable ahead of time") or be disabled for boards containing any dynamic
jump. This is a good staff-level answer precisely because it doesn't
pretend the existing design absorbs this change for free — it identifies
exactly which three places would need to change and why.

### Q12. This system has 10 million concurrent games in production. What's the first thing that breaks?

Not the per-turn game logic — that remains O(1) and sub-microsecond
regardless of how many *other* games exist, because (per §11 of the
README) games share no mutable state with each other. The first thing to
break is almost certainly whatever **process-wide registry** maps game ids
to live `Game` objects: if that's a single `HashMap` behind one lock (a
natural, easy-to-write-first-and-forget-to-revisit mistake), every single
game's "find my game object" step now serializes behind one global lock,
even though the actual *game logic* for different games has zero reason to
contend. The fix is mechanical and well-understood (a `ConcurrentHashMap`,
or sharding the registry across N maps by a hash of the game id) — the
staff-level insight worth stating explicitly is *why* this is the first
failure point and not the game logic itself: the game logic was designed
from the start to have zero shared mutable state across games (§11), so
the only place contention *can* arise is in whatever bookkeeping structure
holds all the games together, which is exactly the kind of thing that's
easy to bolt on carelessly after the "real" design is done. Second-order
effects worth mentioning if pushed further: 10M live games each holding a
`Board` reference — if every game constructs its *own* `Board` rather than
sharing one immutable, interned instance for "the standard 10x10 board,"
that's 10M duplicate, identical jump maps sitting in memory for no reason;
since `Board` is immutable by design, this is a one-line fix (cache/intern
common board configurations) that the immutability property specifically
enables.

### Q13. What's the single highest-risk, hardest-to-reverse decision in this design, and why?

The single-writer-per-game concurrency model (§11), stated as an explicit
*assumption* rather than enforced by the type system. It's hard to reverse
not because changing the lock strategy is technically hard (it's one
`synchronized` block, as discussed in Q2) — it's hard to reverse because
every piece of *reasoning* throughout the rest of the design implicitly
depends on it: `Player.position` being merely `volatile` rather than
guarded by a lock, `TurnManager`'s unsynchronized `ArrayDeque`, the
absence of any idempotency-key concept for "submit a roll" (because
there's no concurrent-retry scenario to be idempotent against under
single-writer). Reversing the assumption doesn't just mean adding a lock
— it means re-auditing every one of those downstream decisions to check
whether they're still sound once multiple writers are possible (they
mostly are, once the method-level lock is added, but that re-audit is real
work, not a trivial addition). This is precisely why the assumption is
stated as the very first clarifying question in `INTERVIEW_PLAYBOOK.md`
§2 — getting it wrong early is the most expensive kind of wrong in this
design.

### Q14. If you had to ship a correct, working version of this in 2 weeks with a team of 2, what would you simplify, and what would you absolutely not cut?

**Would simplify:** drop `CompositeDice` and the chained-jump resolution
path entirely (both are genuine extensibility demonstrations, not needed
for a working single-die, single-hop game); collapse `BoardBuilder`'s
cycle detection to a simpler, less rigorous check (e.g. cap jump-map size
and trust manual review of configured boards, rather than a full DFS) if
board configurations are curated internally rather than user-submitted;
skip the sealed `GameEvent` hierarchy's exhaustiveness guarantees in favor
of a single generic `GameEvent(type, payload)` class, accepting weaker
compile-time safety for less code. **Would absolutely not cut:** the
`maxTurns` safety valve (an infinite hang in production is categorically
worse than a thrown exception that a caller can catch and log), the
`Dice`/`WinCondition` Strategy interfaces themselves (even with only one
implementation each shipped, the interface boundary costs almost nothing
and directly enables the next feature request without a rewrite), and the
board-validation invariants around jump-start restrictions (cell 1, final
cell, duplicates) — these are the invariants most likely to be violated by
a careless/rushed board configuration, and catching them at build time
rather than discovering a broken game mid-play in production is a cheap
insurance policy relative to its cost.

### Q15. Why is `Player` the only mutable class in the entire domain model, and is that actually justified or just convenient?

It's justified by a genuine distinction in the DDD sense between an
*entity* (has identity that persists across state changes — "this is
still Alice even after she moves") and a *value object* (defined entirely
by its current field values — two `Jump`s with the same start/end/type are
interchangeable). `Player` has an identity (`id`) that must remain stable
while an attribute (`position`) changes every turn — this is the textbook
definition of when mutability is the *correct* modeling choice, not merely
a convenient one. The counter-argument (fully immutable `Player`, new
instance per move, `Game` tracks "current `Player` snapshot" per id in a
map) was considered and rejected in README §16 specifically because it
buys nothing here: nothing in this design needs to hold a reference to
"Alice's position at turn 7" after turn 8 has happened (no undo feature,
no replay-from-arbitrary-point feature in scope), so paying the
bookkeeping cost of immutable snapshots has no corresponding benefit. The
important discipline is that this was a *deliberate, scoped* exception to
"immutable by default" — not "mutability is fine because Java default" —
and it's confined to exactly one class in the whole model.

### Q16. Your `TurnManager` uses a `Deque`. Walk me through exactly what happens, step by step, if a player needs to be removed mid-game (e.g. they disconnect).

`ArrayDeque` supports `remove(Object)` which does an O(n) linear scan to
find and remove an arbitrary element — so it's *possible*, just not O(1)
like the head/tail operations the design actually relies on elsewhere. The
real complication isn't the removal operation's complexity, though — it's
correctness around *where in the rotation* the removed player currently
sits relative to whichever player the `Game` loop currently has "in hand"
(already polled via `pollCurrent()`, not yet requeued). If player B
disconnects while player A's turn is actively being processed (A has been
polled, B is still sitting in the deque), a straightforward
`turnManager.remove(b)` call works cleanly since B hasn't been touched
yet. But if the disconnect notification arrives in between A's `pollCurrent()`
and A's `requeue()` — i.e., mid-turn — the `TurnManager`'s internal deque
is a consistent snapshot throughout (B is untouched, still safely in the
queue, regardless of what's happening with A), so removal is still safe;
the only thing to get right is that this entire sequence must happen
within the same `synchronized` boundary as the rest of `playSingleTurn()`
once multi-writer support exists (Q2), otherwise a disconnect-triggered
removal racing against an in-flight `playSingleTurn()` call is exactly the
kind of interleaving bug Q2 already describes. The genuinely hard case —
not currently supported, and explicitly named as such in README §16 — is
removing the player who is a *winner-in-waiting mid-resolution*: if B
disconnects at the exact moment their own roll would have won the game,
there's a real product-decision question (does a disconnect forfeit an
in-flight winning roll?) that no amount of data-structure cleverness
resolves — it needs a policy decision, which is exactly the kind of
ambiguity worth surfacing to the interviewer rather than silently picking
one behavior.

### Q17. The `Board` cell-range validation effectively caps the board at 10,000 cells (size ≤ 100). Where does that number come from, and is it actually the right ceiling?

It's a deliberately generous, round, defensible-but-arbitrary ceiling, and
that should be said plainly rather than implying it was derived from some
rigorous capacity model — it wasn't. The actual constraint that matters is
indirect: board size drives (a) the cost of `BoardBuilder`'s validation
pass, which is linear in the number of *configured jumps*, not cells
directly, so a 100x100 board with 3 jumps validates just as fast as a
10x10 board with 3 jumps; and (b) the worst-case hop count for
`resolveChained`'s defensive loop cap, which *is* `totalCells`, so a larger
board does linearly increase the worst-case (never-actually-hit-in-
practice, since cycles are rejected at build time) iteration bound for
that one method. Given neither of those costs is actually troubling even
at `size=100` (10,000 iterations of a trivial loop is microseconds), the
real reason for a ceiling at all is sanity-checking caller input (reject
`size=1_000_000` as almost certainly a bug or an abuse attempt) rather
than a genuine performance constraint — which is exactly the kind of
validation ceiling that's fine to pick a round, generous number for and
revisit only if a real use case needs more, rather than over-engineering
a precise derivation for a number that doesn't actually drive any
meaningful cost today.

### Q18. Your sealed `GameEvent` hierarchy gives compile-time exhaustiveness inside this codebase. Does that guarantee actually hold for an external consumer of this library?

No, and this is worth being precise about rather than overclaiming the
guarantee. Sealed types give compile-time exhaustiveness *at compile time
against a specific version of the sealed hierarchy* — any code (inside or
outside this codebase) that exhaustively pattern-matches/instanceof-chains
over `GameEvent` today is guaranteed by the compiler to handle all five
current event types. But the moment a sixth `GameEvent` implementation is
added to the `permits` clause in a future version of this library, *every*
previously-compiled exhaustive consumer — including ones outside this
codebase that depend on it as a library — needs to be recompiled against
the new sealed hierarchy to even notice the gap (an exhaustive `switch`
over a sealed type that's missing a case literally fails to *compile*
against the new version, which is the intended safety net, but it is a
source-compatibility break, not something that fails silently at
runtime for already-compiled callers, nor something update-in-place
without a recompile). This is precisely the API-evolution trade-off named
in README §17: sealed types are the right choice for guaranteeing
exhaustiveness *within* a single evolving codebase (which is this
project's actual situation), and the wrong choice for a public,
independently-versioned API where you want to *add* event types without
forcing every downstream consumer to recompile and handle the new case
immediately (there, an open/non-sealed hierarchy with a default/`else`
branch convention would be the more appropriate, if less strictly-checked,
choice).

### Q19. Could two different `Game` instances sharing the same immutable `Board` object ever interfere with each other?

No, and walking through exactly *why* not is the valuable part of the
answer, not just the conclusion. `Board` exposes no method that mutates
any of its fields post-construction — `jumps()` returns the result of
`Map.copyOf(...)` taken once at construction (an unmodifiable view, and
even if it weren't, it's a *copy*, not the live map), and `resolveSingleHop`/
`resolveChained` are pure read-only lookups with no side effects on
`Board` itself. Two `Game` instances calling `board.resolveSingleHop(cell)`
concurrently from two different threads are both just reading from the
same `HashMap`, which is safe for concurrent *reads* with no concurrent
*writes* (the Java Memory Model guarantee that applies here: all of
`Board`'s fields are effectively final — set once in the constructor and
never reassigned — which means safe publication is guaranteed the moment
a reference to a fully-constructed `Board` is handed to a second thread,
with no need for `volatile` or synchronization on `Board`'s own fields).
This is the direct payoff of the immutability design decision from README
§7 and §11: it's not just "nice for testing," it's what makes board-sharing
across games a genuinely free, zero-synchronization-cost optimization
rather than a subtle future bug waiting to happen.

### Q20. You chose `ArrayDeque` over `LinkedList` for `TurnManager` — why, concretely, and does it matter at this scale?

`ArrayDeque` is documented by the JDK as generally faster than
`LinkedList` for deque operations because it avoids the per-node object
allocation and pointer-chasing overhead inherent to a linked structure,
instead using a resizable circular array — for a structure that's pushed
to and popped from every single turn, this is a real (if small)
constant-factor win. At the scale this problem actually operates at
(2–N players, N rarely exceeding single digits for a realistic board
game), this performance difference is utterly immaterial in absolute
terms — the honest answer is that `ArrayDeque` was chosen because it's the
JDK-recommended default for deque use cases generally, not because a
`LinkedList` would have caused any observable problem here. This is worth
saying explicitly in an interview: picking the generally-better default
when there's no cost to doing so is good practice, but it would be
overclaiming to present this specific choice as solving a performance
problem that doesn't actually exist at this scale — the *real* reason to
know this trade-off is for contexts where turn/queue volume is large
enough for the constant factor to matter, which this problem is not.

### Q21. Does anything in this design assume the board is contiguous (no gaps in cell numbering)? What if cell 50 simply didn't exist?

Yes — this is an implicit assumption worth surfacing proactively rather
than waiting to be asked, because it's exactly the kind of thing that
looks "obviously fine" until probed. `Board.totalCells()` is computed as
`size * size` and used directly as both "the winning cell number" and "the
upper bound for jump-cell range validation" — the entire design assumes
cells `1..totalCells` are a dense, contiguous range with no holes. If a
future board concept needed genuine gaps (e.g. a board shaped like a
physical "plus" sign, common in some board game variants, where not every
`(row, col)` in the bounding square is a real cell), `totalCells` could no
longer be computed as a simple multiplication — it would need to become
an explicit `Set<Integer>` of valid cell numbers (or a `BitSet` for
efficient membership checks at scale) supplied to `BoardBuilder`, and
every piece of code that currently does arithmetic range-checking against
`totalCells` (jump validation, win-condition overshoot comparison) would
need to instead do set-membership checking. This is a meaningfully larger
change than it might first appear, since "is this the final cell" and "is
this roll an overshoot" currently rely on simple numeric comparison
against one `int`, not membership testing against a shape — worth naming
as a real limitation rather than discovering it mid-implementation if ever
asked to generalize the board's geometry.

### Q22. If this needed to support "watch this game live" for spectators (not players), what changes, and what doesn't?

What doesn't change: the entire `Game`/`Board`/`Dice`/`WinCondition`/
`TurnManager` core, because spectating is purely a *read* concern layered
on top of the existing Observer mechanism — a spectator is simply another
`GameEventListener` (e.g. one that serializes each `GameEvent` to JSON and
pushes it over a WebSocket to a connected spectator client), requiring
zero changes to the engine itself; this is the single best validation
that the Observer pattern was the right call in README §7, since an
entirely new consumer category (spectators, not just logging/console
output) slots in without touching `Game` at all. What *does* need
addressing: a spectator joining *mid-game* needs the current state (board
positions, turn order) as a snapshot, not just the event stream going
forward — today's design has no "describe current state as of right now"
API beyond `Game.players()`/`Game.state()`/`Game.winner()`, which is
actually sufficient for a snapshot (iterate `players()` for positions,
check `state()`/`winner()` for lifecycle), so this turns out to be a
non-issue once you actually check rather than assume a gap exists — a
good example of why it's worth tracing through the existing public API
surface before declaring a new requirement needs new engine code.

### Q23. What does "backward compatibility" even mean for a library like this one, and where would you be most exposed?

For a library (as opposed to a network-facing service), backward
compatibility is about *source and binary compatibility for downstream
code that compiles against this library's public API*, not about wire
protocol versioning. The most exposed surface is exactly the one named in
Q18: the sealed `GameEvent` hierarchy, where adding a new event type is
a source-breaking change for any exhaustive consumer. The second most
exposed surface is `GameBuilder`/`BoardBuilder`'s validation rules
themselves — if a future version *tightens* validation (e.g. starts
rejecting boards with `size < 4` instead of the current `size < 3`), any
caller currently constructing a `size=3` board in production would break
on upgrade with no source-code change on their part, purely because the
library got stricter; this is the classic "validation rules are part of
your API contract, not just internal detail" trap, and the safe pattern is
to only ever *loosen* validation in a minor version, reserving any
*tightening* for a major version bump with a clear changelog entry. The
least exposed surface, by contrast, is anything behind a pure interface
with multiple implementations already (`Dice`, `WinCondition`) — adding a
new implementation is purely additive and never breaks an existing
caller who's only depending on the interface.

### Q24. Final challenge: is this design over-engineered for what is, after all, a children's board game?

This is worth answering head-on rather than defensively, because it's a
fair challenge. The honest calibration: every pattern introduced here was
introduced in direct response to a requirement explicitly named in the
problem statement — "pluggable Strategy" for dice is named verbatim in
the prompt; "deterministic/testable dice via seeded RNG" is named
verbatim; "exact landing on 100 required" as an extensibility example is
named verbatim; N-player support and a Queue-based turn manager are named
verbatim. Nothing here is speculative gold-plating for a requirement that
was never asked for — contrast this directly with the `GameState`
decision in README §7/§16, where the full GoF State pattern *was*
considered and explicitly *rejected* as disproportionate, precisely
because nothing in the stated requirements calls for per-state polymorphic
behavior. The actual test for over-engineering isn't "does this problem
sound simple" — many genuinely simple-sounding problems (this one
included) have real extensibility and correctness requirements once you
read the full prompt carefully, and the children's-game framing is a
surface-level distraction from that. The test is "would removing this
abstraction make a named, stated requirement impossible or materially
harder to satisfy" — by that test, every pattern kept here earns its
place, and the one pattern that didn't meet that bar was left out.
