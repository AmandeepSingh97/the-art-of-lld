package lld.lfucache;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A single-threaded, O(1) amortized-time Least-Frequently-Used (LFU) cache.
 *
 * <h2>Policy</h2>
 * On eviction, the entry with the smallest access frequency is removed. If
 * multiple entries share the minimum frequency, the <em>least recently
 * used</em> among them is evicted (LRU as a tie-breaker) — this is the
 * standard LFU specification (LeetCode 460) and the only tie-break rule that
 * can be implemented in true O(1); anything based on, say, insertion order
 * across the whole cache would require a separate ordering structure.
 *
 * <h2>Why O(1) is achievable</h2>
 * The design rests on three cooperating structures, each chosen because a
 * single {@link HashMap} or a single list cannot answer all three questions
 * a cache needs answered in O(1): "does this key exist", "what is the
 * least-frequently-used entry", and "within a frequency, what was used
 * longest ago".
 * <ol>
 *   <li>{@code keyToNode: Map<K, Node<K,V>>} — O(1) existence check and
 *       value retrieval by key, same role as in a plain {@code HashMap}
 *       cache.</li>
 *   <li>{@code frequencyToList: Map<Integer, FrequencyList<K,V>>} — buckets
 *       keys by access frequency. Each bucket is an intrusive doubly linked
 *       list ordered by recency (most-recently-touched-at-this-frequency at
 *       the head, least-recently-touched at the tail). Because {@link Node}
 *       objects carry their own {@code prev}/{@code next} pointers, moving a
 *       node between buckets is pointer surgery, not a search.</li>
 *   <li>{@code minFrequency} — a single integer, incrementally maintained,
 *       that always names the bucket containing the next eviction
 *       candidate. This avoids scanning all buckets (which would be O(number
 *       of distinct frequencies)) or using a sorted structure (which would
 *       be O(log n)).</li>
 * </ol>
 * See {@code README.md} section 10 for the full per-operation proof that
 * every step touches only O(1) pointers/map entries.
 *
 * <h2>Thread safety</h2>
 * This class performs <b>no synchronization</b>. Concurrent {@code get}/
 * {@code put} calls from multiple threads can corrupt the internal doubly
 * linked lists (e.g. two threads unlinking the same node) or leave
 * {@code minFrequency} pointing at an empty or wrong bucket. Use {@link
 * ConcurrentLFUCache} to wrap an instance of this class for multi-threaded
 * use; do not add {@code synchronized} piecemeal to individual methods here
 * — see {@code STAFF_LEVEL_DEEP_DIVE.md} for why that is unsafe (compound
 * actions spanning multiple method calls still need external locking) and
 * why the decorator is the right seam instead.
 *
 * @param <K> the key type; must implement {@code equals}/{@code hashCode}
 *            correctly and must not be mutated while in the cache.
 * @param <V> the value type.
 */
public final class LFUCache<K, V> implements Cache<K, V> {

    private final int capacity;
    private final Map<K, Node<K, V>> keyToNode;
    private final Map<Integer, FrequencyList<K, V>> frequencyToList;
    private final EvictionListener<K, V> evictionListener;
    private int minFrequency;

    /**
     * Creates an LFU cache that silently evicts the least-frequently-used
     * (LRU tie-broken) entry when full.
     *
     * @param capacity maximum number of entries; {@code 0} is a legal,
     *                 degenerate cache that never stores anything.
     * @throws InvalidCacheCapacityException if {@code capacity < 0}
     */
    public LFUCache(int capacity) {
        this(capacity, (key, value) -> { /* no-op by default */ });
    }

