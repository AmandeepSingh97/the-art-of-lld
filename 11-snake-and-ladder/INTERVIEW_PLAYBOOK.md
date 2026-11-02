# Interview Playbook — Snake and Ladder

A speakable, time-boxed playbook for driving this problem in a live 30/45/60
minute interview. Pair with `README.md` for the full technical rationale;
this document is about *sequencing and delivery*, not re-deriving the design.

---

## 1. How to open the discussion

Don't start coding. Start by restating the problem in your own words and
immediately surfacing the one design decision that matters most:

> "So we're modeling a board game — square grid, players take turns
> rolling dice and moving forward, some cells have snakes or ladders that
> teleport you, first to the end wins. Before I design anything, I want to
> flag the one thing I think matters most here: snakes and ladders are
> really the same mechanic — 'land on cell X, get moved to cell Y' — just
> with different directions. I'd like to model them as one concept instead
> of two, if that's alright, and I'll explain why as I go."

This immediately signals you're not going to naively translate the
problem statement's nouns into classes, which is the single most common
mediocre-candidate failure mode on this problem.

---

## 2. First 5 clarifying questions to ask

Ask these in order — each one's answer measurably changes scope:

1. "Is the board always a square grid, and is a fixed size like 10x10 fine
   as the default, or does it need to be fully configurable?"
2. "If a player's roll would overshoot the final cell, what happens —
   do they just land on the final cell and win, or does that roll get
   rejected and they stay put?" *(This is the "exact landing" variant —
   ask it even if not explicitly prompted; it's almost always the
   interviewer's follow-up anyway.)*
3. "How many dice, and are they always six-sided — should I assume this
   needs to be pluggable, e.g. for a 'roll two dice' variant?"
4. "Do I need to support more than 2 players? Any upper bound I should
   design around?"
5. "Should I assume a single person/thread is driving the game turn by
   turn, like a physical board game, or could multiple requests try to
   advance the same game concurrently?" *(This fifth question is the one
   most candidates skip, and it's a direct fork in the concurrency story —
   asking it signals staff-level thinking immediately.)*

---

## 3. Requirements to establish

Before writing a single class, say out loud what you're building and what
you're explicitly not:

- **Building:** configurable board + unified jump concept, pluggable dice,
  pluggable win condition, N-player round robin, an observable event
  stream for "what just happened."
- **Not building (and why):** networking/multiplayer transport,
  persistence across restarts, a renderer, concurrent multi-writer access
  to one game. State this proactively — it shows you scope deliberately
  rather than by omission.

---

## 4. How to identify core entities

Walk the problem statement noun-by-noun, but merge where mechanics are
identical:

- Board, Player — obvious entities.
- Snake, Ladder → **merge into one `Jump(start, end, type)`** — say this
  out loud as a decision, not an afterthought: "I'm going to model these
  as one `Jump` value object rather than two classes, because the only
  difference is direction, and keeping them separate would mean
  duplicating validation and resolution logic for no real behavioral
  gain."
- Dice → an **interface**, not a class, the moment you hear "pluggable" or
  "multiple dice" or "deterministic for testing" in the requirements.
- "First to reach the end wins, unless overshoot is rejected" → this is a
  **policy**, not a fact baked into the board or the player — becomes a
  `WinCondition` strategy.
- Turn order for N players → a dedicated `TurnManager`, not an `int`
  index buried inside `Game`.

---

## 5. How to explain class relationships

Speak in terms of *ownership* and *lifetime*, not just "has-a":

> "`Game` **owns** the board, the players, and the turn manager — they're
> created for this game and have no life outside it. `Game` *depends on*
> but doesn't own the dice and the win condition — those are injected
> strategies that could, in principle, be reused across many games or
> swapped out entirely without `Game` changing at all. That distinction —
> composition for what's truly exclusive to one game, aggregation for
> what's genuinely pluggable — is what makes the variants in the problem
> statement easy to add later."

