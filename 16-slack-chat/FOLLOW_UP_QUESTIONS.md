# Follow-Up Questions — Slack / Workplace Chat System

25+ realistic interviewer follow-ups, categorized, each with a complete answer specific to
this design (no generic filler, no repeats across categories).

---

## Object-Oriented Design

**1. Why is `PresenceStatus` tracked in a separate `PresenceService` instead of as a field on
`User`?**

Because presence changes at a completely different rate and for a completely different reason
than identity: a user's username/display name changes rarely and is driven by the user
themselves, while presence changes on every heartbeat/focus-change from every connected client
— potentially many times a minute. Coupling them on one object would mean every presence update
mutates the same object every other part of the system treats as a stable identity reference,
and would force `User` to deal with concurrency concerns (`volatile`/atomics) it otherwise
wouldn't need at all. Splitting them also means presence can be queried/updated independently
without touching the user repository.

**2. Why does `Channel` not hold a reference to its parent `Workspace` object?**

To avoid a bidirectional object graph that has to be kept consistent from both sides (if you
delete/modify a `Workspace`, every `Channel` holding a reference to it needs to agree about
what that means) and to avoid two classes needing to `equals()`/`hashCode()` each other
indirectly through a cycle. `Channel` holds a `WorkspaceId` (a value, not a live reference), and
the repository layer resolves it when needed — the same pattern a relational foreign key uses,
deliberately chosen over an object-graph reference.

**3. `Message` is described as "self-encapsulated." What does that mean concretely, and why
does it matter here?**

It means `Message` validates and enforces its own invariants (sender-only edit/delete, no
editing after delete) *inside its own methods*, rather than exposing public setters and trusting
`ChatServiceImpl` to check things before calling them. It matters because it makes the invariant
impossible to bypass — any future caller (a new feature, a bug in the service layer, a direct
test) gets the same guarantee for free, instead of the guarantee being "as strong as the
discipline of whoever calls this class correctly."

**4. Why are `UserId`, `ChannelId`, `WorkspaceId`, and `MessageId` four separate types instead
of one generic `Id<T>`?**

A generic `Id<T>` would need a phantom type parameter purely for compile-time differentiation,
since the runtime representation (a wrapped `String`) is identical across all of them — this
adds generic-type ceremony (`Id<User>` vs `Id<Channel>`) without adding any behavior the
simpler four-record approach doesn't already have. Four small records are more explicit, each
can have its own `generate()`/`of()` factory methods and own tightly-scoped validation, and
readers don't need to reason about erasure/generics to understand what a method signature
means.

**5. Why is `ChannelType` an enum rather than a subclass hierarchy (`PublicChannel`,
`PrivateChannel`, `DirectMessageChannel` extending a common `Channel` base)?**

Because the behavioral differences between the three types are narrow and localized (join
rules, rename rules, leave rules) rather than pervasive — modeling them as a type hierarchy
would mean every piece of code that currently does `if (channel.type() == DIRECT_MESSAGE)` would
instead need an `instanceof`/visitor dispatch, for no additional type safety benefit, since
`Channel` as a single concrete class with an enum field is simpler to construct, serialize, and
reason about. A subclass hierarchy would be justified if the channel types had substantially
different fields or a much larger set of type-specific behaviors — they don't, here.

---

## SOLID Principles

**6. Where does Single Responsibility actually show up as a *design decision* in this codebase,
not just as a slogan?**

The `MessageStore` (ordering/storage/pagination) is deliberately separate from `UnreadTracker`
(read-position bookkeeping), even though both are "about messages" — because one's reason to
change is "how we store and paginate history" and the other's is "how we compute unread badges,"
which are genuinely independent concerns that evolve on different timelines (you could redesign
pagination without touching unread logic at all, and vice versa, which the current split proves
by construction).

**7. How does `ChannelDeliveryStrategy` satisfy Open/Closed?**

