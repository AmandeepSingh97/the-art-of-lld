# Class Diagram

Matches the actual classes under `src/main/java/lld/lfucache/`. `Node` and
`FrequencyList` are private static nested classes of `LFUCache`; they are
shown here because they are essential to explaining the O(1) design, not
because they are part of the public API.

```mermaid
classDiagram
    class Cache~K,V~ {
        <<interface>>
        +get(K key) Optional~V~
        +put(K key, V value) void
        +size() int
        +capacity() int
    }

    class EvictionListener~K,V~ {
        <<interface>>
        +onEvict(K key, V value) void
    }

    class LFUCache~K,V~ {
        -int capacity
        -Map~K, Node~K,V~~ keyToNode
        -Map~Integer, FrequencyList~K,V~~ frequencyToList
        -EvictionListener~K,V~ evictionListener
        -int minFrequency
        +LFUCache(int capacity)
        +LFUCache(int capacity, EvictionListener~K,V~ listener)
        +get(K key) Optional~V~
        +put(K key, V value) void
        +size() int
        +capacity() int
        -touch(Node~K,V~ node) void
        -evictLeastFrequentlyUsed() void
    }

    class Node~K,V~ {
        <<private static nested>>
        ~K key~
        ~V value~
        ~int frequency~
        ~Node~K,V~ prev~
        ~Node~K,V~ next~
    }

    class FrequencyList~K,V~ {
        <<private static nested>>
        -Node~K,V~ head
        -Node~K,V~ tail
        -int size
        +addToHead(Node~K,V~ node) void
        +remove(Node~K,V~ node) void
        +removeTail() Node~K,V~
        +isEmpty() boolean
    }

    class ConcurrentLFUCache~K,V~ {
        -Cache~K,V~ delegate
        -ReentrantLock lock
        +ConcurrentLFUCache(Cache~K,V~ delegate)
        +get(K key) Optional~V~
        +put(K key, V value) void
        +size() int
        +capacity() int
    }

    class StatsRecordingCache~K,V~ {
        -Cache~K,V~ delegate
        -AtomicLong hitCount
        -AtomicLong missCount
        -AtomicLong evictionCount
        +forLFUCache(int capacity)$ StatsRecordingCache~K,V~
        +get(K key) Optional~V~
        +put(K key, V value) void
        +stats() CacheStats
    }

    class CacheStats {
        <<record>>
        +long hitCount
        +long missCount
        +long evictionCount
        +requestCount() long
        +hitRate() double
    }

    class CacheFactory {
        <<utility>>
        +lfuCache(int capacity)$ Cache~K,V~
        +threadSafeLfuCache(int capacity)$ Cache~K,V~
        +instrumentedLfuCache(int capacity)$ StatsRecordingCache~K,V~
        +instrumentedThreadSafeLfuCache(int capacity)$ StatsRecordingCache~K,V~
    }

    class InvalidCacheCapacityException {
        <<exception>>
    }

    Cache <|.. LFUCache
    Cache <|.. ConcurrentLFUCache
    Cache <|.. StatsRecordingCache
    LFUCache *-- "0..*" Node : owns, per key
    LFUCache *-- "0..*" FrequencyList : owns, per distinct frequency
    FrequencyList o-- "0..*" Node : links (intrusive DLL)
    LFUCache ..> EvictionListener : notifies
    LFUCache ..> InvalidCacheCapacityException : throws
    ConcurrentLFUCache o-- Cache : wraps
    StatsRecordingCache o-- Cache : wraps
    StatsRecordingCache ..> CacheStats : produces
    CacheFactory ..> LFUCache : creates
    CacheFactory ..> ConcurrentLFUCache : creates
    CacheFactory ..> StatsRecordingCache : creates
```

**Reading the composition relationships:** `LFUCache *-- Node` is a strong
composition — a `Node`'s lifecycle is entirely owned by the `LFUCache`
that created it (created on `put`, destroyed on eviction or never exposed
outside). `FrequencyList o-- Node` is a weaker aggregation: the same `Node`
object is linked into exactly one `FrequencyList` at a time, but the list
doesn't "own" the node's identity — `LFUCache.touch()` moves a node between
lists by re-linking pointers, not by copying or recreating it.
