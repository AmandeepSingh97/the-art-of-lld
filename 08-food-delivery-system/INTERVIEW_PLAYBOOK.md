# Interview Playbook — Food Delivery System

A speakable, timed walkthrough for a 30/45/60-minute LLD round. Pair this with
`README.md` for the full technical reference.

---

## 1. How to open the discussion

Don't start drawing boxes immediately. Restate the problem in your own words, narrow scope out
loud, and name the thing you think the interviewer actually wants to see:

> "So this is Zomato/Swiggy-style — restaurants with menus, a customer orders, the restaurant
> accepts and cooks it, a delivery partner picks it up and drops it off. I think the
> interesting parts here are the order lifecycle state machine, how a delivery partner gets
> assigned without double-booking a rider, and the concurrency around all of that — not the UI
> or the payment gateway integration itself, which I'll treat as a black box. Let me confirm
> scope with a few questions, then I'll sketch the entities."

This signals you already know where the real complexity is before you've written a line of
code — that's the single biggest signal in the first two minutes.

---

## 2. First 5 clarifying questions to ask

1. "Can a cart/order span multiple restaurants, or is it always one restaurant per order?"
2. "Once a restaurant accepts an order, can they still back out, or only cancel via a
   different path?"
3. "Is delivery-partner assignment automatic (nearest-available), or does the interviewer want
   me to model a manual dispatcher step?"
4. "Should I model payment and refund, or can I treat that as already handled upstream?"
5. "Do discounts/promotions need to compose (stack), or is it always exactly one promo code
   per order?"

(Full rationale for each, and the assumption I land on when unanswered, is in README §3.)

---

## 3. Requirements to establish

State these back to the interviewer in one breath before designing, so you're both anchored:

- Functional: menu/availability management, cart, checkout with re-validation, order lifecycle
  with accept/reject-with-timeout, automatic nearest-partner assignment, payment+refund,
  real-time tracking, composable discounts.
- Non-functional: this is a single-process, in-memory design; the bar is *correctness under
  concurrency* and *clean extensibility* (SOLID, patterns used purposefully), not distributed
  scale — say this explicitly so you don't accidentally wander into a systems-design tangent.

---

## 4. How to identify core entities

Walk the user journey and name a noun every time a *new kind of thing with state/identity*
shows up — don't go model-first, go story-first:

> "Restaurant has a menu — so `Restaurant` and `MenuItem`. Customer builds a cart —
> `Cart`/`CartItem`. Checkout creates an `Order`, which is the one that really matters because
> it's long-lived and goes through many states. Assigning a rider needs `DeliveryPartner`.
> Charging money needs `Payment`. And I'll need something representing money itself,
> `Money`, and a coordinate, `GeoLocation`, as value objects — not entities, because they have
> no identity of their own."

Explicitly separate **entities** (have identity, e.g. `Order`, `Restaurant`) from **value
objects** (compared by value, e.g. `Money`, `GeoLocation`, `OrderItem`) out loud — interviewers
notice when a candidate conflates the two.

---

## 5. How to explain class relationships

Narrate ownership, not just "has-a":

> "`Restaurant` *owns* a `Menu`, which *owns* `MenuItem`s — composition, they don't outlive the
> restaurant. `Order` *owns* a frozen, immutable list of `OrderItem` snapshots — not live
> references into the menu, because I don't want a later price change to retroactively affect
> an order someone already paid for. `Order` *references* its customer/restaurant/
> delivery-partner by id, not by object — that's an association, not ownership, because those
> entities have independent lifecycles managed by their own services."

---

## 6. How to draw the architecture

Draw top-down, left-to-right: client-facing services at the top (`CartService`,
`OrderService`), domain objects in the middle (`Order`, `Restaurant`, `DeliveryPartner`), and
the three "pattern" layers off to the side (`state`, `observer`, `strategy`) so the interviewer
can see you're not burying pattern usage inside a god class. Say out loud which class is the
aggregate root (`Order`) and which service is the orchestrator (`OrderService`) — this is the
one class allowed to know about all the others; everything else should look narrow.

---

## 7. Which interfaces to create first

In this order, and say why:

1. `OrderState` — because the whole lifecycle hinges on it; get the contract right before
   writing any concrete state.
2. `OrderObserver` — tiny, one method, but establishes the tracking story early so you don't
   bolt it on awkwardly later.