Adding a third delivery strategy (e.g. a hybrid "push to online members only, let offline
members pull") requires writing one new class implementing the interface and updating
`ChannelDeliveryPolicy`'s selection logic — it does not require modifying
`FanOutOnWriteStrategy`, `FanOutOnReadStrategy`, or anything in `ChatServiceImpl`, which only
ever calls the interface type. The system is open for extension (new strategies) and closed for
modification (existing strategies and their callers untouched).

**8. Does `ChatService` violate Interface Segregation by bundling messaging, channel
management, and presence into one interface?**

It's a deliberate, bounded exception: `ChatService` is explicitly a Facade (§7), whose entire
purpose is to be one coherent entry point for "everything a chat client needs to do" — splitting
it into `MessagingService`/`ChannelService`/`PresenceService` interfaces would scatter the very
thing Facade exists to centralize, and would push the sequencing/validation responsibility back
onto callers (exactly what Facade is meant to avoid, see §7's rejected-alternative reasoning).
ISG is better applied *inside* the facade's dependencies (`RateLimiter` has exactly one method;
`EventBus` has exactly two) rather than to the facade itself.

**9. Where does Liskov Substitution actually get exercised in this design, concretely (not just
"interfaces are substitutable")?**

`ChannelDeliveryPolicy.strategyFor(channel)` returns a `ChannelDeliveryStrategy` reference, and
`ChatServiceImpl.sendMessage` calls `.deliver(...)` on it without ever checking which concrete
type it got back. Both `FanOutOnWriteStrategy` and `FanOutOnReadStrategy` can be substituted for
each other at that call site with zero behavioral assumption violated by the caller — the caller
genuinely does not know or care which one it received, which is the actual test of LSP (not
merely "both implement the interface," but "the caller's correctness doesn't depend on which
one it is").

**10. Which dependency in this codebase best demonstrates Dependency Inversion, and why does it
matter there specifically?**

`MentionParser` depends on `Function<String, Optional<UserId>>` rather than directly on
`UserRepository`. This matters because `MentionParser` is a pure parsing concern — it should
not need to know anything about how usernames are looked up, persisted, or cached. Depending on
the abstraction (a function) instead of the concretion (`UserRepository`) is what let
`MentionParserTest` test mention-parsing logic with zero repository/`ChatService` setup at all
— a direct, visible payoff of the inversion, not just a theoretical one.

---

## Design Patterns

**11. Why Strategy instead of a simple boolean flag (`channel.useReadFanout = true/false`) on
`Channel`?**

A boolean flag couples the *decision of which strategy to use* to the `Channel` entity itself,
meaning `Channel` would need to know about delivery mechanics (a concern it currently has zero
knowledge of) and any new strategy would require another flag/field, not a new class.
`ChannelDeliveryPolicy` keeps the decision external and swappable (even per-environment, e.g.
tests can inject a policy with a different threshold) while `Channel` stays a pure
membership/identity entity.

**12. You rejected Abstract Factory for `ChannelFactory`. What would have actually justified
using it?**

Abstract Factory earns its complexity when you have *multiple families of related objects* that
need to vary together consistently (e.g. "a UI toolkit's buttons, checkboxes, and menus must all
be either all-Windows-style or all-Mac-style, never mixed"). Here there is exactly one product
(`Channel`) with type-varying validation — there's no second product type that needs to vary in
lockstep with channel type, so introducing the extra factory-of-factories indirection would add
ceremony with no corresponding flexibility payoff.

**13. Could the Observer pattern here have been implemented with plain Java listeners
(`PropertyChangeListener`-style) instead of a custom `EventBus`?**

Yes, structurally similar, but `PropertyChangeListener` is tied to a single subject object
firing property-change notifications about itself — it doesn't naturally support *topic-based*
routing (one user's mailbox, one channel's topic, a global presence topic) from a shared,
central dispatcher serving many unrelated subjects. A custom, string-topic-keyed `EventBus` was
chosen specifically because the system needs pub-sub *across* entities (a message in channel X
needs to reach listeners keyed by *user* id, not by the channel object itself) — a shape
`PropertyChangeSupport` doesn't fit without working around its design.

**14. Why didn't you implement `MessageState` as a full State design pattern (a `MessageState`
interface with `ActiveState`/`EditedState`/`DeletedState` classes, each implementing
`edit()`/`delete()`)?**

A full State pattern earns its cost when the *behavior* (not just the data) varies richly per
state and the state object itself needs to decide/trigger transitions. Here there are only two
guarded transitions (`ACTIVE/EDITED → DELETED` is terminal; editing is permitted i,ff not yet
deleted) and the logic is a one-line `if` per method — expressing that as three additional
classes with their own `edit`/`delete` methods (most of which would just throw) would be
over-engineering for a 3-value enum with simple guards. State pattern becomes worth it once you
have, say, 5+ states with materially different behavior in multiple methods.

**15. Is `ChannelFactory` a Factory Method or a (plain) Factory, and does the distinction
matter here?**

It's a plain static Factory (sometimes called a "Simple Factory") — a single class with static
creation methods, not a `Factory Method` (which requires subclasses to override a creation
method to vary what gets produced) and not Abstract Factory (families of products). The
distinction matters for vocabulary precision in an interview: calling this "Factory Method"
would be describing a different, unused pattern — being precise about which pattern you
actually applied (and why the "textbook" Factory Method/Abstract Factory variants weren't
needed) is itself a signal of real understanding versus pattern-name-dropping.

---

## Data Structures

**16. Why `ConcurrentSkipListMap` and not a `TreeMap` wrapped in `Collections.synchronizedMap`?**

`Collections.synchronizedMap(new TreeMap<>())` serializes *every* operation — reads and writes
alike — behind one lock, meaning concurrent readers (different users paginating the same
channel at once) block each other even though they're not conflicting. `ConcurrentSkipListMap`
provides genuinely concurrent reads (no locking for `get`/range-view traversal) with scalable
writes, at the cost of O(log n) rather than `TreeMap`'s *slightly* cheaper constant factor for
single-threaded access — a trade clearly worth making given the read-heavy, concurrent access
pattern of chat history.

**17. Why `LinkedHashSet` inside `MentionParser.parseMentions` instead of a plain `HashSet`?**

To preserve the order mentions were encountered in the message text, which is a nice-to-have
for deterministic test assertions and potentially useful if a future feature wants to show
"mentions in order of appearance" — `HashSet` would work functionally identically for the
*set* semantics the method promises, but iteration order would be unspecified/not matching
text order, which is a minor but free-to-get correctness-of-intent improvement.

**18. The unread tracker uses a nested `ConcurrentHashMap<ChannelId, ConcurrentHashMap<UserId,
AtomicLong>>`. What's the downside of nesting maps like this versus a single flat map keyed by
a composite `(ChannelId, UserId)` key?**

Nesting means "all readers of channel X" is directly enumerable (iterate the inner map) without
scanning every entry of a flat map and filtering by channel — useful for, e.g., "notify everyone
who hasn't read message N yet." The downside is two map lookups per operation instead of one,
and slightly more allocation (the inner map itself) the first time any user reads any message in
a channel. For this workload (channel-scoped operations are common, cross-channel scans are
rare) the nested structure's access pattern fits better than a flat map would.

**19. Why does `PageRequest` cap `limit` at 500 — where does that number come from and what
would you do differently with real data?**

500 is a defensible, round, "prevents a single pathological request from materializing an
enormous response" ceiling, chosen the same way most paginated APIs pick a max page size (e.g.
GitHub's API caps at 100) — it's a safety bound, not a measured constant. With real traffic data
you'd tune it based on actual payload size targets (e.g. "keep a page under 200KB serialized")
and typical client page-rendering needs, rather than picking a round number — flagged here as
exactly that: a reasonable placeholder, not a researched constant.

**20. Could you use a plain `ArrayDeque` instead of `ConcurrentSkipListMap` if you only ever
needed "most recent N messages," never full history?**

Yes — if the requirement were truly "only ever show the last N messages, no deep history, no
arbitrary cursor-based pagination," a fixed-capacity ring buffer (`ArrayDeque` with manual
eviction, or `Collections.synchronizedDeque`) would be simpler and avoid retaining unbounded
history in memory at all. It was rejected here because the stated requirement is explicit
pagination over full history ("message sending/ordering/history (pagination over message
store)"), which needs arbitrary-depth, stable cursor-based access that a bounded ring buffer
cannot provide once content ages out of the ring.

---

## Java Internals

**21. `UserId` and friends are `record`s. What do you get for free, and what did you still have
to write by hand?**

For free: `equals`/`hashCode` (field-based), `toString()` (default, though overridden here for a
cleaner bare-value format), a canonical constructor, and accessor methods (`value()`). Written
by hand: the compact constructor's validation (`Objects.requireNonNull`, blank-check) — records
don't validate for you, they just give you a hook (the compact constructor) to put validation
in, which this codebase uses in every id record.

**22. `ChatEvent` is a `sealed interface`. What does `sealed` actually buy you here that a plain
interface wouldn't?**

With a plain interface, any code anywhere could implement `ChatEvent` with a new event type the
rest of the system doesn't know how to handle, and a `switch` over event types could never be
exhaustively checked by the compiler. `sealed` with an explicit `permits`-equivalent (nested
records) means the compiler knows the *complete* set of possible subtypes — a `switch`
expression over `ChatEvent` without a `default` branch is a compile error if a new event type is
later added and not handled, which turns "forgot to handle the new event type somewhere" from a
runtime bug into a compile-time error at every switch site.

**23. Why does `Message` mark `content`, `state`, `mentionedUserIds`, and `deletedAt` as
`volatile` even though `edit`/`delete` are already `synchronized`?**

`synchronized` guarantees mutual exclusion *and* visibility for threads that go through the
same lock — but readers like `content()`/`state()` are plain unsynchronized getters (by
design, for cheap frequent reads e.g. during pagination rendering) that never acquire the
`Message`'s monitor. Without `volatile`, a reader thread could observe a stale cached value from
its own CPU core/cache line indefinitely. `volatile` guarantees that any write by the
`synchronized` method becomes visible to any subsequent read by *any* thread, immediately —
covering the readers that intentionally don't take the lock.

**24. What's the actual difference between `Set.copyOf(...)` (used throughout) and
`Collections.unmodifiableSet(...)`?**

`Collections.unmodifiableSet` wraps the *given* mutable set — if the caller retains a reference
to the original mutable set and mutates it, the "unmodifiable" view changes too (it's a view, not
a copy). `Set.copyOf` makes an actual independent, immutable copy — no caller-held reference
can ever affect it after construction. Every defensive accessor in this codebase
(`Channel.memberSnapshot()`, `Workspace.memberSnapshot()`, `Message.mentionedUserIds()`) uses
`Set.copyOf`/`List.copyOf` specifically to guarantee true immutability, not merely an
unmodifiable view over data someone else can still mutate.

**25. Why `Instant` rather than `long` epoch millis or `java.util.Date` for all the timestamp
fields?**

`Instant` is immutable (unlike the notoriously mutable `Date`), nanosecond-precision, and has a
rich, readable API (`isBefore`/`isAfter`/`Duration.between`) without needing to remember what
unit a raw `long` represents (seconds? millis? nanos? — a `long` gives zero type-level hint).
`Instant` is also what the modern `java.time` APIs (`Clock`, `Duration`) are built around, making
this codebase trivially adaptable to inject a fake `Clock` for deterministic time-based testing
later, something a raw `long`/`System.currentTimeMillis()` approach makes considerably more
awkward.

---

## Multithreading

**26. Why `AtomicLong.incrementAndGet()` for sequence allocation instead of a `synchronized`
block around a plain `long` counter?**

`incrementAndGet()` compiles down to a lock-free compare-and-swap loop at the hardware level
(no OS-level mutex acquisition/contention/context-switch overhead), which is strictly cheaper
under contention than a `synchronized` block protecting the equivalent operation, for an
operation this simple (increment-and-read one value). `synchronized` would be justified instead
if the critical section did more than one atomic operation (as it does inside `Message.edit`,
which touches three fields together) — here it's exactly one indivisible operation, which is
the textbook case for preferring an atomic over a lock.

**27. Could two threads calling `InMemoryMessageStore.append` for the *same* channel ever end
up with the message objects visible out of order relative to their sequence numbers in the
`ConcurrentSkipListMap`?**

No — `ConcurrentSkipListMap` is a sorted map; regardless of the *insertion* order of `put` calls,
iterating it (or taking a `headMap`/`tailMap` view) always reflects key order, not insertion
order. So even if thread B's `put(5, ...)` physically completes before thread A's `put(3, ...)`
(a legitimate possible interleaving since A and B only coordinated on *claiming* unique sequence
numbers via `AtomicLong`, not on insertion order), any reader at any point sees keys in sorted
order — 3 before 5 — once both puts have completed. A reader racing in between the two puts
would simply not yet see key 3 or key 5, which is fine (that message hasn't "arrived" from that
reader's perspective yet) — it would never see them out of order relative to each other.

**28. If `NotificationService.onEvent` were changed to do a slow network call (e.g. send a
push notification over HTTP) instead of an in-memory list append, what breaks?**

Because `InMemoryEventBus.publish` dispatches synchronously on the calling thread, a slow
`onEvent` implementation would make the *sender's* `sendMessage` call block until the network
call completes (or times out) — a mention notification's external dependency would directly
degrade message-send latency for everyone, which is clearly wrong. This is exactly the
documented limitation in `InMemoryEventBus`'s javadoc (§7/§17): production systems dispatch each
listener on its own worker/queue precisely to prevent this coupling.

---

## Database and Persistence

**29. Why does the LLD intentionally avoid using an actual embedded database (e.g. H2, SQLite)
instead of hand-rolled `ConcurrentHashMap` repositories?**

Because the interview signal being tested is object modeling, interface design, and concurrency
reasoning — introducing a real (even embedded) database would shift focus to SQL/ORM mechanics
and schema migrations, which is explicitly called out as out of scope (§1). The repository
*interfaces* are still present specifically so this decision is reversible later without
touching calling code — the "avoid a real DB" choice is about implementation, not about
abandoning the abstraction that would make swapping to one easy.

**30. What would the unique index on `(channel_id, sequence)` from §12 actually protect against
that the application-level `AtomicLong` doesn't, once you're on multiple nodes?**

Once sequence allocation can no longer be a single in-process `AtomicLong` (because multiple
nodes might handle requests for the same channel), the *database* becomes the only shared
arbiter of "has this sequence number already been used for this channel" — the unique index
turns a would-be silent duplicate-sequence bug (two nodes both believing they allocated sequence
7) into a hard constraint violation the application must detect and retry/reconcile, rather than
quietly corrupting channel ordering. This is the same principle as the `message_dedup` unique
key in the idempotency discussion (Deep Dive Q15) — let the database enforce uniqueness
invariants that can't be safely enforced by independent, uncoordinated application processes.

---

## Performance

**31. If a single channel receives 1,000 messages/second sustained, what's the first component
in this design to show stress, and what's the fix?**

The per-channel `AtomicLong` + `ConcurrentSkipListMap` pair itself handles this volume fine in
raw throughput terms (CAS operations and skip-list inserts are cheap, sub-microsecond
operations) — the actual stress point would be `FanOutOnWriteStrategy` *if* such a hot channel
also had many members, since it does O(members) `EventBus.publish` calls per message, 1,000
times a second — that's where synchronous, inline dispatch (§11/§17) would start to visibly
back up the sender's thread. The fix at that volume is exactly what's flagged as a production
evolution: route this specific channel to `FanOutOnReadStrategy` regardless of member count
once *send rate*, not just member count, crosses a threshold — a refinement to
`ChannelDeliveryPolicy`'s selection criteria, not a redesign.

**32. Why does `getHistory` not cache recently-fetched pages?**

Because in this LLD's single-process, single-source-of-truth model, `ConcurrentSkipListMap`'s
range-view reads are already O(log n) and lock-free — adding a cache layer in front of a
data structure that's already this cheap to query would add cache-invalidation complexity
(the hardest problem in computer science, famously) for a marginal win. Caching becomes
worthwhile once reads cross a network/process boundary to a real database (§17), where even an
O(log n) indexed query has real network round-trip latency a local in-memory cache can avoid.

---

## Failure Handling

**33. If `RateLimiter.tryAcquire` itself threw an exception (e.g. a bug), what happens to the
message the user was trying to send?**

`sendMessage` calls `rateLimiter.tryAcquire(...)` *before* `messageStore.append(...)` — if it
threw, the exception would propagate straight out of `sendMessage` to the caller, and
critically, **no sequence number would have been allocated and no message would exist** in the
store. This ordering (validate everything, including rate limiting, strictly before any state
mutation) is a deliberate "fail before you commit" structure throughout `ChatServiceImpl` — it
means a bug in any validation step produces a clean exception, never a half-created message.

**34. A client calls `markRead` with a sequence number that refers to a message that has since
been deleted. Does that fail, and should it?**

It does not fail — `markRead`'s only check is that `uptoSequence` falls within `[0, latestSequence]`
for the channel; it does not care whether the specific message at that sequence still exists in
`ACTIVE` state. This is intentional: "I've read up through this point in the timeline" is a
valid, meaningful claim regardless of whether a message at that exact position was later
deleted — unread counting is about *position in the timeline*, not about the content/existence
of any one message at that position, so rejecting this would conflate two unrelated concerns.

---

## Production Architecture

**35. In a distributed version, which component in this design becomes a single-point-of-failure
first if you naively lift-and-shift it as one centralized service?**

`InMemoryEventBus`, if naively kept as one centralized in-process component that every gateway
node calls into synchronously — every message send across the entire system would funnel
through it, making it both a throughput bottleneck and a single point of failure (its crash
stops all real-time delivery everywhere, not just for one channel/workspace). This is precisely
why §17 specifies replacing it with a partitioned message broker (Kafka/Pulsar, inherently
designed for exactly this "many producers, many consumers, horizontally partitioned, no single
coordinator" shape) rather than just "running the same `EventBus` class on a bigger server."
