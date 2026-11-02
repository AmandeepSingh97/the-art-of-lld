# Interview Playbook — TTL-based Cache Library

A speakable, time-boxed playbook for running this problem live in a 30/45/60-minute
Staff-level round.

---

## 1. How to open the discussion

Don't start coding. Start by restating the problem in your own words and proposing the
scope you intend to cover, so the interviewer can correct you before you've invested
time:

> "So we're building a generic in-memory cache library — key-value, with per-entry TTL,
> a pluggable eviction policy for when it's full, and it needs to be safe under
> concurrent access. I'm going to treat this as a single-process, in-memory library —
> not something that needs to coordinate across machines — unless you tell me otherwise.
> Let me ask a few questions first, then I'll sketch the API, then get into the
> concurrency model, since that's usually the meatiest part of this one."

## 2. First 5 clarifying questions to ask

1. Is this single-process/in-memory, or does it need cross-process coordination?
2. Per-entry TTL, or one TTL for the whole cache?
3. Does an expired entry need to vanish instantly, or is "eventually, via a background
   sweep" acceptable alongside expiry-on-access?
4. Is the eviction algorithm fixed (e.g. "just do LRU"), or should it be pluggable?
5. Do we need a "get or load" convenience (`computeIfAbsent`) with protection against
   many threads loading the same missing key at once?

(Full reasoning for each is in `README.md` §3; this file is the condensed, speakable
version.)

## 3. Requirements to establish

Say out loud, and get a nod on, before designing:
- Functional: `get`/`put` with TTL, size-bounded with pluggable eviction, explicit
  invalidation, stats.
- Non-functional: thread-safe without a global lock, extensible eviction strategy,
  bounded memory.
- Explicitly out of scope unless told otherwise: distribution, persistence, dynamic
  reconfiguration.

## 4. How to identify core entities

Walk from nouns in the problem statement to classes, out loud:
- "Cache" → the main interface/class.
- "Entry with a TTL" → a value object pairing a value with an expiry time.
- "Eviction policy" → an interface, because it explicitly needs to be pluggable (LRU vs
  LFU) — this is the signal to reach for Strategy.
- "Something needs to be told when an entry is evicted" → a listener interface —
  Observer.
- "Configuring all of this" → a builder, because there are many independent optional
  knobs (size, TTL, policy, reaper interval, listeners).

## 5. How to explain class relationships

> "`TtlCache` *has a* `ConcurrentHashMap` for storage and *has an* `EvictionPolicy` it
> delegates eviction decisions to — composition, not inheritance, because the eviction
> algorithm is an orthogonal concern from storage and TTL. `EvictionPolicy` only ever
> sees keys, never values — it doesn't need to know what's stored, just which key to
> evict next. The builder constructs a `TtlCache` and then has no further relationship
> to it — classic Builder."

## 6. How to draw the architecture

Draw, in this order (roughly 3-4 minutes):
1. A box for `Cache<K,V>` (the interface) with `CacheBuilder` beside it.
2. A box for `TtlCache` implementing it, containing a `ConcurrentHashMap`.
3. A box for `EvictionPolicy` with two arrows to `LruEvictionPolicy`/`LfuEvictionPolicy`.
4. An arrow from `TtlCache` to a `CacheEvictionListener` box, labeled "notifies".
5. A small box off to the side for the background reaper thread, with an arrow into the
   map (it sweeps the same store).

Narrate while drawing: "the map is the single source of truth for what's live; the
policy is just bookkeeping about *order*, and it's deliberately decoupled from the map
itself — they're updated together but are two separate data structures."

## 7. Which interfaces to create first

1. `Cache<K, V>` — the public contract, first, because it forces you to decide the
   method signatures (`Optional<V> get`, `put` with/without TTL,
   `computeIfAbsent`) before writing any implementation.
2. `EvictionPolicy<K>` — second, because `TtlCache` needs to be written *against* this
   interface, not against a concrete LRU implementation, from the start.
3. `CacheEvictionListener<K, V>` — third, small and self-contained.

## 8. Which classes to implement first

