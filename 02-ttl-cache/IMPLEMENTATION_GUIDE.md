# Implementation Guide — TTL-based Cache Library

A build-order guide: what to write first, a minimal compilable skeleton, important
methods, common mistakes, and what a realistic 20-30 minute simplified version looks
like.

---

## Suggested implementation sequence

1. **`Clock`** (interface + `systemClock()` static factory) — trivial, but write it
   first because `CacheEntry`/`TtlCache` both depend on it, and having it in place from
   the start means TTL logic is testable from the very first test you write (no
   retrofitting a fake clock in later).
2. **`CacheEntry<V>`** — the value+expiry pair. Two fields, two methods
   (`value()`, `isExpiredAt(long)`). Get this exactly right early; everything else reads
   it.
3. **`Cache<K,V>`** interface — write the method signatures before any implementation,
   so you commit to `Optional<V>` returns, TTL as `Duration`, and the exception contract
   up front.
4. **`EvictionPolicy<K>`** interface — four methods (`onInsert`, `onAccess`, `onRemove`,
   `evictionCandidate`) plus `clear()`. Write this before any concrete policy so
   `TtlCache` is built against the abstraction from day one.
5. **`TtlCache` skeleton**: constructor taking the minimum (a map, a default TTL, a
   clock), `get()`/`put()` with lazy expiry only — no eviction policy call yet, no
   listeners, no reaper. Get this compiling and demonstrably correct (a `Main` with 3-4
   lines proving TTL expiry works) before adding anything else.
6. **`LruEvictionPolicy`** — wire it into `TtlCache` (`onInsert`/`onAccess`/`onRemove`
   calls at every map mutation site, `enforceCapacity()` using `evictionCandidate()`).
7. **`CacheBuilder`** — once the constructor's parameter list has stabilized (clock,
   policy, TTL, size), wrap it in a builder.
8. **`CacheEvictionListener` + `EvictionCause`** — notification plumbing, bolted onto
   the three removal paths (expiry, size eviction, explicit invalidate).
9. **`computeIfAbsent`** — the stampede-protected load-or-get method, built last since
   it's additive and benefits from the rest of the cache already being solid.
10. **`LfuEvictionPolicy`, background reaper, `CacheStats`/`CacheStatsRecorder`** — round
    out the feature set in whatever order time/interviewer interest favors; none of
    these block each other.

---

## Minimal compilable implementation outline

This is the smallest version that is still *correct* (not a toy that skips the hard
part) — lazy expiry only, LRU only, no listeners/stats/builder. Expand into the full
design from here.

```java
package lld.ttlcache;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class MinimalTtlCache<K, V> {

    private record Entry<V>(V value, long expireAtMillis) {
        boolean isExpired(long now) {
            return expireAtMillis != Long.MAX_VALUE && now >= expireAtMillis;
        }
    }

    private final ConcurrentHashMap<K, Entry<V>> store = new ConcurrentHashMap<>();

    public Optional<V> get(K key) {
        Entry<V> entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired(System.currentTimeMillis())) {
            store.remove(key, entry); // conditional remove: the key correctness point
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    public void put(K key, V value, long ttlMillis) {
        long expireAt = ttlMillis <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + ttlMillis;
        store.put(key, new Entry<>(value, expireAt));
    }
}
```

This alone is enough to demonstrate: generics, TTL semantics, and — critically — the
conditional-remove race fix, which is the single most important line in the whole
problem. From here, layer in `EvictionPolicy`/size bounds, then listeners/stats/builder.

---

## Important methods (in order of how much interview weight they carry)

1. **`get(K key)`** — the expiry race lives here; this is the method to narrate most
   carefully, out loud, line by line.
2. **`computeIfAbsent`'s remapping function** — the stampede-protection logic; second
   most important to narrate carefully.
3. **`enforceCapacity()`** — demonstrates the `tryLock` reasoning and the
   policy-delegate pattern.
4. **`EvictionPolicy.evictionCandidate()`** (both implementations) — demonstrates DSA
   choice justification (why `LinkedHashMap`, why `TreeMap` of buckets).
5. **`CacheBuilder.build()`** — minor but shows you know to validate before
   constructing, not defer validation to first use.

---

## Common coding mistakes (and how to avoid them live)

