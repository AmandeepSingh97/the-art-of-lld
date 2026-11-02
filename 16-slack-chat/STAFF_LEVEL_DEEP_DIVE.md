# Staff-Level Deep Dive — Slack / Workplace Chat System

Twenty-plus problem-specific, challenging questions with reasoning-based answers — the kind of
pressure-testing a staff/principal interviewer would apply to this exact design, not a generic
checklist.

---

**1. Your sequence numbers are per-channel `AtomicLong`s in a single JVM. What's the first
thing that breaks if this service runs on two nodes behind a load balancer, and how would you
notice before a customer does?**

Two nodes would each have their own in-memory `AtomicLong` for the same channel if a
request for that channel is routed to either node — two users sending "simultaneously" to the
same channel via different nodes could both get sequence `1`, `2`, etc. independently, and
each node's view of "the channel's history" would silently diverge; neither node would error,
they'd just disagree. You'd notice it the hard way — a client paginating would see duplicate
or out-of-order sequence numbers, or two different messages both claiming to be sequence 5 — unless
you had a monitoring check that explicitly validates "max(sequence) per channel is unique and
dense" as a background job. The fix is to make sequence allocation single-owner per channel
(sticky routing to one node per channel, or a database sequence/leader-per-partition scheme)
*before* going multi-node — this is exactly why §17 calls out a single ordering authority per
channel as the thing that must not be naively parallelized.

**2. Walk me through what happens if the process crashes mid-`sendMessage` — after
`messageStore.append()` returns but before the delivery strategy runs.**

The message is durably (within this process's lifetime) in the store with its sequence number
consumed — a reader pulling history later sees it normally. What's lost is *real-time
delivery*: no `MessageSentEvent`/`ChannelUpdatedEvent` was published, so any client relying
purely on push notification never finds out about this message until it next pulls history
(e.g. reopens the channel). This is a genuine gap in the current design: delivery is not
"at-least-once" with respect to process crashes — it's "best-effort, in the same call". A
production fix is to make delivery driven from the durable log itself (a change-data-capture
style consumer tailing `messages` and publishing events), so delivery is derived from
committed state, not from "did the request thread get far enough" — crash-recovery delivery is
then just "resume the CDC consumer from its last acknowledged offset."

**3. Two threads call `message.edit(...)` on the same `Message` at literally the same
instant. Prove to me there's no lost update.**

`edit` is a `synchronized` instance method — the JVM guarantees mutual exclusion on the
monitor for `this` specific `Message` object. Thread A and Thread B cannot both be inside the
method body simultaneously; one fully completes (reads `this.content`, pushes it into
`editHistory`, overwrites `content`, sets `state = EDITED`) before the other even starts its
read of `this.content`. So the second edit's audit entry captures the *first* edit's result as
its "previous content" — there is no interleaving where one edit's write is silently
overwritten without being recorded, because the read-modify-write triplet (`content`,
`mentionedUserIds`, `state`) never happens concurrently for the same message.

**4. Now make it harder: Thread A calls `edit` and Thread B calls `delete` on the same message
at the same instant. What's the actual outcome, and is it deterministic?**

Non-deterministic in *which one* wins the race for the lock (the JVM doesn't guarantee fairness
by default), but deterministic in *outcome-shape*: whichever acquires the monitor first runs to
completion before the other starts. If `delete` wins: `state` becomes `DELETED`, then `edit`
acquires the lock, checks `state == DELETED`, and throws `InvalidMessageStateException` —
clean rejection, no partial state. If `edit` wins: content/mentions update, `state` becomes
`EDITED`, then `delete` proceeds normally and deletes the edited version. Either way, there is
no observable state where content changed *after* `deletedAt` was set — which is the actual
invariant that matters (§5.5), not "who won."

**5. You picked `synchronized` for `Message`. Under what load profile would that become a
measurable bottleneck, and what would you change?**

`synchronized` on a single `Message` instance is only a bottleneck if *that specific message*
is being edited/deleted at high concurrent frequency — which essentially never happens in a
chat product (one author, occasional edits). It would never become a global bottleneck because
different messages use different locks. The one scenario where it matters: a test harness or
bot abusively hammering `edit` on one message id in a tight loop from many threads — purely
synthetic, not a realistic production load. If it somehow mattered, the fix would be a
version-number CAS loop (optimistic concurrency) instead of pessimistic locking, trading a bit
of complexity for lock-free throughput under contention — explicitly not done here because the
contention doesn't exist in practice (see README §11's optimistic-vs-pessimistic discussion).

