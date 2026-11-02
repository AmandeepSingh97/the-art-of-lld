# Interview Playbook — BookMyShow (Movie Ticket Booking System)

A speakable, timeline-driven playbook for presenting this design live in an
interview. Pairs with `README.md` (the full written design) and
`IMPLEMENTATION_GUIDE.md` (the coding sequence).

---

## 1. How to open the discussion

Don't start describing classes. Start by restating the problem in your own
words and naming the hard part out loud — this signals you've correctly
identified what the interviewer actually cares about, before you've
written a line of code.

> "So this is a ticket-booking system — cities, theatres, screens, shows,
> seats. The part I want to make sure we nail is seat-level concurrency:
> when a show is popular, many users will try to grab the same seat at
> the same instant, and the system has to guarantee exactly one of them
> wins — never zero, never two. I'll design the catalog model first since
> it's straightforward, then spend most of our time on the locking
> mechanism, the temporary-hold-with-TTL flow, and making booking
> confirmation idempotent."

## 2. First 5 clarifying questions to ask

1. "Is a seat held the moment a user selects it, or only once they start
   checkout?" — fixes when the TTL clock starts.
2. "Can one booking span multiple seats, and must it be all-or-nothing?"
   — determines whether you need group-lock rollback logic.
3. "Must the server tolerate retried/duplicate confirm requests?" —
   determines whether you need idempotency keys from the start (always
   say yes, but ask to show you know why it matters).
4. "Is this a single service instance, or do I need to account for
   multiple instances behind a load balancer?" — scopes whether you present
   CAS as the final answer or as "the in-process version of a pattern
   that extends to Redis/DB later."
5. "Is pricing fixed per category, or does it need to vary with demand or
   time-to-show?" — determines if `PricingStrategy` needs to be pluggable
   from minute one.

## 3. Requirements to establish

Say these out loud explicitly before modelling, so the interviewer can
correct you early rather than after you've built the wrong thing:
- Functional: search shows, view seat map with live availability + price,
  hold seats with TTL, pay and confirm (idempotently), cancel with refund.
- Non-functional: no double-booking ever (the one non-negotiable
  invariant), bounded hold duration, fast under contention on a few hot
  seats, extensible pricing/payment provider.

## 4. How to identify core entities

Walk top-down through the physical hierarchy first — it's intuitive and
low-risk: City → Theatre → Screen → Seat. Then introduce the two entities
that aren't obvious from the physical model and are the actual design
insight:
- **`Show`**: a (movie, screen, time) tuple — explain that seat
  availability can't live on `Seat` itself, because the same seat is
  available for one show and booked for another showing the same evening.
- **`ShowSeat`**: "this is the one entity doing all the work — it's the
  per-(show, seat) availability record, and it's the only genuinely
  concurrent, mutable thing in the whole model." Naming this early and
  explicitly is a strong signal.
- Then `Booking` (the user-facing aggregate) and `Payment`.

## 5. How to explain class relationships

Use composition-vs-association language explicitly, it's a cheap, clear
signal of OO fluency:
> "Theatre owns Screens, Screens own Seats — that's composition, a seat
> can't meaningfully exist without its screen. Show *references* a Movie
> and a Screen — that's association, a movie and a screen both have
> lifecycles independent of any one show. ShowSeat is interesting: it's
> derived from a Screen's seats at show-scheduling time, but then lives
> independently, keyed by (showId, seatId) — one physical seat maps to
> many ShowSeat records, one per show it ever appears in."

## 6. How to draw the architecture

Draw left-to-right or top-to-bottom in this order, narrating as you go:
1. Catalog read path: `CatalogService` → `ShowRepository` /
   `SeatInventoryRepository`.
2. Write path: `BookingService` in the middle, with three things it talks
   to on one level: `SeatLockService`, `PaymentGateway`, `PricingStrategy`.
3. Underneath `SeatLockService`, draw `ShowSeat` as the actual state being
   mutated — make clear `SeatLockService` is a thin orchestrator and
   `ShowSeat` is where the atomic guarantee actually lives.
4. Add the `BookingEventListener` observer hook coming *out* of
   `BookingService` last, as a "and this is how we'd hook up
   notifications without coupling them in" aside.

Full version: `diagrams/01-component-diagram.md` and
`diagrams/02-class-diagram.md`.

## 7. Which interfaces to create first

In this order, and say why for each:
1. `SeatLockService` — "I want this to be an interface from the start
   because the concurrency mechanism is the thing most likely to change
   or be challenged — in-process CAS now, Redis-backed later — and I don't
   want that decision to leak into `BookingService`."
2. `PaymentGateway` — "any real payment provider is external and mockable;
   this has to be an interface so I can test the booking flow without a
   real payment call."
3. `PricingStrategy` — "a single-method functional interface, because
   pricing rules are the most likely thing to need swapping without
   touching booking logic."