- **Unconditional `store.remove(key)` on expiry.** This is *the* mistake this problem is
  designed to catch. If you write `if (expired) { store.remove(key); }` instead of
  `store.remove(key, entry)`, say out loud why that's wrong before an interviewer has to
  point it out: "actually wait, that's not safe if another thread just replaced this key
  — let me use the conditional form."
- **Checking expiry against a fresh `store.get(key)` instead of the entry you already
  read.** E.g. `if (store.get(key).isExpired()) { ... }` re-reads the map a second time,
  reopening exactly the gap the single-read-then-check approach closes. Always check
  expiry against the *same* entry reference you already retrieved.
- **Doing the `computeIfAbsent` check-then-load as two separate map operations**
  (a `get` to check, then a `put` to store) instead of one `compute` call. This defeats
  stampede protection entirely — say explicitly why you're using `compute` and not
  `get`-then-`put` if asked.
- **Forgetting to call `evictionPolicy.onRemove(key)` on every removal path.** It's easy
  to update the policy on `put`/`get` but forget it on the reaper's sweep or on
  `invalidate` — then the policy's bookkeeping silently grows stale entries forever,
  eventually recommending eviction of keys that are already gone. Treat "every map
  removal site has a matching policy removal call" as a checklist, not something to
  remember ad hoc.
- **Using `store.size()` instead of `store.mappingCount()`.** Minor but worth knowing:
  `ConcurrentHashMap.size()` returns `int` and can misbehave/overflow conceptually for
  very large maps; `mappingCount()` returns `long` and is the modern, recommended API.
- **Holding a lock across a listener callback.** If listener dispatch happens while
  still holding `evictionLock` or the policy's lock, a slow or reentrant listener can
  stall unrelated cache operations or even deadlock if the listener itself calls back
  into the cache. Keep listener dispatch outside any lock you can avoid holding across
  it.
- **Blocking (`lock()`) instead of `tryLock()` for capacity enforcement without
  realizing the throughput implication.** Both are defensible depending on requirements
  (§16 of the README), but pick one deliberately and be able to say why.

---

## Edge cases to explicitly call out

- `get`/`put` with a key that's never been inserted.
- `put` replacing an existing key — is this a "fresh insert" (call `onInsert`) or a
  "touch" (call `onAccess`)? This implementation treats it as a touch and reports
  `EvictionCause.REPLACED` (not `SIZE`) to listeners — a case worth stating explicitly,
  since it's a modeling decision, not an obvious default.
- An entry inserted with no TTL (or `Duration.ZERO`) — must never expire, and must not
  be mistaken for "already expired" (watch the sentinel value, `Long.MAX_VALUE`, isn't
  accidentally treated as "expire immediately").
- `maximumSize == 1` — every single `put` of a new key must immediately trigger
  eviction of whatever's there; a correct implementation handles this with no special
  casing (the `while (over capacity)` loop in `enforceCapacity` already covers it), but
  it's worth testing explicitly since off-by-one errors in capacity checks tend to show
  up exactly at this boundary.
- A key whose TTL expires between being chosen as an eviction candidate and the actual
  removal (a cross-cutting TTL/size race) — handled naturally here since
  `enforceCapacity`'s `store.remove(keyToEvict)` is unconditional (explicit
  invalidation-style removal is fine to be unconditional; it's only the *expiry* removal
  path that needs the conditional form, since expiry removal is racing against a
  possible concurrent replace of the same key, whereas an explicit "I chose to evict
  this key" removal isn't trying to protect against that specific race).
- Null keys/values at every public method boundary.
- Negative or zero TTL (`Duration.ZERO`/negative) treated as "never expires," not as an
  error — document this choice rather than leaving it ambiguous.

---

## How to narrate the code while writing it

- Narrate the *why* before the *what*: "I'm going to check expiry against the entry I
  already have, not do a second map lookup, because a second lookup reopens a race,"
  *then* write the line, rather than writing silently and explaining afterward.
- When you write a lock, say what it protects and what it deliberately does *not*
  protect, in the same breath: "this lock is just for the eviction policy's internal
  ordering structure — it's not protecting the map itself, `ConcurrentHashMap` already
  does that."
