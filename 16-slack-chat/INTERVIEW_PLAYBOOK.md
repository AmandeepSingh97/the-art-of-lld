# Interview Playbook — Slack / Workplace Chat System

A speakable, time-boxed playbook for driving this problem end-to-end in a live interview.

---

## 1. How to open the discussion

Start by restating the problem in your own words and immediately proposing scope boundaries,
rather than diving into classes:

> "So we're building the backend model for something like Slack: workspaces containing public
> and private channels and DMs, with message history, real-time delivery, presence, mentions,
> and edit/delete. Before I start drawing boxes, I'd like to nail down a few things that change
> the design a lot — particularly how big channels can get, since that affects how I deliver
> messages in real time."

This signals: you understand the breadth of the prompt, and you know real-time delivery at
scale is the interesting part, before you've been told that explicitly.

## 2. First 5 clarifying questions to ask

1. "Can a channel have thousands or tens of thousands of members, or are we always talking
   about team-sized channels (tens to low hundreds)?" — drives fan-out strategy choice.
2. "Who can edit or delete a message — just the author, or also channel/workspace admins?"
3. "Does message ordering need to be globally consistent, or just within a channel?"
4. "Is read/unread tracked per user, or per device?"
5. "Should deleted or edited messages still show up in history (with a marker), or disappear
   entirely?"

(Full rationale for each in README §3 — use that as your answer bank if pushed on "why does
that matter?".)

## 3. Requirements to establish

Say out loud, and write down if there's a whiteboard/doc:
- **Functional:** create workspace/channel/DM, send message, paginated history, edit/delete
  with audit, real-time delivery, presence, mentions/notifications, read receipts/unread count,
  rate limiting.
- **Non-functional:** correctness under concurrency (multiple senders, concurrent edit/delete),
  consistent per-channel ordering, reasonable complexity bounds on pagination and unread count
  (not "scan all history"), single-process/in-memory scope (explicitly defer full
  distribution).

## 4. How to identify core entities

Walk from the nouns in the prompt to entities, then ask "what's an entity (has identity,
changes over time) vs a value object (fully described by its fields)?":

> "User, Workspace, Channel, Message are entities — they have identity and a lifecycle.
> A channel type (public/private/DM) is naturally an enum. An edit record — previous content
> plus timestamp — doesn't need its own identity, so that's a value object. Same for the
> message/channel/user ids themselves — I'll make those small typed wrappers instead of raw
> strings, so I can't accidentally pass a channel id where a user id is expected."

That last sentence is a good staff-level signal — flag it, don't wait to be asked why you did
it.

## 5. How to explain class relationships

> "A Workspace owns membership at the workspace level — who's allowed to be `@mentioned` or
> join a public channel at all. A Channel owns its own membership, which is a subset of
> workspace membership. I'm linking them by id, not by holding object references both ways —
> that avoids a bidirectional graph I'd have to keep in sync. Messages live in a separate
> per-channel ordered store, not inside the Channel object itself, because messages have
> totally different access patterns (append-heavy, paginated) than membership (small set,
> checked on every send)."

## 6. How to draw the architecture

Draw top-down: client → a single Facade (`ChatService`) → the subsystems it orchestrates
(repositories, message store, delivery strategy, rate limiter, event bus) → the event bus
fanning out to notification service and (simulated) client listeners. Say explicitly:

> "I'm putting everything behind one facade so nobody can bypass validation — like sending to
> a channel you're not a member of — by going around it and hitting the message store
> directly."

Then draw the event bus as its own box and say:

> "This is where I want to be explicit: in a real system, this box is WebSocket connections
> fanned out through a broker like Kafka or Redis Pub/Sub. Here it's an in-process observer
> list. I'm modeling the delivery *semantics* faithfully — who gets notified, push vs pull —
> but not the actual network transport, which is out of scope for an LLD."

## 7. Which interfaces to create first

In this order, and say why:
1. `ChannelDeliveryStrategy` — because the fan-out trade-off is the centerpiece discussion;
   defining the interface early lets you talk about both implementations before writing either.
2. `MessageStore` — the other core abstraction (ordering + pagination).
3. `EventBus` — the pub-sub seam.
4. `ChatService` — the facade, last, because its shape depends on what the above expose.