**6. A new requirement lands: "workspace admins can delete any message for moderation." Can
you add this without touching `ChatServiceImpl`'s calling code or the `Message` class's field
layout?**

Mostly yes, with one necessary touch. `Message.delete(UserId requesterId)` currently hard-codes
`senderId.equals(requesterId)`. The clean extension is to replace that single check with an
injected `DeleteAuthorizationPolicy` (a one-method functional interface:
`boolean canDelete(Message message, UserId requesterId)`), defaulting to
"sender-only," with a second implementation consulting workspace admin membership. This does
require editing `Message` (to accept/consult the policy) but *not* `ChatServiceImpl`'s calling
code — `deleteMessage(channelId, messageId, requesterId)`'s signature and body stay identical;
only what `message.delete(...)` internally consults changes. This is a reasonable,
bounded blast radius for a real new requirement — not a sign the original design was wrong,
just a sign that "who can act on a message" was deliberately left as a narrow, swappable seam.

**7. What's the actual Big-O of `getUnreadCount`, and does it change based on which fan-out
strategy a channel uses?**

O(1) — two map lookups (`lastReadSequence` nested map, `sequenceGenerators` map) plus a
subtraction. It is **identical regardless of delivery strategy**, which is a deliberate and
important property: unread count is derived purely from the channel's monotonic sequence
counter and the user's last-read position, neither of which the delivery strategy touches.
This is why fan-out-on-read doesn't need to "catch up" unread counts lazily via a scan — the
arithmetic was never doing a scan in the first place. If you'd built unread counts as "a
counter incremented per member on each send" (fan-out-on-write style), you'd have been forced
to also maintain it for fan-out-on-read channels via some other, more expensive mechanism —
the chosen design sidesteps that entirely.

**8. 10 million users, one workspace, one `#general` channel all 10 million are members of.
Where does this design fall over first, concretely — not "it doesn't scale," name the actual
first failure.**

First concrete failure: `Channel.memberSnapshot()` — called by `FanOutOnWriteStrategy` (if
mis-configured to apply at this size) and also just by `channel.memberCount()`/membership
checks — would materialize a 10-million-entry `Set.copyOf` on *every single message send* if
the delivery policy's threshold were set above 10 million (a misconfiguration, admittedly, but
a realistic one if the threshold is a static constant rather than data-driven). Even with the
policy correctly routing to `FanOutOnReadStrategy` (O(1) write), the *membership set itself* —
a `ConcurrentHashMap.newKeySet()` with 10 million entries living in one `Channel` object in one
JVM's heap — is the real first failure: it does not fit a reasonable heap budget for a single
process once you have many such channels, and even one such channel is a meaningful chunk of
memory and GC pressure. The architectural fix is that membership for very large channels
cannot be an in-memory `Set` on a single object at all — it needs to be a paginated,
externally-stored collection (a `channel_members` table with an index, not something you ever
materialize in full) — a structural change, not a tuning change.

**9. If I ask you to add message threading (replies to a specific message) tomorrow, which
part of your design resists that change the most?**

`MessageStore`'s pagination is built entirely around a *flat*, single-sequence-per-channel
ordering (`ConcurrentSkipListMap<Long, Message>` keyed by one channel-wide counter). Threading
needs a second ordering dimension (within a thread) while *also* preserving the top-level
channel timeline for messages that start threads. The resistant part isn't `Message` itself
(you can add an optional `parentMessageId` field cheaply) — it's that `getPage`'s keyset
pagination assumes "the channel's message list" is the only view you ever need; a thread view
needs an index from `parentMessageId → child sequence numbers`, which is a new store structure
alongside, not instead of, the existing one. This is a real, non-trivial addition — correctly
flagged, not hand-waved as "just add a field."

**10. Your `TokenBucketRateLimiter` keys buckets by `UserId` as a `String`. What happens under
10,000 distinct noisy senders being rate-limited simultaneously, and is the `ConcurrentHashMap`
of buckets itself a problem?**