- When you make a trade-off (e.g. `tryLock` vs `lock`), say the trade-off out loud at
  the moment you make the choice, not as an afterthought if asked later.
- When you skip something for time, say so explicitly: "I'm going to leave `stats()`
  as a stub for now and come back to it if we have time, since it's additive and doesn't
  affect correctness of what I've built so far."

---

## What can be safely omitted under time pressure

Safe to omit (and say so explicitly) without undermining the core correctness story:
- `LfuEvictionPolicy` (ship LRU only; mention LFU as "a second `EvictionPolicy`
  implementation I'd add the same way").
- Background reaper (lazy expiry alone is correct, just not actively memory-bounded
  between accesses).
- `CacheStats`/`CacheStatsRecorder` (purely observability, zero correctness impact).
- `CacheBuilder` (a plain constructor is fine if time is very short — mention you'd
  wrap it in a builder given more time, don't silently skip explaining why).
- `computeIfAbsent` (only if genuinely out of time — but try hard not to cut this one,
  since the stampede-protection discussion is a strong differentiator; cut
  `LfuEvictionPolicy` and the reaper first).

**Never safe to omit**: the conditional-remove expiry fix, and being able to explain, in
your own words, why a naive check-then-act expiry implementation is wrong. If only one
thing survives a severely time-constrained round, it should be this.

---

## A simplified version implementable in 20-30 minutes

Scope: `Cache<K,V>` interface with `get`/`put(key, value, ttlMillis)`/`invalidate`;
single `MinimalTtlCache` implementation (as sketched above) using
`ConcurrentHashMap<K, Entry<V>>` where `Entry` is a private record; lazy expiry only
(no reaper); no size-based eviction at all (unbounded cache) *or*, if eviction is
explicitly required even in the short version, a bare-bones LRU via
`LinkedHashMap<K,Boolean>` directly inlined in the cache class rather than factored out
as a separate `EvictionPolicy` strategy (skip the Strategy abstraction itself under
severe time pressure — mention it as "I'd factor this interface out given more time, so
a second algorithm could be added without touching this class").

```java
package lld.ttlcache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class SimpleTtlCache<K, V> {

    private record Entry<V>(V value, long expireAtMillis) {
        boolean isExpired(long now) {
            return expireAtMillis != Long.MAX_VALUE && now >= expireAtMillis;
        }
    }

    private final ConcurrentHashMap<K, Entry<V>> store = new ConcurrentHashMap<>();
    private final ReentrantLock orderLock = new ReentrantLock();
    private final LinkedHashMap<K, Boolean> accessOrder = new LinkedHashMap<>(16, 0.75f, true);
    private final int maximumSize;

    public SimpleTtlCache(int maximumSize) {
        this.maximumSize = maximumSize;
    }

    public Optional<V> get(K key) {
        Entry<V> entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired(System.currentTimeMillis())) {
            store.remove(key, entry);
            return Optional.empty();
        }
        touch(key);
        return Optional.of(entry.value());
    }

    public void put(K key, V value, long ttlMillis) {
        long expireAt = ttlMillis <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + ttlMillis;
        store.put(key, new Entry<>(value, expireAt));
        touch(key);
        evictIfNeeded();
    }

    public void invalidate(K key) {
        store.remove(key);
        orderLock.lock();
        try {
            accessOrder.remove(key);
        } finally {
            orderLock.unlock();
        }
    }

    private void touch(K key) {
        orderLock.lock();
        try {
            accessOrder.put(key, Boolean.TRUE); // access-order map moves it to the end
        } finally {
            orderLock.unlock();
        }
    }

    private void evictIfNeeded() {
        if (store.mappingCount() <= maximumSize) {
            return;
        }
        orderLock.lock();
        K victim;
        try {
            Map.Entry<K, Boolean> eldest = accessOrder.entrySet().stream().findFirst().orElse(null);
            if (eldest == null) {
                return;
            }
            victim = eldest.getKey();
            accessOrder.remove(victim);
        } finally {
            orderLock.unlock();
        }
        store.remove(victim);
    }
}
```

This keeps the one non-negotiable correctness property (the conditional expiry remove)
while deliberately inlining the LRU bookkeeping rather than factoring it out — an
honest, explicitly-flagged simplification for a 20-30 minute window, not a silent cut.