## 8. Which classes to implement first

1. Value objects/ids (`UserId`, etc.) — fast, establishes the type-safety habit early.
2. `User`, `Workspace`, `Channel` (+ `ChannelType`) — the structural skeleton.
3. `Message` + `MessageState` — get the edit/delete state machine right early, since it's a
   concurrency-sensitive hotspot you'll want time to think about.
4. `InMemoryMessageStore` — sequence allocation + pagination.
5. `ChannelDeliveryStrategy` implementations + `ChannelDeliveryPolicy`.
6. `TokenBucketRateLimiter`, `UnreadTracker`, `PresenceService`, `MentionParser`,
   `NotificationService` — these are mostly independent, implement in whatever order time
   allows.
7. `ChatServiceImpl` wiring it all together, last.

## 9. Which design patterns to introduce and when

Introduce a pattern only when you hit the problem it solves, not up front as a checklist:
- Mention **Strategy** the moment you discuss channel size affecting delivery — that's the
  natural trigger, and it's the single most important pattern for this problem.
- Mention **Observer/Pub-Sub** when you draw the event bus.
- Mention **Facade** when you start `ChatService` — explain it's there so validation can't be
  bypassed.
- Mention **Factory** only when you get to channel creation and need to explain why a DM needs
  different validation than a public channel.
- Explicitly say what you're *not* using and why (no Singleton, no Builder) if time allows —
  it shows judgment, not just pattern recall.

## 10. How to discuss concurrency

Don't say "I'd use `ConcurrentHashMap` everywhere" and stop. For each piece of shared mutable
state, name: what the race is, what guarantee you need, and the specific mechanism:

> "The trickiest one is per-channel sequence numbers for messages — two senders can't get the
> same number. I give each channel its own `AtomicLong`; `incrementAndGet()` is the single
> coordination point, and because every thread gets a unique number before it even touches the
> map, the subsequent `put` calls never collide — so I don't need a lock around the pair."

> "Editing a message is trickier: I need to check 'is this the sender, and is it not already
> deleted' and then change three fields together, atomically. That's a compound operation, so
> I put a `synchronized` block scoped to just that one `Message` object — editing message A
> never blocks editing message B."

If asked "why not lock-free/CAS here too?", have the answer ready: single-message contention
is realistically near-zero, so the simplicity of `synchronized` wins over a hand-rolled
version-CAS retry loop (full reasoning in README §11).

## 11. How to discuss edge cases

Have these ready without being prompted:
- Edit racing a delete on the same message (resolved by the per-message lock).
- A user removed from a channel mid-send (membership re-checked at send time, not cached).
- Retried `sendMessage` creating a duplicate (acknowledge honestly: not idempotent in this LLD,
  explain the production fix — client idempotency keys).
- `markRead` called out of order or with a stale/smaller value (monotonic via `Math::max`).
- Rate limit hit (fails closed, before any state mutation — no partial message).

## 12. How to handle interviewer interruptions

- If interrupted with "what if channels are huge?" mid-explanation of the basic model: don't
  panic-redesign. Say "Good — that's exactly the fan-out-on-write vs fan-out-on-read trade-off,
  let me finish the base model in one sentence and then go straight into that," and do it.
- If interrupted with "why not just use a database for all of this?": acknowledge the
  question is fair, explain the LLD is deliberately in-memory so you can focus on object
  design and concurrency, and that every store is behind an interface specifically so swapping
  in a real DB later is mechanical, not a redesign.
- If pushed on a decision you made as an assumption (e.g. sender-only edit/delete): don't
  defend it as "the only right answer" — say what you assumed, why, and how it would change
  (see README §3 and §16) — this shows flexibility, not rigidity.

## 13. How to prioritise if time is running out

Drop, in this order, narrating that you're doing so:
1. Presence service (nice-to-have, lowest interdependency with everything else).
2. The adaptive `ChannelDeliveryPolicy` — fall back to "always fan-out-on-write" and *say*
   you'd add the Strategy/threshold given more time (you still get credit for recognizing the
   trade-off even if you don't fully implement both sides).