Each sender gets their own `Bucket` object; `ConcurrentHashMap.computeIfAbsent` gives O(1)
amortized lookup/creation with fine-grained internal locking, so 10,000 distinct keys
contending on *different* buckets do not serialize against each other — this scales linearly
with sender count, not quadratically or worse. The actual risk is unbounded memory growth: a
bucket is created the first time a user sends anything and is **never evicted**, so a workspace
with millions of historical users (most inactive) accumulates millions of stale `Bucket`
objects. This is a real, flagged gap (not addressed in this LLD) — a production fix is a
bounded/TTL-evicting cache (e.g. Caffeine with `expireAfterAccess`) instead of a plain
`ConcurrentHashMap`, since a long-idle bucket's state is cheap to recompute from scratch anyway
(it would be full/at-capacity after any idle period exceeding one refill cycle).

**11. I claim your `ChannelUpdatedEvent` fan-out-on-read path doesn't actually scale, because
publishing to `"channel:<id>"` still means *someone* iterates a `CopyOnWriteArrayList` of every
subscriber to that topic — and if 10,000 clients are watching a channel, that's still
O(subscribers). Defend or concede.**

Partially concede, with an important distinction. It's true that `InMemoryEventBus.publish`
iterates every subscriber of a topic — so if every one of 10,000 viewers maintained their own
subscription to the exact channel topic, the iteration cost is still O(viewers), not actually
O(1) end-to-end. What *is* O(1) is the **work done by the strategy itself** (one `publish` call
regardless of channel size) — the cost that was O(members) under fan-out-on-write
(serializing+addressing N individual messages) has moved to "O(subscribers) generic list
iteration," which is a real win if most of the channel's 10,000 members are *not* currently
subscribed (not actively connected/viewing) — the realistic case for a huge broadcast channel.
If literally all members are simultaneously connected and subscribed, fan-out-on-read's
advantage shrinks to "one shared event object instead of N copies," which is still a real
(lower-constant-factor) win but not the asymptotic win the model implies at full concurrency —
a nuance worth stating plainly rather than oversold.

**12. What breaks a test you'd actually have to write, if two users mark the same message as
read at the exact same nanosecond with different sequence values?**

Nothing breaks — this is exactly what `AtomicLong.updateAndGet(prev -> Math.max(prev, newValue))`
is for: it's a CAS retry loop, so even if both threads read the same "current" value
simultaneously, only one `compareAndSet` underneath succeeds per attempt and the other retries
against the updated value; the net result converges to the maximum offered value with no lost
update. The test that actually exercises this (`UnreadTrackerTest.concurrentMarkReadCallsConvergeToTheMaximumReportedPosition`)
fires 20 threads with values 1..20 and asserts the result is exactly 20 — if the
implementation used a plain (non-atomic) read-compare-write instead of `updateAndGet`, this
test would be flaky (occasionally converge to something less than 20) rather than
deterministically failing — a good illustration of why "mostly passes" concurrency tests are
dangerous and why the mechanism matters more than the test alone.

**13. Your DM channel lookup index (`InMemoryChannelRepository.directMessageIndex`) is keyed by
a sorted, joined string of two user ids. What's wrong with that as a long-term key design?**

It conflates "value equality" with "string formatting correctness" — the key is only correct
as long as `UserId.value()` never contains the delimiter (`":"`) used to join them, and as long
as sorting by `String::compareTo` is stable and collision-free across all possible id values
(true for UUIDs, not guaranteed if ids were ever changed to something with different
comparison semantics, e.g. numeric ids compared lexicographically as strings: `"10" < "9"`
non-numerically). A more robust key would be a dedicated small record/value object
(`DirectMessageKey(Set<UserId> members)` with proper `equals`/`hashCode` derived from an
order-independent set) rather than a hand-formatted string — the current approach works for
this LLD's id scheme but is a latent footgun if ids ever change shape.

**14. If I told you "ship something in two weeks," what's the first thing from this design you
would cut, and what would you keep no matter what?**

