# Implementation Guide — Slack / Workplace Chat System

A practical, time-boxed guide for actually *writing* this system live in an interview, as
opposed to the reference design in `README.md`. This document answers: "if the clock is
running, what do I type first, in what order, and what do I skip?"

---

## 1. Suggested Build Order (and why this order)

Build **bottom-up through the dependency graph**, not top-down from the API. If you start with
`ChatService` you'll be stubbing out six collaborators before anything compiles or is
demonstrable. Building bottom-up means every class you add is immediately testable/runnable in
isolation, and you always have a compiling, growing system — critical when being watched.

1. **Id value objects** (`UserId`, `WorkspaceId`, `ChannelId`, `MessageId`) — zero dependencies,
   five minutes, unblocks everything else.
2. **Core entities** (`User`, `Workspace`, `Channel` + `ChannelType`) — depend only on step 1.
3. **`Message` + `MessageState`** — the most important class to get right; do this carefully,
   don't rush it.
4. **`MessageStore` interface + `InMemoryMessageStore`** — depends on `Message`. This is where
   ordering/pagination lives; get the `AtomicLong` + sorted-map mechanism working and
   demonstrate it (print a few messages back in order) before moving on.
5. **Pagination types** (`PageRequest`, `MessagePage`, `Direction`) — small, mechanical, but
   needed before `MessageStore`'s `getHistory` signature makes sense. In practice build these
   *alongside* step 4, not strictly before it.
6. **Repositories** (`UserRepository`, `WorkspaceRepository`, `ChannelRepository`, in-memory
   impls) — simple `ConcurrentHashMap` wrappers; fast to write.
7. **Event bus** (`ChatEvent`, `EventListener`, `EventBus`, `InMemoryEventBus`) — the Observer
   simulation boundary. State out loud, while writing this, that this stands in for
   WebSockets/a broker in production.
8. **Delivery strategy** (`ChannelDeliveryStrategy`, the two implementations,
   `ChannelDeliveryPolicy`) — this is your flagship discussion point; don't leave it for the
   last five minutes.
9. **Presence, rate limiting, notifications, unread tracker** — these are independent of each
   other; build whichever the interviewer seems most interested in first if time is short.
10. **`ChatService` / `ChatServiceImpl`** — the facade, last, because it's just orchestration of
    everything already built. Writing this last also means you're not guessing at
    collaborator APIs you haven't written yet.
11. **Demo / quick manual test** — always leave 5 minutes to actually run something and show it
    works.

---

## 2. Minimal Compilable Core (first 10–15 minutes)

If asked to "just get something compiling and running" before going deeper, this is the
smallest useful vertical slice — enough to send one message and read it back, nothing else:

```java
// UserId, ChannelId, MessageId — minimal versions
public record UserId(String value) {
    public UserId { Objects.requireNonNull(value); }
    public static UserId generate() { return new UserId(UUID.randomUUID().toString()); }
}
// (ChannelId, MessageId identical shape)

public final class Message {
    private final MessageId id;
    private final ChannelId channelId;
    private final UserId senderId;
    private volatile String content;
    private final Instant createdAt;

    public Message(MessageId id, ChannelId channelId, UserId senderId, String content) {
        this.id = id; this.channelId = channelId; this.senderId = senderId;
        this.content = content; this.createdAt = Instant.now();
    }
    public MessageId id() { return id; }
    public String content() { return content; }
    // ... getters
}

public final class InMemoryMessageStore {
    private final Map<ChannelId, ConcurrentSkipListMap<Long, Message>> byChannel =
        new ConcurrentHashMap<>();
    private final Map<ChannelId, AtomicLong> sequences = new ConcurrentHashMap<>();

    public long append(ChannelId channelId, Message message) {
        long seq = sequences.computeIfAbsent(channelId, c -> new AtomicLong())
                             .incrementAndGet();
        byChannel.computeIfAbsent(channelId, c -> new ConcurrentSkipListMap<>())
                 .put(seq, message);
        return seq;
    }
    public List<Message> recent(ChannelId channelId, int n) {
        var map = byChannel.getOrDefault(channelId, new ConcurrentSkipListMap<>());
        return new ArrayList<>(map.descendingMap().values()).stream()
                 .limit(n).toList();
    }
}
```