3. Notification service / mention parsing — keep sending/history/edit/delete working first.
4. Pagination — fall back to "return everything, newest-first" and say what keyset pagination
   would look like.

Never drop: membership checks, the message state machine (edit/delete correctness), and the
sequence-number concurrency story — these are the core of what's being evaluated.

## 14. Three full walkthroughs with realistic timelines

### 30-minute walkthrough
- 0–3 min: restate problem, ask 3–4 clarifying questions (don't over-invest here).
- 3–8 min: core entities (`User`, `Workspace`, `Channel`, `Message`) + enums, on a whiteboard
  or in a doc, narrating responsibilities as you go.
- 8–15 min: `ChatService` facade signature + `MessageStore` with sequence-based pagination;
  write real code for `append`/`getPage`.
- 15–22 min: concurrency discussion for sequence allocation and message edit/delete (verbal +
  a couple of lines of `synchronized`/`AtomicLong` code, not full files).
- 22–27 min: fan-out-on-write vs fan-out-on-read trade-off, verbally, with the Strategy
  interface sketched (not implemented).
- 27–30 min: rate limiting (verbal: Token Bucket, why), wrap up with what you'd add given more
  time.

### 45-minute walkthrough
- 0–5 min: clarifying questions + scope.
- 5–12 min: entities, enums, relationships (Workspace/Channel/Message), value-object ids.
- 12–20 min: `MessageStore` with real `append`/`getPage` code; discuss `ConcurrentSkipListMap`
  choice and complexity.
- 20–30 min: `Message` edit/delete state machine with real code, concurrency reasoning
  (per-message lock, visibility via `volatile`).
- 30–38 min: fan-out-on-write vs fan-out-on-read — implement both `ChannelDeliveryStrategy`
  classes for real, plus the `EventBus` interface.
- 38–43 min: rate limiting (real Token Bucket code) and unread-count tracking (`AtomicLong` +
  `Math::max`).
- 43–45 min: summarize trade-offs made, what's deferred to production.

### 60-minute walkthrough
- 0–5 min: clarifying questions + scope.
- 5–15 min: full entity/value-object model, enums, exceptions.
- 15–25 min: `MessageStore` + pagination, with real code and a quick test narrated out loud
  (even if not actually run) for the concurrent-append scenario.
- 25–35 min: `Message` state machine with full code + concurrency justification.
- 35–45 min: both delivery strategies + `ChannelDeliveryPolicy` + `EventBus`, with a sequence
  diagram sketch contrasting the two (see `diagrams/sequence-fanout-tradeoff.md` for the shape
  to draw).
- 45–52 min: rate limiting, unread tracking, mentions/notifications — real code for at least
  the Token Bucket.
- 52–57 min: `ChatServiceImpl` wiring + a couple of failure scenarios (membership race, rate
  limit, edit-after-delete).
- 57–60 min: production-evolution discussion (transport, persistence, idempotency) — show you
  know where the line between LLD and distributed system is.

## 15. What I would say to the interviewer

- "I want to get the fan-out trade-off on the table early, because I think that's the real
  meat of this problem — everything else is fairly standard CRUD-plus-concurrency."
- "I'm deliberately keeping this in one process with in-memory storage — I'll call out where
  I'd reach for a database or a message broker, but I don't want to spend our time designing a
  distributed system when the ask is an LLD."
- "This lock is scoped to a single message, not the whole channel, because I don't want
  editing one message to block someone editing a totally different message."
- "I'm going to model real-time delivery with a plain observer list instead of actual
  WebSockets — same idea, way faster to build in 45 minutes, and I'll flag exactly where the
  real transport would plug in."
- "Honestly, sending isn't idempotent in what I've built — a retried request would create a
  duplicate message. In production I'd fix that with a client-supplied idempotency key. I'd
  rather tell you that gap exists than pretend it doesn't."
- "I thought about making edit/delete admin-overridable too, but I'm going to scope it to
  sender-only unless you want me to add a role check — happy to sketch how that'd plug in."
- "Unread count is just `latestSequence minus lastRead` — O(1) math, no scanning — regardless
  of which fan-out strategy delivered the message, which is actually a nice property: the
  unread badge doesn't care how the message got to you."
