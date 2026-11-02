# Interview Playbook — LFU Cache

## 1. How to open the discussion

Start by restating the problem in your own words and immediately naming the
two things that make LFU harder than LRU: "I want to build a fixed-capacity
cache that evicts the least-frequently-accessed entry when full, with ties
broken by least-recently-used, and I need both `get` and `put` to be O(1).
Before I design, let me ask a few questions, because LFU has some easy-to-get-
wrong edge cases around what counts as an access." This signals upfront that
you know the problem has traps (tie-breaking, update semantics) without
needing to be walked into them.

## 2. First 5 clarifying questions to ask

1. "Does updating the value of an existing key count as an access, bumping
   its frequency?" — this is the single most consequential question; get it
   wrong and your whole eviction trace in a dry-run will look broken to the
   interviewer.
2. "On a tie — same frequency, multiple candidates — what breaks the tie?"
   — confirm it's LRU-among-equal-frequency (the standard spec) rather than
   something else like insertion order across the whole cache.
3. "What should `get` return on a miss — null, a sentinel like -1, or should
   I design my own return type?" — settles your API signature before you
   write a line of code.
4. "Is this single-threaded, or do you want me to also address concurrent
   access?" — tells you whether to budget time for section 11-equivalent
   discussion.
5. "Any constraints on key/value types, or capacity edge cases like 0 I
   should handle explicitly?" — catches the capacity-0/1 boundary early
   instead of being surprised by it mid-coding.

## 3. Requirements to establish

Before touching a whiteboard: fixed capacity set once; O(1) `get`/`put`;
LFU with LRU tie-break; update counts as access; miss returns an explicit
"absent" signal, not an exception. State these back to the interviewer as a
checklist — it shows you've converted the conversation into a concrete spec,
which is exactly what section 2 of `README.md` does in writing.

## 4. How to identify core entities

Walk it top-down: "I need O(1) lookup by key — that's a HashMap. I need O(1)
eviction of the minimum-frequency entry — a HashMap alone can't give me an
ordered-by-frequency view in O(1), so I need a second structure bucketing
keys by frequency. Within a frequency bucket, I need O(1) 'move to most
recently used' and O(1) 'remove the least recently used' — that's a doubly
linked list, because arrays/ArrayLists can't remove an arbitrary element in
O(1)." This derivation, said out loud, *is* the entity identification: `Node`
(the thing that needs frequency + list position), `FrequencyList` (the
per-frequency bucket), and the top-level `LFUCache` orchestrating both maps
plus the `minFrequency` tracker.

## 5. How to explain class relationships

"`LFUCache` owns two maps: key → node, and frequency → a doubly linked list
of nodes at that frequency. A `Node` lives in exactly one `FrequencyList` at
a time — when its frequency bumps, it's unlinked from its current list and
relinked into the next one, using its own prev/next pointers, so there's no
searching involved. `LFUCache` also tracks the single smallest active
frequency so it always knows where to evict from without scanning." Then
mention the orthogonal pieces only if time/interest allows: "If you want
thread-safety or metrics, I'd add those as wrappers around this core class
rather than modifying it — composition, not inheritance, since those are
independent concerns."

## 6. How to draw the architecture

Draw bottom-up, in this order, narrating as you go:
1. A box for `Node` with fields `key, value, frequency, prev, next`.
2. A box for `FrequencyList` with `head`/`tail` sentinels and the three
   operations (`addToHead`, `remove`, `removeTail`).
3. A box for `LFUCache` with the two maps and `minFrequency`, arrows into
   the `FrequencyList` boxes.
4. If time allows, a thin wrapper box for `ConcurrentLFUCache` drawn
   *around* `LFUCache` with a lock icon, to visually reinforce "decorator,
   not modification."

Use `diagrams/class-diagram.md` and `diagrams/component-diagram.md` in this
repo as the target shape if you want to rehearse from something concrete.