3. `DeliveryPartnerAssignmentStrategy` — one method, easy to stub with a trivial
   implementation first and refine later.
4. `PriceCalculator` — last, because pricing is the least structurally risky part; get the
   state machine and assignment right first.

---

## 8. Which classes to implement first

1. `Money`, `GeoLocation` (tiny value objects — unblock everything else).
2. `MenuItem`, `Menu`, `Restaurant` (simple, no surprises).
3. `OrderItem`, `Order` **skeleton** (fields + constructor only, no transitions yet).
4. `OrderState` + one or two concrete states (`PlacedState`, `AcceptedState`) to prove the
   pattern compiles and reads well, *then* fill in the rest.
5. `DeliveryPartner` with the CAS reservation method — this is usually where you earn the most
   credit, so don't leave it for the last five minutes.
6. Services (`RestaurantService`, `CartService`, `DeliveryAssignmentService`, `PaymentService`,
   `OrderService`) last, wiring the above together.

---

## 9. Which design patterns to introduce and when

Introduce a pattern **at the moment its need becomes concrete**, not up front as a checklist:

- Mention **State** the instant you sketch the order-status enum and someone (you or the
  interviewer) says "and then what can happen next depends on the current status" — that's
  your cue.
- Mention **Observer** when you get to "how does the customer see live updates" — don't
  introduce it earlier, it'll look like pattern-first thinking.
- Mention **Strategy** when you get to delivery-partner assignment and say "nearest available
  for now, but this should be swappable" — naming the *alternative* you're deferring
  (round-robin, fairness-based) earns extra credit.
- Mention **Decorator** when discussing discounts, specifically framed as "these need to
  *stack*, which is why I'd reach for Decorator over a single enum of promo types."

---

## 10. How to discuss concurrency

Don't say "I'd synchronize everything." Name the *specific* shared mutable state and the
*specific* race, then name the *specific* guard:

> "The one race that actually matters here is: a delivery partner is in the middle of being
> assigned to an order at the exact moment that order gets cancelled. I'd put a single
> `synchronized` block on the `Order` instance covering both the status check and the
> partner-id write, so they're atomic with respect to each other — whichever operation's
> critical section runs first fully determines the outcome, and the loser either throws a
> clean 'invalid transition' or gets told 'assignment not allowed anymore' and releases the
> rider back to the pool. For the rider reservation itself, I'd use a compare-and-set on an
> atomic status field rather than a lock, because the assignment service needs to try several
> candidate riders in a retry loop, and CAS composes with that far more cheaply than
> lock/retry/unlock per candidate."

If asked "why not just lock everything globally" — answer directly: "that would serialize
unrelated orders against each other for no reason; I want contention scoped to the one order
(or one rider) actually involved."

---

## 11. How to discuss edge cases

Rattle these off proactively rather than waiting to be asked — they're exactly what a Staff
interviewer is listening for:

- Item goes out of stock between add-to-cart and checkout → re-validate at checkout, not just
  at add-time.
- Restaurant never responds → acceptance-timeout auto-cancel-and-refund.
- Two orders want the same nearest rider → CAS reservation, retry next-best on loss.
- Customer cancels right as a rider gets assigned → covered above; say explicitly that
  cancellation after preparing starts is *not* self-service.
- Duplicate network retry of a cancel/refund call → idempotent refund via CAS, not a flag you
  have to remember to check manually everywhere.
- Zero-item or empty-cart checkout → rejected before any order object is even created.

---

## 12. How to handle interviewer interruptions

- **If interrupted with "what if two restaurants..."** — pause your current thread, answer the
  new question in 1–2 sentences, then explicitly say "let me park that and come back to
  finishing the state machine" so you visibly manage your own time rather than spiraling.
