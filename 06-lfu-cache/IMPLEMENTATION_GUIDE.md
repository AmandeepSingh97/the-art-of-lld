# Implementation Guide — LFU Cache

## Which classes to write first

1. `Cache<K,V>` — the interface, two minutes of typing, establishes the
   contract before any internals exist.
2. `Node<K,V>` — as a private static nested class inside the eventual
   `LFUCache`. Fields: `key` (final), `value`, `frequency`, `prev`, `next`.
3. `FrequencyList<K,V>` — also private static nested, with sentinel
   `head`/`tail`. Get `addToHead`/`remove`/`removeTail` working and
   (ideally) manually traced/tested before touching `LFUCache` itself —
   bugs here are far easier to spot in isolation than inside the full
   cache.
4. `LFUCache<K,V>` — fields, constructor(s), `get`, `put`, the shared
   `touch` helper, `evictLeastFrequentlyUsed`.
5. `InvalidCacheCapacityException` — one-liner, write whenever the
   constructor needs it.
6. Only if time remains: `EvictionListener`, `ConcurrentLFUCache`,
   `StatsRecordingCache`, `CacheFactory`.

## Minimal compilable implementation outline

```java
public interface Cache<K, V> {
    Optional<V> get(K key);
    void put(K key, V value);
    int size();
    int capacity();
}

public final class LFUCache<K, V> implements Cache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> keyToNode = new HashMap<>();
    private final Map<Integer, FrequencyList<K, V>> frequencyToList = new HashMap<>();
    private int minFrequency;

    public LFUCache(int capacity) {
        if (capacity < 0) throw new IllegalArgumentException();
        this.capacity = capacity;
    }

    @Override
    public Optional<V> get(K key) {
        Node<K, V> node = keyToNode.get(key);
        if (node == null) return Optional.empty();
        touch(node);
        return Optional.of(node.value);
    }

    @Override
    public void put(K key, V value) {
        if (capacity == 0) return;
        Node<K, V> existing = keyToNode.get(key);
        if (existing != null) {
            existing.value = value;
            touch(existing);
            return;
        }
        if (keyToNode.size() >= capacity) evict();
        Node<K, V> node = new Node<>(key, value);
        keyToNode.put(key, node);
        frequencyToList.computeIfAbsent(1, f -> new FrequencyList<>()).addToHead(node);
        minFrequency = 1;
    }

    private void touch(Node<K, V> node) {
        int oldFreq = node.frequency;
        FrequencyList<K, V> oldList = frequencyToList.get(oldFreq);
        oldList.remove(node);
        if (oldList.isEmpty()) {
            frequencyToList.remove(oldFreq);
            if (oldFreq == minFrequency) minFrequency++;
        }
        node.frequency = oldFreq + 1;
        frequencyToList.computeIfAbsent(node.frequency, f -> new FrequencyList<>()).addToHead(node);
    }

    private void evict() {
        FrequencyList<K, V> minList = frequencyToList.get(minFrequency);
        Node<K, V> evicted = minList.removeTail();
        keyToNode.remove(evicted.key);
        if (minList.isEmpty()) frequencyToList.remove(minFrequency);
    }

    @Override public int size() { return keyToNode.size(); }
    @Override public int capacity() { return capacity; }

    private static final class Node<K, V> {
        final K key; V value; int frequency = 1;
        Node<K, V> prev, next;
        Node(K key, V value) { this.key = key; this.value = value; }
    }

    private static final class FrequencyList<K, V> {
        private final Node<K, V> head = new Node<>(null, null);
        private final Node<K, V> tail = new Node<>(null, null);
        private int size;
        FrequencyList() { head.next = tail; tail.prev = head; }
        void addToHead(Node<K, V> n) { n.prev = head; n.next = head.next; head.next.prev = n; head.next = n; size++; }
        void remove(Node<K, V> n) { n.prev.next = n.next; n.next.prev = n.prev; size--; }
        Node<K, V> removeTail() { Node<K, V> last = tail.prev; remove(last); return last; }
        boolean isEmpty() { return size == 0; }
    }
}
```

This compiles and is behaviorally complete for the core interview ask; the
full repository version additionally adds the eviction listener, javadoc,
null-safety on the listener, and the decorators.

## Suggested implementation sequence (with rough time budget for a 45-min round)

1. (2 min) Write `Cache<K,V>`.
2. (5 min) Write `Node` + `FrequencyList`, narrate the sentinel trick while
   writing it.
3. (3 min) Write `LFUCache` fields + constructor.
4. (4 min) Write `get` (trivial once `touch` exists — write `get` calling
   a not-yet-written `touch`, then implement `touch`).
5. (6 min) Write `touch`, narrating the `minFrequency` bump condition
   explicitly as you write the `if` — this is the line most likely to
   prompt a follow-up question, so narrate it before being asked.
6. (5 min) Write `put`'s update path (reuses `touch`) then insert/eviction
   path (`evict()`).
7. (5 min) Manually trace 3–4 operations against the interviewer including
   one eviction and one tie, pointing at the actual lines of code as you
   go.