Cut first: the adaptive `ChannelDeliveryPolicy`/`FanOutOnReadStrategy` — ship
fan-out-on-write only and hard-code it, explicitly documenting "revisit once we see channels
above N members in practice" (you don't know the real threshold without production data
anyway, so guessing one early is low-value work). Also cut: `PresenceService` as a fully
separate subsystem — presence could piggyback on "last seen message delivery timestamp" as a
rough proxy initially. Never cut: the per-channel sequence-number ordering guarantee and the
message state machine (edit/delete correctness) — these are the parts that are expensive to
retrofit later (an ordering bug discovered after customers have months of history is a data
migration, not a code change) versus the parts that are comparatively cheap to extend later
(delivery strategy, presence) without touching stored data.

**15. You said `sendMessage` isn't idempotent. Give me the actual mechanism you'd add, not just
"idempotency key" as a buzzword — what table, what check, what race does your mechanism itself
have to handle?**

Add a client-generated UUID, say `clientMessageId`, passed alongside `content`. Server-side: a
unique index on `(channelId, senderId, clientMessageId)` in a `message_dedup` table (or as an
additional unique column on `messages` itself). On `sendMessage`, attempt an insert into that
unique key *before* allocating a sequence number; if the insert fails with a unique-constraint
violation, look up the already-created message by that key and return it instead of creating a
new one (treating the retry as "already handled"). The race this mechanism itself has to
handle: two concurrent retries of the *same* logical send racing each other to insert the
dedup key — resolved by the database's unique constraint rejecting the second writer
atomically (the same "let the DB be the arbiter" pattern used for `(channel_id, sequence)`
uniqueness in §12) — critically, this cannot be done purely in application-level
`ConcurrentHashMap` logic across multiple nodes, which is exactly why this fix is listed under
persistence/production (§17), not implementable correctly in the current single-process
in-memory version without a shared, atomic "insert-if-absent" authority.

**16. Your `MentionParser` regex is `@([A-Za-z0-9._-]+)`. What's a message content string that
breaks a user's reasonable expectation of "who did I mention," and is that a bug or an accepted
limitation?**

`"contact me at jane@example.com"` parses `example.com` as a mentioned "username" — if a user
named `example.com` existed (unlikely, but usernames in this LLD allow dots), that user gets
spuriously notified; more commonly it's silently a no-op (no such username), so the *visible*
bug is narrower: a message like `"ping @bob, re: issue@tracker see ticket"` could double-count
or misinterpret `tracker` as a handle. This is flagged in the test suite itself
(`emailLikeTextIsNotMisparsedAsAnUnrelatedMention` documents the exact behavior) as an accepted
limitation, not silently swept under the rug — a production system would either require a
mention delimiter the UI inserts unambiguously (e.g. the client sends structured
`{type: "mention", userId: "..."}` spans instead of parsing free text) rather than relying on
regex-over-plain-text at all.

**17. Why is `EventBus.publish` synchronous, and what observable behavior would change if you
made it asynchronous (dispatch to a thread pool) without changing anything else?**

It's synchronous for determinism in this LLD and in tests — `sendMessage` returning means
"delivery to all currently-subscribed listeners has already happened," which is exactly what
the test suite relies on (e.g. asserting `notificationsFor(bob)` immediately after
`sendMessage` without needing to wait/poll). Making it asynchronous would decouple "message
persisted" from "message delivered," meaning a caller could call `sendMessage` and then
immediately call `getUnreadCount`/`notificationsFor` and observe *stale* results (a genuine
read-after-write consistency regression for anything depending on side effects of delivery,
though not for `getHistory`/`getUnreadCount`, which read from `MessageStore`/`UnreadTracker`
directly, not through the event bus). It would also mean a slow or hung listener could no
longer block the sender's thread — a real win — at the cost of needing explicit
synchronization (e.g. a `CompletableFuture` or test-side `CountDownLatch`) anywhere a test or
caller needs to observe "delivery has happened" deterministically.

**18. Is there a version of this design where `Channel` and `Workspace` should have been the
*same* concept (e.g. workspace-as-giant-channel), and why did you reject it?**

Rejected because they have different invariants and different cardinalities of relationship:
a `Workspace` has exactly one of itself per set of channels (1-to-many, parent-like), while
`Channel`s of different types have fundamentally different membership rules (self-service
join for public, invite-only for private, fixed-two for DM) that don't apply to workspace
membership at all (workspace membership is just "added by an admin/accepted an invite," no
type variation). Collapsing them would force `Workspace` to carry channel-type-specific
validation logic it has no business owning, and would make "is this user in the workspace but
not yet in any channel" (a real, distinct state — e.g. a newly invited employee) inexpressible
without a workaround. The current split — `Workspace` as the membership *ceiling*, `Channel` as
a membership *subset* with type-specific rules — models that distinction directly.