---

## 6. How to draw the architecture

Draw left-to-right, in this order, narrating as you go:
1. `Board` + `Jump` (pure data, no behavior dependencies) — draw first
   because everything else depends on it, nothing depends on `Game`.
2. `Dice` interface with one box labeled "impl: single die, multi-dice,
   scripted-for-tests" — don't draw three separate boxes yet, just note
   the interface and that implementations vary.
3. `WinCondition` interface, same treatment.
4. `TurnManager` as a thin box wrapping a queue.
5. `Game` in the center, with arrows *into* it from all four boxes above
   plus `Player`.
6. A small `GameEventListener` box off to the side with a dashed arrow
   from `Game` — label it "Observer: decouples UI/logging from engine."

This mirrors `diagrams/component-diagram.md` exactly, so if asked to
produce a diagram afterward in writing, it will be consistent with what
you drew on the whiteboard.

---

## 7. Which interfaces to create first

In this exact order, and say why:

1. `Dice` — "I want this pluggable from the start, because I know I'll
   need a deterministic version for tests, and retrofitting an interface
   onto a concrete class later always costs more than starting with it."
2. `WinCondition` — same reasoning, directly serves the "exact landing"
   variant named in the prompt.
3. `GameEventListener` — only after the core loop works; this is additive
   and shouldn't block getting a basic game running.

---

## 8. Which classes to implement first

1. `Jump` (record, self-validating) — smallest possible unit, and getting
   its validation right early prevents bugs from leaking into `Board`.
2. `Board` + `BoardBuilder` — next smallest independently testable unit.
3. `Player` — trivial, but needed before `TurnManager`.
4. `TurnManager` — needs only `Player`.
5. `Dice` + one implementation (`SingleDie`) — needed before `Game` can do
   anything.
6. `WinCondition` + one implementation — same.
7. `Game` + `GameBuilder` — the integration point; everything else should
   already be independently working and testable by the time you get here.
8. Events + `ConsoleGameEventListener` — last, since the game is fully
   functional without them; they're an enhancement, not a dependency.

---

## 9. Which design patterns to introduce and when

Don't announce "I'm using the Strategy pattern" cold — let it emerge from
the requirement:

- When you hit "dice should be pluggable / testable deterministically":
  *"This is a good fit for the Strategy pattern — `Dice` becomes an
  interface, and I can swap implementations without touching the game
  loop."*
- When you hit "overshoot should be configurable": *"Same idea again —
  `WinCondition` as a second Strategy, so the 'exact landing' variant is
  just a new implementation, not a new `if` branch in `Game`."*
- When you hit "I need validation before the board/game is usable":
  *"I'll use a Builder here rather than a big constructor, so all the
  cross-field validation — no duplicate jump starts, no cycles, minimum
  player count — happens in one place before anything invalid can
  escape."*
- When you hit "something needs to react to game events without `Game`
  knowing about it": *"Observer — a listener interface, so logging/UI/test
  assertions can all subscribe independently."*
- If asked "why not a State pattern for the game lifecycle?": *"I
  considered it, but with three states and no per-state polymorphic
  behavior beyond 'is a turn allowed,' a plain enum plus a guard clause is
  proportionate — I'd reach for the full State pattern if a future state
  needed genuinely different behavior, like a `PAUSED` state that buffers
  incoming moves."* This is a strong staff-level answer because it shows
  you know the pattern and chose not to use it, rather than not knowing it.

---

## 10. How to discuss concurrency

Lead with the explicit assumption, then show you know what changes if it's
wrong:

> "By default I'm assuming one logical thread drives a given game at a
> time — like a physical board game, only one person rolls at once. Under
> that assumption, `Board` is fully immutable so it's safe to share across
> games with zero synchronization, and `Player.position` is `volatile` so
> a read-only monitoring thread always sees the latest value without
> needing a lock. If we needed multiple concurrent writers to the *same*
> game — say, two near-simultaneous HTTP requests — I'd wrap
> `playSingleTurn()` in a single `synchronized` block scoped to the `Game`
> instance. One coarse lock is fine here because the whole method is a
> handful of in-memory operations with no I/O, so it's held for
> nanoseconds — finer-grained locking would only add lock-ordering risk
> for no real throughput benefit."

---

## 11. How to discuss edge cases

Have these ready, in priority order, and volunteer them before being asked:

1. Roll overshoots the final cell — covered by the `WinCondition` strategy.
2. A jump starting on the final cell (ambiguous "won or bitten?") — reject
   at board-build time.
3. Two jumps sharing a start cell — reject at board-build time.
4. A cyclic jump chain (only matters if chaining is enabled) — detect at
   board-build time via DFS, so the game loop never has to worry about it.
5. A pathological dice/win-condition combination that can never produce a
   winner (e.g. exact-landing with a die that only ever overshoots) — the
   turn loop must have a hard `maxTurns` ceiling that counts *every*
   attempt, including rejected/skipped ones, or it hangs forever. *(Flag
   this one proactively — it's the single most instructive edge case in
   this problem and it's rarely caught without actually running tests.)*

---

## 12. How to handle interviewer interruptions

- **"Why not just use two maps, one for snakes and one for ladders?"** —
  Answer directly, don't get defensive: "I could, and it'd work for
  exactly this feature set. I chose one unified map keyed by start cell
  because it collapses duplicate-start validation and resolution into one
  code path, and it's one step closer to supporting a third jump flavor
  later without a third map. Happy to go with two maps if you'd prefer
  that for this conversation, though — it's a shallow decision to reverse."
- **"What if I told you this needs to support 2 dice summed together,
  right now?"** — Don't panic-refactor. Say: "Easy — `Dice` is already an
  interface, so I add a `CompositeDice` that wraps a list of `Dice` and
  sums their rolls. Nothing in `Game` changes." Then actually write it if
  time allows — it's a 15-line class.
- **"Can you prove to me your `maxTurns` safety net actually works?"** —
  This is a great moment to mention (or write) the exact unit test:
  construct a `FixedSequenceDice` that always overshoots under
  `ExactLandingWinCondition`, call `playUntilWinner()` with a small
  `maxTurns`, assert it throws rather than hangs.

---

## 13. How to prioritise if time is running out

If you're at the 40-minute mark in a 45-minute slot and not done:

1. **Never skip:** `Jump`'s validation, the core `Game.playSingleTurn()`
   loop, and at least one working `Dice`/`WinCondition` implementation
   each — this is the minimum "does it actually work" bar.
2. **Skip without guilt:** the `events`/Observer layer entirely (say so
   explicitly: "I'd add an event-listener layer for observability, but
   I'm prioritizing a correct, tested core loop first").
2a. **Skip without guilt:** `CompositeDice` and `FixedSequenceDice` as
    concrete classes — keep the `Dice` interface, mention these as
    one-line follow-ons.
3. **Compress, don't skip:** the Builder classes — if truly out of time,
   fall back to simple constructors with a comment acknowledging the
   trade-off, rather than leaving validation out entirely.
4. **State the gap out loud:** "I'm not going to implement `TurnManager`
   as its own class given the time, I'll inline a simple rotation in
   `Game`, but I'd pull it out the moment a second piece of logic needed
   to know about turn order."

---

## 14. Three full walkthroughs with realistic timelines

### 30-minute walkthrough
- 0–3 min: restate problem, name the unify-snake/ladder decision up front.
- 3–7 min: clarifying questions (§2), state assumptions explicitly.
- 7–12 min: entities + relationships verbally, quick whiteboard sketch
  (no full Mermaid, just boxes and arrows) — `Board`/`Jump`, `Dice`,
  `WinCondition`, `TurnManager`, `Game`.
- 12–26 min: code `Jump`, `Board`/`BoardBuilder` (abbreviated validation —
  skip cycle detection, mention it verbally), `Player`, `TurnManager`,
  `Dice` interface + `SingleDie`, `WinCondition` interface +
  `OvershootClampWinCondition`, `Game.playSingleTurn()` core loop.
- 26–30 min: walk through one example turn by hand (a ladder climb), state
  what you'd add with more time (exact-landing variant, events,
  `maxTurns` safety net, tests).

### 45-minute walkthrough
- 0–4 min: restate + unify decision.
- 4–9 min: clarifying questions.
- 9–16 min: entities, relationships, and a quick Mermaid-style sketch on
  the whiteboard/shared doc if tooling allows.
- 16–38 min: full code — everything in the 30-minute version, plus
  `ExactLandingWinCondition`, `GameBuilder`, `maxTurns` safety net, and at
  least a sketch of the events/Observer layer (interface + one event
  type, even if not all five).
- 38–43 min: write 2–3 real unit tests live, using `FixedSequenceDice` to
  show you understand testability — this is a strong differentiator.
- 43–45 min: trade-offs recap (why Strategy/Builder/Observer, why not
  State pattern), concurrency assumption stated explicitly.

### 60-minute walkthrough
- 0–5 min: restate + unify decision + explicit in/out-of-scope list.
- 5–12 min: clarifying questions, written assumptions log.
- 12–20 min: full architecture discussion with diagrams (component +
  class + one sequence diagram).
- 20–45 min: full code — everything above, plus `CompositeDice`,
  `FixedSequenceDice`, full event hierarchy with `ConsoleGameEventListener`.
- 45–52 min: tests — at least one per collaborator, including the
  `maxTurns` failure-injection test and the ladder-climb event-ordering
  test.
- 52–57 min: concurrency deep dive (single-writer justification +
  what changes for multi-writer), persistence sketch if asked.
- 57–60 min: staff-level wrap-up — what you'd simplify for a 2-week ship,
  what the first production bottleneck would likely be, and one thing
  you'd reconsider given more time (e.g. `TurnManager`'s deque vs. a
  structure supporting mid-game removal).