1. `CacheEntry<V>` — trivial, but everything else depends on its shape (value + expiry).
2. `TtlCache` skeleton: constructor, `put`/`get` without eviction or listeners yet — get
   basic TTL semantics working and demonstrable first.
3. `LruEvictionPolicy` — simplest policy, using `LinkedHashMap`, wires up size eviction.
4. `CacheBuilder` — once the constructor's dependencies are stable, wrap them in a
   builder.
5. Listener plumbing, stats, `LfuEvictionPolicy`, background reaper — roughly in
   whatever order the interviewer's remaining time/interest points toward.

## 9. Which design patterns to introduce and when

- **Strategy** (`EvictionPolicy`): introduce the moment you mention "pluggable
  eviction" — don't wait until you're implementing LFU to retrofit it; design the
  interface before the first concrete policy.
- **Observer** (`CacheEvictionListener`): introduce when discussing what happens after
  an entry is removed — natural to mention right after sketching the three removal
  paths (lazy expiry, reaper, explicit invalidate).
- **Builder** (`CacheBuilder`): introduce once you've listed more than ~3 configuration
  knobs — say explicitly "this is enough optional configuration that I'd reach for a
  builder rather than a constructor with five optional parameters."

## 10. How to discuss concurrency

Don't just say "I'll use `ConcurrentHashMap`" and move on — that's necessary but not
sufficient, and a Staff interviewer will push on it. Walk through, explicitly:
1. Why `ConcurrentHashMap` (fine-grained locking, lock-free reads) over a
   `synchronized` `HashMap`.
2. The expiry race: "if I check `isExpired` and then separately call `remove`, another
   thread could have replaced that entry in between — so I use the conditional
   `remove(key, expectedEntry)` form, which only removes if the entry is still the
   exact one I saw." This is the single most important sentence in the whole interview
   — say it clearly.
3. The stampede scenario for `computeIfAbsent`: "I do the whole check-and-load inside
   `ConcurrentHashMap.compute`, which the JDK guarantees is atomic per key — so N
   threads racing for the same missing key only load once."
4. Where locks *do* exist (eviction-policy bookkeeping, capacity enforcement) and why
   they're scoped narrowly and never block `get()`.

## 11. How to discuss edge cases

Raise these proactively rather than waiting to be asked: empty cache `get`; `put`
replacing an existing key (is that a "replace" or an "eviction"? — this problem reports
it as `REPLACED`, distinct from `SIZE`); an entry with no TTL (should live forever, not
error); `maximumSize = 1` (degenerate but must still work); a listener that throws (must
not break the cache); `close()` called twice.

## 12. How to handle interviewer interruptions

If interrupted mid-explanation with "wait, what if two threads call `put` on the same
key at the same time?" — welcome it, don't treat it as a detour:

> "Good — that's actually simpler than the expiry race, because `ConcurrentHashMap.put`
> is already atomic per key; whichever `put` call's `store.put()` executes second just
> overwrites the first entirely, cleanly, no torn state. The harder race is the expiry
> one, which I was about to get to — want me to jump there now?"

If asked to simplify under time pressure mid-build, say what you're cutting and why,
out loud (see §13), rather than silently dropping scope.

## 13. How to prioritise if time is running out

Drop, in this order, narrating the trade-off as you do:
1. LFU policy (keep only LRU) — "I'll note LFU as a second `EvictionPolicy`
   implementation I'd add the same way, but won't code it given the time."
2. Background reaper (keep only lazy expiry) — "I'll rely on expiry-on-access only for
   now; the trade-off is a key that's never touched again after expiring holds memory
   a bit longer than ideal — I'd add a reaper next."
3. Stats/listeners — last to cut, since they're additive and don't risk correctness bugs
   if rushed, but still worth mentioning as "I'd wire up simple counters here given more
   time."
4. **Never cut**: the core `get`/`put`/TTL expiry correctness and the conditional-remove
   race fix — that's the crux of the problem; a solution without it is not actually
   correct, regardless of how much else got built.

## 14. Three full walkthroughs with realistic timelines