8. Remaining time: concurrency discussion, `EvictionListener` if asked,
   complexity-proof narration.

## Important methods, in order of how much interview weight they carry

1. `touch(Node)` — the single method carrying the entire O(1) claim;
   expect the most scrutiny here.
2. `evictLeastFrequentlyUsed()`/`evict()` — second most scrutiny, because
   it's where the LRU tie-break is actually realized (`removeTail()`).
3. `put` — mostly orchestration once `touch`/`evict` exist; lower
   intrinsic complexity but still needs the update-vs-insert branch right.
4. `get` — trivial given `touch`; rarely a source of bugs if `touch` is
   correct.
5. `FrequencyList.addToHead`/`remove`/`removeTail` — mechanical but
   easy to get an off-by-one/pointer-swap wrong under time pressure;
   worth writing slowly and checking by hand once.

## Common coding mistakes (and how to avoid them live)

- **Forgetting that an update (`put` on an existing key) must still bump
  frequency.** Write the update branch to explicitly call `touch`, don't
  special-case it as "just overwrite the value."
- **Incrementing `minFrequency` unconditionally on every `touch`, instead
  of only when the vacated bucket was the minimum *and* is now empty.**
  This is the single most common LFU bug. Say the condition out loud as
  you type it: "only bump the minimum if the bucket I just left behind is
  now empty, *and* it was the minimum bucket."
- **Forgetting to reset `minFrequency = 1` on every new insert.** Without
  this, after the first eviction cycle `minFrequency` can get stuck above
  1 even though a freshly-inserted key (frequency 1) now exists, causing
  `evict()` to look in the wrong (non-existent or wrong) bucket.
- **Removing a node from its old bucket's map entry only when the bucket
  becomes empty — but forgetting to also check whether that was the
  `minFrequency` bucket before removing the map entry.** Order matters:
  check/act on `minFrequency` using `oldFreq`, not after the map entry is
  already gone.
- **Using a `LinkedList`/`ArrayList` instead of an intrusive linked list**
  for frequency buckets, quietly reintroducing an O(n) removal cost and
  defeating the entire point of the design — if you catch yourself reaching
  for `java.util.LinkedList`, stop and build the intrusive node-based
  version instead.
- **Forgetting the sentinel nodes and null-checking `head`/`tail`
  everywhere instead**, which multiplies the chance of an edge-case bug
  under interview time pressure.

## Edge cases to call out explicitly (even if not asked)

- Capacity 0 — decide and state "no-op cache" rather than letting it be a
  silent accident of the code.
- Capacity 1 — every `put` of a new key evicts the current single
  occupant, regardless of its frequency.
- Updating a key that doesn't exist vs. one that does — make sure your
  `put` branches on `existing != null` correctly, not on whether `get`
  would succeed (same check, but be deliberate about reusing
  `keyToNode.get` rather than accidentally calling the mutating `get`
  method and double-bumping frequency).
- A `get`/`put` sequence that ties two keys at the same frequency, then
  evicts — the textbook "did you get the tie-break right" check.
- Negative capacity — fail fast at construction, not lazily on first use.

## How to narrate the code while writing it

Speak in terms of the *invariant* you're maintaining, not the mechanics:
"I'm moving this node out of its current frequency bucket — if that
leaves the bucket empty, I need to forget about that bucket entirely and,
if it happened to be the overall minimum, the minimum has to move up by
exactly one, because frequencies only ever go up by one at a time." This
kind of narration pre-empts the "why did you write that `if`?" follow-up
by answering it before it's asked, which reads as confidence and depth
rather than rote recall.

## What can be safely omitted under time pressure

- `EvictionListener` and any eviction-notification mechanism — mention
  verbally as a natural extension point, don't build it, unless asked.
- `StatsRecordingCache`, `CacheFactory` — entirely optional; these exist in
  the full repo to answer "how would you add metrics" concretely, not
  because every interview needs them built.
- `ConcurrentLFUCache`'s actual code — a correct verbal explanation (single
  lock, why striping/lock-free don't help here) is usually sufficient
  unless the interviewer explicitly asks you to write the thread-safe
  version.
- Javadoc — write comments only where the "why" isn't obvious from the
  code (e.g. the `minFrequency` bump condition); skip prose documentation
  entirely under real time pressure.
- Capacity validation beyond a simple `if (capacity < 0) throw ...` — a
  custom exception type is a nice-to-have, not essential, in a live round.

## A simplified version implementable in 20–30 minutes

Drop: the custom `InvalidCacheCapacityException` (use
`IllegalArgumentException` directly), the `Cache<K,V>` interface (code
`LFUCache` as a single concrete class if truly pressed for time, though
keeping the interface costs almost nothing and is worth keeping even under
pressure), and all decorators. Keep: `Node`, `FrequencyList` with
sentinels, `get`, `put`, `touch`, `evict` — exactly the "Minimal compilable
implementation outline" above, which is a complete, correct, O(1) LFU
cache in roughly 80 lines of code once written without the javadoc and
extra constructors present in the full repository version.