Narrate while typing this: "I'm starting with the smallest thing that proves ordering and
storage work, before I add edit/delete, pagination cursors, or delivery — I'll layer those on
once this compiles." Then actually compile+run it with a 3-line `main` before moving on — this
gives you a safety net checkpoint.

---

## 3. Methods Most Worth Getting Exactly Right

These are the methods where interviewers probe hardest — know them well enough to write them
without hesitation:

- **`InMemoryMessageStore.append`** — the sequence-allocation-then-insert two-step, and *why*
  no additional lock is needed (each thread gets a unique key from the atomic before touching
  the map).
- **`Message.edit` / `Message.delete`** — the `synchronized` guard and the state-machine check
  (`if (state == DELETED) throw ...`), plus recording a `MessageEdit` audit entry.
- **`getHistory(channelId, PageRequest)`** — the cursor-based pagination logic (`tailMap`/
  `headMap` by sequence, trimmed to the requested limit, with a `hasMore` flag computed from
  whether the untrimmed view had more entries than the page size).
- **`UnreadTracker.unreadCount`** — the O(1) `latestSequence - lastRead` subtraction; be ready
  to explain why this is strategy-independent.
- **`ChannelDeliveryPolicy.strategyFor`** — the member-count threshold decision; know the
  number you picked and be ready to defend it as a placeholder, not research.
- **`TokenBucketRateLimiter.tryAcquire`** — the lazy-refill-on-access math
  (`elapsedNanos * refillRatePerNanos`), capped at bucket capacity.
- **`MentionParser.parseMentions`** — the regex and the resolver-function indirection
  (`Function<String, Optional<UserId>>`), and the known email-handle false-positive edge case.

---

## 4. Common Mistakes to Avoid

- **Forgetting to validate channel membership before allowing a send/read.** It's tempting to
  wire up `sendMessage` to just append to the store — always check `channel.isMember(senderId)`
  first, and say out loud that you're doing it, since "can a non-member post?" is a question
  that *will* come up if you don't address it.
- **Using a plain `HashMap`/`ArrayList` for the message store and then bolting on
  `synchronized` later.** Start with `ConcurrentHashMap`/`ConcurrentSkipListMap` from the first
  line — retrofitting thread-safety after the fact under time pressure is how subtle bugs slip
  in and it looks reactive rather than deliberate.
- **Making `markRead` require fetching the actual message at that sequence.** It shouldn't —
  the position is enough. Fetching the message object is unnecessary work and couples read-
  receipts to message existence in a way that breaks once messages are deleted (see Follow-Up
  Q34).
- **Allowing `PageRequest` to accept an unbounded limit.** Always cap it — this is a natural,
  realistic thing to enforce and gives you a concrete, defensible invariant to discuss instead
  of a vague "we'd add limits in production" hand-wave.
- **Forgetting that `edit`/`delete` need an authorization check (sender-only), not just a state
  check.** It's easy to remember "can't edit a deleted message" and forget "can't edit *someone
  else's* message" — both belong in the same guard clause.
- **Treating fan-out-on-write and fan-out-on-read as only about *storage cost* instead of
  *delivery* cost.** The common mistake is to say "fan-out-on-write copies the message into
  every member's inbox" (a storage framing) when the actual LLD-relevant distinction here is
  about *how many event-bus publishes happen per send* (a delivery-cost framing) — be precise
  about which cost you're discussing.
- **Over-synchronizing.** Wrapping entire repository methods or the whole `ChatServiceImpl` in
  one big lock "to be safe" is a red flag to an interviewer — it suggests you don't know where
  contention actually matters. Be precise about *which* operations need coordination
  (sequence allocation, message state transitions, rate-limiter bucket updates) and leave
  everything else lock-free.

---

## 5. Edge Cases Worth Mentioning Even If Not Fully Implemented

- Sending a message to a channel the sender isn't a member of.
- Editing/deleting a message that's already deleted.
- Editing/deleting someone else's message.
- Marking read with a sequence number ahead of the channel's actual latest sequence (should be
  rejected/clamped — mention it even if your implementation clamps rather than rejects).