## 7. Which interfaces to create first

Write `Cache<K,V>` first — `get`, `put`, `size`, `capacity` — even before any
implementation. This gives you (and the interviewer) a stable contract to
talk about before committing to internals, and it's two minutes of typing
that pays for itself if the interviewer later asks "could you swap in a
different policy?"

## 8. Which classes to implement first

Order: `Node` (private nested class) → `FrequencyList` (private nested
class, get `addToHead`/`remove`/`removeTail` right with the sentinel trick)
→ `LFUCache` skeleton with fields and constructor → `get` → `put` (update
path first, since it reuses `touch`, then insert/eviction path). Resist the
urge to write `ConcurrentLFUCache` or stats before the core algorithm works
and is demonstrated with a quick manual trace or a couple of asserts.

## 9. Which design patterns to introduce and when

Only mention patterns when they're earning their keep, not as vocabulary:
after the core class works, say "I'd add thread-safety and metrics as
decorators around `Cache<K,V>` rather than subclassing, since `LFUCache`'s
correctness depends on nobody bypassing its bucket bookkeeping — that's the
Decorator pattern, and it's genuinely the right fit here, not pattern-
matching for its own sake." If asked "would you use Strategy for the
eviction policy?", have the rejection ready (see `README.md` section 7): the
O(1) guarantee is inseparable from the concrete bucketed-DLL structure, so a
generic `EvictionPolicy` abstraction would either leak the structure through
the interface or not capture where LFU's actual complexity lives.

## 10. How to discuss concurrency

Even if asked for a single-threaded design, volunteer one sentence:
"This implementation isn't thread-safe as written — multiple threads
calling `get`/`put` concurrently could corrupt the linked lists or
`minFrequency`. I'd wrap it in a class that holds a single lock around each
public method." If pushed further ("why not finer-grained locking?"), give
the real reason: a single logical operation here can touch two frequency
buckets and the global `minFrequency` counter, which are shared across keys
that would otherwise hash to different lock stripes — so striping by key
doesn't isolate operations the way it would for, say, a plain
`ConcurrentHashMap`-style cache. If pushed to lock-free: "the multi-location
nature of a single bump (unlink, relink, maybe delete a bucket, maybe bump
the global minimum) doesn't fit a single CAS; you'd need something like a
multi-word transactional update, which is real but disproportionate given
the critical section is already O(1) work."

## 11. How to discuss edge cases