**19. Your repositories return `Optional<T>` and the service layer immediately calls
`.orElseThrow(...)`. Isn't that just a verbose null check — why not just return `null` and
check for it, or throw directly from the repository?**

Two reasons, both deliberate. First, `Optional` at the repository boundary makes "this might
not exist" part of the *type signature*, not a comment or convention — a caller cannot
accidentally forget a null-check and get a `NullPointerException` three stack frames away from
the actual missing-entity logic; the compiler nudges toward handling it at the call site.
Second, throwing directly from the repository would hard-code "not found is always an error"
into the data-access layer, but that's not always true — e.g. `findDirectMessage` returning
"empty" in `getOrCreateDirectMessage` is a completely normal, expected outcome (first contact
between two users), not an error at all. Keeping repositories return-`Optional` and letting
each *call site* decide whether absence is an error (`requireChannel`'s `orElseThrow`) or a
normal branch (`getOrCreateDirectMessage`'s `orElseGet`) is more honest about where the
"missing is exceptional" judgment actually belongs.

**20. If a bug caused `InMemoryMessageStore.append` to allocate a sequence number but then
throw before the `put` completed, what state would the channel be left in, and is that a
correctness problem?**

The `AtomicLong` would have been incremented (that step cannot be "undone" — it already
happened), but the corresponding `Message` would never make it into the `ConcurrentSkipListMap`
or `globalIndex`. The channel would be left with a **gap** in its visible sequence numbers
(e.g. messages 1, 2, 4, 5 with 3 missing) — violating the "dense, no gaps" invariant stated in
§5.6. This is a real correctness problem *if it could happen* — but examining the actual method,
the only code between `incrementAndGet()` and the two `put` calls is pure object construction
(`new Message(...)`), which only throws on the explicit validation in `Message`'s constructor
(blank content) — and that validation happens to run *before* `sequence` is even used
meaningfully (it's just stored). The realistic fix, if this were a concern with a more complex
constructor, would be to validate inputs *before* calling `incrementAndGet()`, guaranteeing the
counter is only advanced once success is otherwise certain — worth stating as a principle
("validate before you consume a scarce, non-reusable counter value") even though the current
code already happens to satisfy it.

**21. What's the first thing you'd ask a product manager before building the "adaptive"
threshold in `ChannelDeliveryPolicy` for real, instead of hard-coding 500?**

"What does our actual channel-size distribution look like today, and is it bimodal (mostly
tiny DMs/team channels, plus a handful of genuinely huge broadcast channels) or a smooth
spread?" — because the whole premise of a single static threshold only holds up if the
distribution is bimodal (which is realistic for a Slack-like product: team channels cluster in
the tens, broadcast channels are rare but huge). If it's a smooth spread, a single cutoff would
misclassify a meaningful number of mid-sized channels either way, and you'd want the policy to
weigh something richer than raw member count — e.g. active/connected member count, or recent
message rate — which is explicitly called out in README §14/§17 as the next refinement, not
pretended to be solved by one constant.

**22. Does your design guarantee "read-your-own-writes" — if Alice sends a message and
immediately calls `getHistory`, will she always see it?**

Yes, and this is worth being precise about *why*: `sendMessage` calls `messageStore.append(...)`
synchronously and returns only after the `put` into `ConcurrentSkipListMap` has completed —
there's no asynchronous buffering between "the method returned" and "the data is visible."
`getHistory`'s subsequent call to `getPage` reads from that same map, in the same process, with
no caching layer in between. The visibility guarantee comes from the happens-before
relationship established by the method call itself (not needing a separate memory barrier)
since both operations run (possibly on different threads, but strictly sequenced by Alice's own
two calls) against the same `ConcurrentSkipListMap`, which provides its own internal visibility
guarantees for prior writes to be visible to subsequent reads. This guarantee would need to be
re-earned explicitly in a distributed version (e.g. reading from a replica that hasn't caught
up to the write yet) — flagged as a real risk in §17, not assumed to carry over for free.