- A direct message channel between two users that already exists — do you create a duplicate or
  return the existing one? (This design indexes DM channels by a canonical sorted-pair key
  specifically to make this idempotent.)
- Mentioning a username that doesn't exist / doesn't resolve to a real user.
- Rate limit hit by a user who is also an admin posting an urgent announcement (is rate
  limiting role-aware? — acknowledge it isn't here, and that it's a reasonable follow-up to
  discuss, not implement).
- Pagination cursor pointing past the end of history, or before the beginning.
- A channel with zero members (can messages even be sent? — reasonable to say no, membership
  check already covers it since the sender themselves must be a member).

---

## 6. How to Narrate While Coding

Say the *why* before the *what*, every time you start a new class:

- "I'm making `MessageId` its own type instead of reusing `String`, so the compiler stops me
  from accidentally passing a `ChannelId` where a `MessageId` is expected."
- "I'm putting the sequence counter and the storage map both inside `MessageStore`, keyed by
  channel, so each channel gets independent ordering with no cross-channel contention."
- "I'm going to make `edit`/`delete` synchronized methods on `Message` itself, so the invariant
  'can't edit after delete' is enforced in exactly one place no matter who calls it."
- "I'm introducing a `ChannelDeliveryStrategy` interface right now, before I write a second
  implementation, because this is the fan-out trade-off I know we'll want to discuss — let me
  set up the seam early rather than retrofit it."
- When you hit a trade-off you're consciously not fully solving (e.g. event bus dispatch being
  synchronous), say so explicitly: "I know this makes delivery synchronous with send, which
  wouldn't fly in production — I'm flagging that now and we can come back to it."

Narrating trade-offs *as you make them* — not after being asked — is one of the highest-signal
behaviors in a staff-level interview.

---

## 7. What Can Be Safely Omitted Under Time Pressure

Explicitly tell the interviewer what you're deferring and why, rather than silently skipping it:

- **Workspace-level roles/permissions (admin vs member)** — mention it affects who can create
  public channels, delete others' messages, etc., but skip implementing a permissions matrix.
- **Full notification delivery (push/email)** — a `NotificationService` that records
  notifications in-memory is enough; don't build actual push/email integration.
- **Search over message history** — explicitly out of scope per the design doc; say so if asked.
- **Threaded replies** — mention it as a natural extension (`Message.parentMessageId`) without
  implementing it.
- **Typing indicators** — same category as presence but higher-frequency/more ephemeral; worth
  a one-line mention, not worth building.
- **Rate-limiter bucket eviction** — acknowledge the unbounded-memory-growth limitation
  verbally; don't spend time building an eviction scheme live.
- **Persistence/durability** — entirely in-memory is fine and expected; say clearly that a real
  system needs a durable store and briefly what that would look like (§12 of the README).

---

## 8. A Simplified 20–30 Minute Version

If given a hard 20–30 minute budget (a shorter round, or a warm-up before a deeper design
discussion), implement **only**:

1. `UserId`, `ChannelId`, `MessageId` (collapse `WorkspaceId` entirely — assume a single
   workspace for this scope).
2. `Channel` with just `id`, `name`, `Set<UserId> members`.
3. `Message` with `id`, `channelId`, `senderId`, `content`, `createdAt` — **skip edit/delete
   entirely**, messages are immutable.
4. `InMemoryMessageStore` with `append` and `recent(channelId, n)` (last N messages — skip
   full cursor-based pagination, just return the tail).
5. A single `ChatService` facade method: `sendMessage(channelId, senderId, content)` that
   checks membership, appends to the store, and returns the stored message.
6. A `main` method demonstrating: create a channel with 2 members, send 3 messages, print them
   back in order.

Explicitly state out loud what you're deliberately leaving out and why: "I'm skipping
pagination, edit/delete, presence, rate limiting, and the event bus entirely for this version —
given the time, I want to prove out the core send-and-read-in-order path first, and I can layer
the rest on if we have time, or just talk through how each would plug in." This demonstrates
prioritization under constraint, which is exactly what a 20–30 minute slot is testing.