### 30-minute walkthrough
- 0-3 min: restate problem, ask 3-4 clarifying questions, state scope.
- 3-6 min: sketch `Cache<K,V>` interface + `EvictionPolicy<K>` interface.
- 6-16 min: implement `TtlCache` core (`get`/`put`/TTL expiry, lazy only) +
  `CacheEntry`.
- 16-22 min: implement `LruEvictionPolicy` and wire size eviction into `put`.
- 22-27 min: concurrency discussion — the expiry race and why `get`/`put` don't need a
  global lock.
- 27-30 min: mention (don't build) listeners, stats, reaper, LFU, builder as "what I'd
  add next."

### 45-minute walkthrough
- 0-5 min: clarifying questions + scope.
- 5-10 min: full interface sketch (`Cache`, `EvictionPolicy`, `CacheEvictionListener`).
- 10-25 min: `TtlCache` core: `get`/`put` with TTL, lazy expiry with the conditional-remove
  fix explained live, `LruEvictionPolicy`, capacity enforcement.
- 25-32 min: `computeIfAbsent` + the stampede-protection discussion (this is a strong
  differentiator at the 45-minute mark — most candidates don't get here).
- 32-38 min: listeners + `CacheBuilder`.
- 38-43 min: full concurrency walkthrough (§10 above) as a dedicated discussion, not
  woven in piecemeal.
- 43-45 min: trade-offs you'd reconsider with more time (background reaper, LFU, stats).

### 60-minute walkthrough
- 0-5 min: clarifying questions + scope + a quick architecture sketch (whiteboard/diagram
  tool) before any code.
- 5-12 min: full interface layer (`Cache`, `EvictionPolicy`, `CacheEvictionListener`,
  `Clock` for testability).
- 12-30 min: `TtlCache` core including both LRU and LFU, capacity enforcement, and the
  full set of named races (expiry, stampede, capacity) explained as you implement each.
- 30-38 min: background reaper thread + `CacheBuilder`.
- 38-45 min: `computeIfAbsent` stampede protection with a live mental trace of N threads
  racing on `compute`.
- 45-52 min: write 2-3 tests live (a basic TTL test with a fake clock, a concurrency test
  for the stampede case) — demonstrates you treat the concurrency claims as something to
  verify, not just assert.
- 52-58 min: Staff-level follow-ups (see `STAFF_LEVEL_DEEP_DIVE.md`) — scaling to very
  large sizes, what changes for a distributed version, operational monitoring.
- 58-60 min: summarize what you built, what you'd add next, and any trade-off you'd
  revisit given more information about real traffic patterns.

## 15. What I would say to the interviewer

- "I'm going to keep `EvictionPolicy` ignorant of values entirely — it only ever sees
  keys — because that keeps its interface narrow and means I can test an LRU or LFU
  policy completely standalone, without spinning up a whole cache."
- "The reason I'm not just wrapping everything in one big lock is that it would mean a
  `get()` on key A blocks behind a `put()` on key B, which defeats the entire point of a
  concurrent cache — so I'm leaning on `ConcurrentHashMap`'s per-key granularity and only
  adding my own lock where the map genuinely can't help me, like the eviction policy's
  ordering structure."
- "The trickiest race here isn't actually `get`/`put` on the same key — that's just
  `ConcurrentHashMap`'s job. It's a `get` racing a concurrent expiry removal: I fix that
  with a conditional remove, so I only ever delete the exact entry I already decided was
  expired, never whatever might have replaced it a moment later."
- "For `computeIfAbsent`, I'm deliberately doing the check-and-load inside `compute`'s
  remapping function rather than doing a `get` first and a separate `put` after, because
  that's exactly what turns 'many threads load the same key' into 'exactly one thread
  loads it.'"
- "I used `tryLock` instead of `lock` for capacity enforcement on purpose — it means a
  write never blocks behind someone else's cleanup pass; the cost is the cache can sit
  very slightly over its size cap for a moment under heavy concurrent writes, which I'm
  treating as acceptable since the cap is a soft memory target, not a hard constraint."
- "If this needed to span multiple processes, I'd treat this exact design as the L1
  layer in front of a shared store, rather than trying to redesign it — but that's a
  materially different problem from what's being asked here."