    /**
     * Creates an LFU cache that notifies {@code evictionListener} whenever an
     * entry is evicted to make room for a new one. The listener is not
     * invoked for explicit overwrites of an existing key.
     *
     * @param capacity         maximum number of entries; {@code 0} is legal.
     * @param evictionListener callback invoked with the evicted key/value;
     *                         must not be {@code null} (pass a no-op lambda
     *                         if notifications are not needed).
     * @throws InvalidCacheCapacityException if {@code capacity < 0}
     */
    public LFUCache(int capacity, EvictionListener<K, V> evictionListener) {
        if (capacity < 0) {
            throw new InvalidCacheCapacityException(capacity);
        }
        this.capacity = capacity;
        this.evictionListener = java.util.Objects.requireNonNull(evictionListener, "evictionListener");
        // Load factor and initial sizing are left to defaults deliberately:
        // over-tuning a HashMap's capacity for an interview-scope cache is a
        // premature optimization that obscures the actual algorithm.
        this.keyToNode = new HashMap<>();
        this.frequencyToList = new HashMap<>();
        this.minFrequency = 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Complexity:</b> O(1) amortized. One {@code HashMap} lookup plus
     * one node migration between two {@link FrequencyList} buckets, each of
     * which is O(1) pointer manipulation.
     */
    @Override
    public Optional<V> get(K key) {
        Node<K, V> node = keyToNode.get(key);
        if (node == null) {
            return Optional.empty();
        }
        touch(node);
        return Optional.of(node.value);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Complexity:</b> O(1) amortized, including eviction: eviction
     * removes exactly one node from the tail of the minimum-frequency
     * bucket, never more.
     */
    @Override
    public void put(K key, V value) {
        if (capacity == 0) {
            return; // degenerate cache: never stores anything, never evicts
        }

        Node<K, V> existing = keyToNode.get(key);
        if (existing != null) {
            existing.value = value;
            touch(existing); // an update counts as an access for LFU purposes
            return;
        }

        if (keyToNode.size() >= capacity) {
            evictLeastFrequentlyUsed();
        }

        Node<K, V> created = new Node<>(key, value);
        keyToNode.put(key, created);
        frequencyToList
                .computeIfAbsent(1, freq -> new FrequencyList<>())
                .addToHead(created);
        minFrequency = 1;
    }

    @Override
    public int size() {
        return keyToNode.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    /**
     * Bumps {@code node}'s frequency by one and relocates it from its
     * current frequency bucket to the next, maintaining {@link
     * #minFrequency} along the way. This single method is the crux of the
     * O(1) guarantee: both {@code get} and {@code put} (on an existing key)
     * reduce to a call here.
     */
    private void touch(Node<K, V> node) {
        int oldFrequency = node.frequency;
        FrequencyList<K, V> oldList = frequencyToList.get(oldFrequency);
        oldList.remove(node);
        if (oldList.isEmpty()) {
            frequencyToList.remove(oldFrequency);
            // If the bucket we just emptied was the minimum, the minimum
            // necessarily moves up by exactly one: nothing can have been
            // sitting at oldFrequency - 1 and skipped over oldFrequency,
            // because frequencies only ever increase one step at a time.
            if (oldFrequency == minFrequency) {
                minFrequency++;
            }
        }

        node.frequency = oldFrequency + 1;
        frequencyToList
                .computeIfAbsent(node.frequency, freq -> new FrequencyList<>())
                .addToHead(node);
    }

    /**
     * Removes the least-recently-used node from the minimum-frequency
     * bucket (the LFU + LRU tie-break eviction candidate) and notifies the
     * eviction listener.
     */
    private void evictLeastFrequentlyUsed() {
        FrequencyList<K, V> minList = frequencyToList.get(minFrequency);
        Node<K, V> evicted = minList.removeTail();
        keyToNode.remove(evicted.key);
        if (minList.isEmpty()) {
            frequencyToList.remove(minFrequency);
        }
        evictionListener.onEvict(evicted.key, evicted.value);
    }

    /**
     * An intrusive doubly linked list node. "Intrusive" means the node
     * carries its own {@code prev}/{@code next} pointers rather than being
     * wrapped by a general-purpose list container; this is what allows
     * {@link #touch} to unlink a node from one bucket and relink it into
     * another in O(1) without searching either list, something a {@link
     * java.util.LinkedList} (which hides its nodes) cannot do — removal from
     * a {@code LinkedList} given only a value is O(n).
     */
    private static final class Node<K, V> {
        final K key;
        V value;
        int frequency;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
            this.frequency = 1;
        }
    }

    /**
     * A doubly linked list of {@link Node}s sharing the same access
     * frequency, kept in recency order (most-recently-touched at the head).
     * Backed by sentinel head/tail nodes so that add/remove never need a
     * null check for "list is empty" or "node is first/last" — a standard
     * idiom that trades two permanently-allocated sentinel objects per
     * bucket for branch-free O(1) splice operations.
     */
    private static final class FrequencyList<K, V> {
        private final Node<K, V> head = new Node<>(null, null);
        private final Node<K, V> tail = new Node<>(null, null);
        private int size;

        FrequencyList() {
            head.next = tail;
            tail.prev = head;
        }

        void addToHead(Node<K, V> node) {
            node.prev = head;
            node.next = head.next;
            head.next.prev = node;
            head.next = node;
            size++;
        }

        void remove(Node<K, V> node) {
            node.prev.next = node.next;
            node.next.prev = node.prev;
            node.prev = null;
            node.next = null;
            size--;
        }

        Node<K, V> removeTail() {
            Node<K, V> last = tail.prev;
            remove(last);
            return last;
        }

        boolean isEmpty() {
            return size == 0;
        }
    }
}