Rehearse these out loud before the interview, don't discover them live:
capacity 0 (legal, no-op cache — decide and state this explicitly rather
than letting the interviewer catch you not having considered it); capacity
1 (every new key evicts the current occupant, regardless of its frequency,
because it's also always the minimum); updating an existing key (counts as
an access — "I bump its frequency the same way a `get` would, since from
the cache's perspective a write is also a use"); tie at the same frequency
(LRU within that frequency — walk the interviewer through exactly which
node gets evicted in a 2-key tie scenario).

## 12. How to handle interviewer interruptions

If interrupted mid-explanation with "why not a priority queue?", answer
directly and briefly, then return to where you were: "A heap gives you
O(log n) per operation because of the sift-up/down on every frequency
change, and it doesn't give you O(1) removal of an arbitrary non-root
element without an auxiliary index — this bucketed approach avoids both
problems by maintaining the minimum incrementally instead of keeping a full
order." Don't get defensive; a probing "why not X" is usually the
interviewer checking you understand the trade-off, not a sign you're wrong.

## 13. How to prioritise if time is running out

Priority order if you must cut scope:
1. Correct `get`/`put` with correct eviction (even if you have to describe
   the LRU tie-break in words rather than fully coding `FrequencyList`'s
   sentinel mechanics) — this is the one thing that must work.
2. The `minFrequency` tracking logic, even informally — this is what
   separates "I understand the O(1) trick" from "I built something that
   merely resembles LFU."
3. One clearly-stated concurrency sentence (see section 10) — cheap to say,
   signals staff-level awareness even unimplemented.
4. Skip: `EvictionListener`, `StatsRecordingCache`, `CacheFactory` — these
   are real but genuinely secondary; say "I'd add an eviction hook as a
   one-interface extension point if there's time" and move on.

## 14. Three full walkthroughs with realistic timelines

### 30-minute walkthrough
- 0–5 min: clarify requirements (sections 2–3 above), state the O(1) goal.
- 5–10 min: derive entities out loud (section 4), sketch `Node`/`FrequencyList`/
  `LFUCache` boxes.
- 10–22 min: code `Node`, `FrequencyList`, `LFUCache.get`/`put`/`touch`/
  `evictLeastFrequentlyUsed` directly (skip `EvictionListener` entirely).
- 22–27 min: manually trace 4–5 operations against the interviewer,
  including one eviction and one tie-break, to prove correctness without
  writing formal tests.
- 27–30 min: one sentence each on concurrency and complexity proof; ask if
  they want either expanded.

### 45-minute walkthrough
- 0–5 min: clarify + requirements.
- 5–12 min: entity derivation + diagram.
- 12–30 min: full core implementation (`Cache` interface, `Node`,
  `FrequencyList`, `LFUCache` complete with `EvictionListener` hook since
  there's room).
- 30–36 min: trace through get/put/eviction/tie-break scenarios; write 2–3
  quick unit-test-shaped asserts if the interviewer likes to see tests.
  36–42 min: concurrency discussion with the "why not striping/lock-free"
  reasoning (section 10), propose `ConcurrentLFUCache` as a decorator
  without necessarily coding all of it.
- 42–45 min: trade-offs recap (priority queue/TreeMap alternatives and why
  rejected) and production-evolution mention (metrics, write-back).

### 60-minute walkthrough
- 0–8 min: clarify + requirements + explicit invariants list (section 5
  equivalent) stated out loud.
- 8–15 min: diagrams (component + class + one sequence diagram for `put`
  with eviction).
- 15–35 min: full implementation including `EvictionListener` and a visible,
  explicit complexity-proof narration for `touch` (section 10 of
  `README.md`'s rigor, spoken rather than written).
- 35–45 min: code `ConcurrentLFUCache` for real, including the
  lock/try/finally shape, and justify the single-lock choice with the
  cross-bucket argument.
- 45–52 min: code or sketch `StatsRecordingCache` if asked "how would you
  know if this cache is actually helping," explaining the Decorator
  composition order.
- 52–60 min: staff-level stress questions — sharding for scale, what
  changes for 10M entries, what you'd simplify for a 2-week ship (see
  `STAFF_LEVEL_DEEP_DIVE.md` for ready answers).

## 15. What I would say to the interviewer

- "Before I write code, let me make sure I've got the tie-break rule right,
  because that's the part of LFU people usually get subtly wrong."
- "I'm using two maps and a doubly linked list per frequency bucket — the
  key insight is that I never search for anything; every move is a direct
  pointer operation because each node already knows its neighbors."
- "I track the minimum frequency as a single integer instead of scanning
  for it, and I can prove it only ever needs to increase by exactly one at
  a time, never jump — that's what keeps eviction O(1)."
- "This version isn't thread-safe yet — want me to talk through how I'd
  make it safe, or keep going on the core logic first?"
- "I thought about a priority queue here, but that gets you O(log n), not
  O(1), and it doesn't cleanly support bumping an arbitrary element's
  priority without extra bookkeeping — so I went with the bucketed
  approach instead."
- "If I had more time I'd add a small hook so evictions can trigger a
  write-back or a metric, but I didn't want to build that until the core
  eviction logic was solid."
- "For a single lock versus something fancier like per-key locking: the
  problem is that one access can touch two frequency buckets shared across
  many keys, so sharding by key doesn't actually isolate the work the way
  it would in a simpler cache."
