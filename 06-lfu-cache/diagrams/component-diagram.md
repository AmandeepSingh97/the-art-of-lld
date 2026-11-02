# Component Diagram

Shows how the public `Cache<K,V>` abstraction is implemented and decorated.
Arrows are "depends on / wraps".

```mermaid
graph TD
    Client["Client code"]

    subgraph "lld.lfucache"
        CacheIfc["«interface» Cache&lt;K,V&gt;"]
        LFUCache["LFUCache&lt;K,V&gt;<br/>(core policy, single-threaded)"]
        ConcurrentLFUCache["ConcurrentLFUCache&lt;K,V&gt;<br/>(thread-safety decorator)"]
        StatsRecordingCache["StatsRecordingCache&lt;K,V&gt;<br/>(metrics decorator)"]
        EvictionListener["«interface» EvictionListener&lt;K,V&gt;"]
        CacheStats["CacheStats<br/>(immutable record)"]
        CacheFactory["CacheFactory<br/>(static assembly helpers)"]
        InvalidCapacityEx["InvalidCacheCapacityException"]
    end

    Client --> CacheIfc
    Client -.uses.-> CacheFactory
    CacheFactory --> LFUCache
    CacheFactory --> ConcurrentLFUCache
    CacheFactory --> StatsRecordingCache

    LFUCache -.implements.-> CacheIfc
    ConcurrentLFUCache -.implements.-> CacheIfc
    StatsRecordingCache -.implements.-> CacheIfc

    ConcurrentLFUCache -->|wraps a| CacheIfc
    StatsRecordingCache -->|wraps a| CacheIfc
    LFUCache -->|notifies| EvictionListener
    LFUCache -.throws.-> InvalidCapacityEx
    StatsRecordingCache -->|produces| CacheStats
```

**Why decorators, not subclasses:** `ConcurrentLFUCache` and
`StatsRecordingCache` each wrap a `Cache<K,V>` rather than extending
`LFUCache`. `LFUCache` is `final`: its correctness depends on nobody
overriding `get`/`put` and bypassing the frequency-bucket bookkeeping. Two
orthogonal concerns (locking, metrics) are composed externally instead,
so either can be added, removed, or reordered without touching the policy
implementation — this is the Decorator pattern, justified in detail in
`README.md` section 7.