- **If interrupted with "why didn't you use pattern X"** — answer with the specific trade-off
  (see README §7's "patterns deliberately not used"), don't get defensive, and don't rip up
  your design unless they're clearly asking you to change it.
- **If you realize mid-explanation you made a modeling mistake** — say so directly ("actually,
  I think `Cart` shouldn't allow items from two restaurants — let me fix that") rather than
  quietly papering over it; interviewers respect visible self-correction far more than a
  silent, slightly-wrong design defended to the end.

---

## 13. How to prioritise if time is running out

Drop things in this order (last-dropped = most important to keep):
1. Production-evolution concerns (monitoring, DB schema, message queues) — mention in one
   sentence, don't design them.
2. Round-robin/alternative strategy implementations — mention as "I'd add a second
   `Strategy` implementation here if needed," don't write it.
3. Decorator-based pricing — can be reduced to "I'd apply a flat discount inline" if truly
   out of time; say explicitly you're simplifying.
4. **Never drop:** the order state machine and the delivery-partner CAS reservation — these
   are the load-bearing walls of this problem; if you only have time for one deep-dive, it's
   the cancel-vs-assign race in §11 above.

---

## 14. Three full walkthroughs with realistic timelines

### 30-minute version
- 0–3 min: restate problem, scope out loud, ask 3 clarifying questions (not all 5).
- 3–8 min: entities + relationships (verbally, lightweight diagram — no full class diagram).
- 8–14 min: order state machine — sketch the `OrderState` interface + 2–3 concrete states.
- 14–20 min: delivery-partner CAS reservation — this is the concurrency centerpiece; spend
  real time here.
- 20–26 min: one more pattern, briefly — Observer *or* Decorator, interviewer's pick if asked.
- 26–30 min: summarize trade-offs you'd revisit with more time (itemized invoice breakdown,
  spatial index for nearest-partner at scale).

### 45-minute version
- 0–5 min: scope + all 5 clarifying questions.
- 5–12 min: entities, value objects, relationships — draw the class diagram.
- 12–20 min: order state machine, written out as real interface + 3–4 concrete classes.
- 20–30 min: delivery-partner assignment — Strategy interface, CAS reservation,
  `DeliveryAssignmentService`'s retry loop, and the cancel-vs-assign race explained with a
  mini sequence diagram.
- 30–37 min: Observer for tracking + Decorator for pricing, both briefly implemented.
- 37–43 min: concurrency deep-dive — name every shared mutable field and its guard.
- 43–45 min: trade-offs + what you'd do differently in production.

### 60-minute version
- 0–5 min: scope + clarifying questions.
- 5–15 min: full entity/value-object/relationship pass with class diagram.
- 15–25 min: order state machine, fully implemented in code (not just talked through).
- 25–40 min: delivery-partner assignment end to end — Strategy, CAS, retry loop, both races
  (accept-vs-reject and cancel-vs-assign) worked through with the interviewer, ideally live-
  coding a small concurrency test.
- 40–48 min: Observer (tracking) and Decorator (pricing) both implemented, with the Decorator
  composition shown via a runnable example (3-decorator stack).
- 48–55 min: concurrency + failure scenarios in depth — acceptance timeout, idempotent
  refund, checkout re-validation race.
- 55–60 min: production evolution (DB schema sketch, monitoring, durable scheduling) and
  open trade-offs.

---

## 15. What I would say to the interviewer

(Natural, speakable sentences — not textbook language.)

- "I'm going to treat payment as a black box that can succeed or fail — I don't want to spend
  our time modeling a payment gateway, but I do want to get the idempotent-refund part right
  because that's a classic place real systems double-pay people."
- "The reason I'm using a State pattern here instead of a big switch statement is that this
  lifecycle has eight statuses with non-symmetric transitions — cancel works from two of them
  but not the rest — and I'd rather that rule live in one small class than get duplicated
  wherever someone calls `cancel()`."
- "I want to call out something subtle: the race you'd naturally think of — 'restaurant rejects
  while a rider's being assigned' — actually can't happen in my model, because reject only
  works before acceptance, and assignment only starts after acceptance. The real race is
  cancellation after acceptance, which I do handle."
- "I'm using compare-and-set here instead of a lock because the assignment service needs to
  try several riders in a loop if the first one's taken — locking and retrying would be more
  overhead for the same guarantee."
- "I'll be honest, if I had more time I'd want each discount decorator to also report how much
  it took off, so you get a real itemized invoice — right now I'm only keeping the final total,
  which is a deliberate simplification I'd flag in a design doc."
- "I don't think this needs a database in an interview setting — every invariant I'm enforcing
  with an atomic field or a synchronized block has a direct SQL equivalent (an `UPDATE ... WHERE
  version = ?`), so I'd rather spend our time on the concurrency logic itself."
- "At real scale I'd shard delivery-partner assignment by city — a rider in one city is never a
  candidate for an order in another, so that's a natural, low-risk partition, not something
  that needs distributed transactions."