Deliberately *not* interfaces up front: `BookingService`,
`CatalogService` (concrete classes — there's exactly one reasonable
implementation of "orchestrate this workflow" for this exercise; making
them interfaces too would be interface-itis).

## 8. Which classes to implement first

1. Enums (`SeatCategory`, `SeatStatus`, `BookingStatus`, `PaymentStatus`) —
   zero dependencies, fast to write, and they pin down your vocabulary for
   everything else.
2. `Seat`, `Screen`, `Theatre`, `City`, `Movie`, `Show` — the immutable
   catalog, no concurrency concerns yet, builds confidence and shows
   steady progress.
3. **`ShowSeat`** — the centerpiece. Build and test this in isolation
   before wiring anything else to it; this is where you want to spend
   visible, careful time.
4. `SeatLockService` + `InMemorySeatLockService` — the orchestration on
   top of `ShowSeat`.
5. `Booking` (with its transition table), `Payment`.
6. `PaymentGateway`/`MockPaymentGateway`, `PricingStrategy` impls.
7. `BookingService` — wires everything together last, once every piece it
   depends on exists and is independently reasoned about.

## 9. Which design patterns to introduce and when

- Mention **Strategy** (pricing) right when you introduce `Show`'s base
  price — natural moment, not forced.
- Mention **Observer** (`BookingEventListener`) only once `BookingService`
  exists and you're asked "how would notifications work?" — don't
  preemptively bolt it on before there's a reason.
- If asked "why not the State pattern for `BookingStatus`?" — have the
  answer ready (table-driven guard is proportionate to the actual
  behavioral difference, which is just transition legality) rather than
  being caught flat-footed; see `README.md §7`.
- Actively **avoid** introducing Factory/Singleton/Command unless the
  interviewer specifically asks "what about X pattern here?" — volunteering
  patterns you then can't justify well is a worse signal than not
  mentioning them.

## 10. How to discuss concurrency

This is 40%+ of the signal in this problem. Structure it as:
1. **Name the race first, precisely**: "two threads both read AVAILABLE,
   both try to lock — without protection both could succeed."
2. **State the invariant**: "a seat has at most one lock holder at a
   time, enforced per (show, seat)."
3. **Pick a mechanism and justify it**, don't just name-drop: "I'll use a
   single `AtomicReference` holding an immutable bundle of status + lock
   owner + expiry, and transition it with compare-and-set. I'm choosing
   lock-free CAS over `synchronized` because the critical section is
   pure in-memory and O(1) — no I/O, no blocking — which is exactly the
   regime where CAS avoids context-switch overhead and never risks
   someone accidentally holding a monitor across a slow call."
4. **Address the multi-seat case explicitly** without being asked:
   "group bookings need multiple seats locked atomically as a unit, and
   there's no multi-key CAS — so I acquire one at a time in sorted order
   and roll back on any single failure." This pre-empts the interviewer's
   most likely follow-up.
5. **Mention TTL expiry and the dual lazy+active mechanism** — show you
   thought about the "user walks away mid-checkout" case without being
   prompted.
6. **If asked about scaling beyond one instance**, say so honestly: "this
   is correct for one process; across instances, the same CAS idea maps to
   a Redis atomic operation or a DB row with an optimistic `version`
   column — I'd want to confirm whether that's in scope before building
   it out."

## 11. How to discuss edge cases

Have these ready without being asked, in priority order:
1. Concurrent booking of the same seat (covered above).
2. Payment fails after seats are held — seats must release, not stay
   locked until TTL.
3. Payment succeeds but the seat happened to expire first (rare race) —
   refund immediately, don't leave the customer charged with nothing.
4. Client retries a confirm call — must not double-charge or
   double-confirm (idempotency key).
5. Cancelling an already-cancelled booking, or a booking that was never
   confirmed — reject cleanly via the state machine, no special-casing.
6. Empty seat list / unknown show id — fail fast, no partial state.

## 12. How to handle interviewer interruptions

- If interrupted mid-explanation with "wait, what if two users—": **stop
  immediately and answer the interruption directly**, don't finish your
  planned sentence first. It's almost always the concurrency question you
  were about to get to anyway — treat it as a gift, not a derailment.
- If asked "why not just use `synchronized`?": don't get defensive.
  Acknowledge it's a legitimate, simpler-to-explain alternative, then give
  the concrete reason you chose CAS (no blocking, short pure in-memory
  critical sections) — see README §16 for the fully worked comparison.
- If asked to add a requirement mid-design (e.g., "now add group
  discounts"): identify the smallest seam that absorbs it (here:
  `PricingStrategy` or a new `RefundPolicy`-shaped strategy) rather than
  redesigning from scratch — and say so explicitly: "I wouldn't touch
  `BookingService` for this, I'd add it as a new strategy implementation."