---

## What I would say to the interviewer

*(Natural, speakable lines for the moments that matter most — not
textbook language.)*

- **Opening the unify decision:** "Snakes and ladders are the same thing
  mechanically, so I'm going to model them as one `Jump` type instead of
  two separate classes — it keeps validation and resolution in one place."
- **Introducing Strategy for dice:** "I'll make dice an interface right
  away, not because I'm trying to show off a pattern, but because I
  already know I'll want a scripted, deterministic version for tests, and
  I'd rather not retrofit that later."
- **Introducing Builder:** "I'm going to validate the board at construction
  time through a builder, so there's no code path anywhere in the system
  where an invalid board — duplicate jump starts, a cycle, whatever —
  can exist even transiently."
- **On why not State pattern:** "I thought about a full State pattern for
  the game lifecycle, but three states with no divergent per-state
  behavior doesn't earn that complexity — an enum and a guard clause says
  the same thing more simply. I'd revisit that the moment a state needed
  real behavior, like pausing and buffering moves."
- **On concurrency:** "I'm assuming one thread drives a game at a time,
  like a physical board game — I'll call that out explicitly rather than
  bake in synchronization I can't justify yet."
- **On the maxTurns bug, if you find/fix it live:** "Good catch to make
  sure of — I want to make sure my turn-attempt counter increments even
  when a roll gets rejected, not just on successful moves, otherwise an
  always-overshooting dice under the exact-landing rule would hang this
  loop forever instead of failing loudly. Let me write a test that proves
  it actually terminates."
- **Closing a time-boxed round:** "If I had another 20 minutes I'd add the
  events layer and a concurrency-safe variant for multi-writer access —
  but I'd rather leave you with a smaller, fully correct and tested core
  than a bigger, shakier one."