## 13. How to prioritize if time is running out

Drop in this order (least to most essential):
1. Dynamic/surge pricing — fall back to `FixedPricingStrategy` only, and
   say you'd add the strategy interface if there were time (interfaces are
   cheap to *describe* even if not fully coded).
2. Cancellation/refund flow — mention the seat-release + refund shape
   verbally, code it only if time remains.
3. Search/filter (`CatalogService`) — a straightforward repository query,
   lowest risk to leave as "I'd implement this the same way as the other
   repository lookups."
4. **Never drop**: the `ShowSeat` CAS state machine and the multi-seat
   lock/rollback logic. If you only get to implement one thing fully in
   code, make it this — it's the entire point of the exercise.
5. **Never drop**: idempotency on confirm — it's a one-line
   `computeIfAbsent`/early-return addition once the happy path exists, and
   skipping it after being asked about retries is a bad signal.

## 14. Three full walkthroughs with realistic timelines

### 30-minute round
- 0–3 min: problem restatement + the 5 clarifying questions (compressed to
  the top 2–3 if truly tight on time).
- 3–8 min: entity list + relationships, verbally, maybe a quick box diagram
  — don't write full Java yet.
- 8–20 min: code `ShowSeat` fully (the CAS state machine) and talk through
  the race it prevents while writing it.
- 20–26 min: code `SeatLockService.lockSeats` at a sketch level (can be
  slightly pseudocoded for the rollback loop if time-pressed, but the
  single-seat CAS must be real code).
- 26–30 min: verbally summarize `BookingService`'s orchestration (hold →
  pay → confirm) and idempotency, without necessarily typing all of it.

### 45-minute round
- 0–5 min: restate + clarifying questions.
- 5–12 min: full entity/enum pass, including `Booking`'s state machine
  sketch.
- 12–28 min: `ShowSeat` fully coded + narrated, then
  `InMemorySeatLockService.lockSeats`/`confirmLock` fully coded including
  the rollback logic.
- 28–38 min: `BookingService.initiateBooking`/`confirmBooking` coded,
  explicitly calling out idempotency and the payment-failure/expiry-race
  compensating actions.
- 38–43 min: talk through TTL expiry (lazy + reaper), and at least sketch
  `PricingStrategy`.
- 43–45 min: trade-offs recap — what you'd change for multiple instances.

### 60-minute round
- 0–5 min: restate + clarifying questions.
- 5–15 min: full domain model, enums, relationships, quick diagram.
- 15–20 min: design pattern choices stated with justification (Strategy,
  Observer) and the two you considered and rejected (State, Factory) —
  volunteer this, it's a strong signal.
- 20–38 min: `ShowSeat` + `InMemorySeatLockService` fully coded, including
  the concurrency test scenario talked through line-by-line (or actually
  written, if coding is expected) — `ExecutorService` + `CountDownLatch`,
  N threads, assert exactly 1 success.
- 38–50 min: `BookingService` fully coded: hold, pay, confirm, cancel,
  idempotency, failure/expiry compensation.
- 50–55 min: `PricingStrategy` (both implementations) and
  `CatalogService`.
- 55–60 min: staff-level discussion — persistence mapping (§12), scaling
  beyond one instance (§14), what you'd monitor in production (§17).

## 15. What I would say to the interviewer

A grab-bag of natural, speakable lines for moments that come up in almost
every run of this problem:

- "The thing I want to get right here isn't the catalog modelling — that's
  mostly bookkeeping — it's making sure two people can never walk away
  both believing they got the same seat."
- "I'm deliberately not using a database for this exercise, but I'll show
  you exactly how the in-memory compare-and-set maps onto a
  `version`-column optimistic lock if we went to Postgres — same idea,
  different substrate."
- "There's no multi-key atomic primitive for locking several seats at
  once, so I'm doing single-seat atomic operations in a fixed order and
  rolling back on partial failure — it's the same shape as a saga, just
  synchronous and in-process."
- "I chose compare-and-set over a synchronized block because the critical
  section here is tiny and pure in-memory — that's exactly the case where
  lock-free wins, no thread ever blocks waiting on another."
- "This retry-safe by idempotency key — if the client's confirm call
  times out and they resend it, they get the same booking back, not a
  double charge."
- "If this needs to run on more than one instance, the CAS idea doesn't
  disappear, it just moves — to a Redis atomic op or a DB row version
  column — I wouldn't want to build that out here unless you want me to,
  since it's more infra than LLD."
- "I thought about the State pattern for the booking lifecycle and
  decided against it — the states here only differ in which transitions
  are legal, not in actual behavior, so a transition table gets the same
  correctness with much less code."
- "Let me show you the actual concurrency test I'd write for this — spin
  up N threads all trying to lock the same seat at the same instant via a
  CountDownLatch, and assert exactly one wins."
