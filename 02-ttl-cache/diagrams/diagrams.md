# Diagrams — TTL-based Cache Library

These Mermaid sources are the ones embedded in `README.md`. Kept here standalone so they
can be rendered independently (e.g. with the Mermaid CLI or a live editor) and so the
README stays readable as prose with diagrams inlined by reference.

## Component diagram

```mermaid
graph TB
    subgraph "Caller code"
        Client["Application code"]
    end

    subgraph "lld.ttlcache (public API)"
        CacheIfc["Cache&lt;K,V&gt; (interface)"]
        Builder["CacheBuilder&lt;K,V&gt;"]
    end

    subgraph "lld.ttlcache (implementation)"
        TtlCache["TtlCache&lt;K,V&gt;"]
        Entry["CacheEntry&lt;V&gt; (immutable)"]
        StatsRec["CacheStatsRecorder"]
        Stats["CacheStats (snapshot)"]
        ClockIfc["Clock"]
        Listener["CacheEvictionListener&lt;K,V&gt;"]
        Reaper["Background reaper\n(ScheduledExecutorService)"]
    end

    subgraph "lld.ttlcache.policy"
        PolicyIfc["EvictionPolicy&lt;K&gt; (Strategy)"]
        Lru["LruEvictionPolicy&lt;K&gt;"]
        Lfu["LfuEvictionPolicy&lt;K&gt;"]
    end

    subgraph "JDK"
        CHM["ConcurrentHashMap&lt;K, CacheEntry&lt;V&gt;&gt;"]
    end

    Client --> CacheIfc
    Client --> Builder
    Builder -- "builds" --> TtlCache
    Builder -- "selects" --> PolicyIfc
    TtlCache -. implements .-> CacheIfc
    TtlCache --> CHM
    CHM --> Entry
    TtlCache --> PolicyIfc
    PolicyIfc <|.. Lru
    PolicyIfc <|.. Lfu
    TtlCache --> StatsRec
    StatsRec --> Stats
    TtlCache --> ClockIfc
    TtlCache --> Reaper
    Reaper --> CHM
    TtlCache -. notifies .-> Listener
```

## Class diagram

```mermaid
classDiagram
    class Cache~K,V~ {
        <<interface>>
        +get(K) Optional~V~
        +put(K, V) void
        +put(K, V, Duration) void
        +computeIfAbsent(K, Function) V
        +computeIfAbsent(K, Function, Duration) V
        +invalidate(K) void
        +invalidateAll() void
        +size() long
        +stats() CacheStats
        +close() void
    }

    class TtlCache~K,V~ {
        -ConcurrentHashMap~K,CacheEntry~V~~ store
        -ReentrantLock evictionLock
        -long maximumSize
        -Duration defaultTtl
        -EvictionPolicy~K~ evictionPolicy
        -Clock clock
        -List~CacheEvictionListener~K,V~~ listeners
        -CacheStatsRecorder stats
        -ScheduledExecutorService reaper
        +get(K) Optional~V~
        +put(K, V, Duration) void
        +computeIfAbsent(K, Function, Duration) V
        -enforceCapacity() void
        -expireIfStillPresent(K, CacheEntry) void
        -reapExpiredEntries() void
    }

    class CacheBuilder~K,V~ {
        -long maximumSize
        -Duration defaultTtl
        -Supplier~EvictionPolicy~K~~ evictionPolicyFactory
        -Duration reaperInterval
        -Clock clock
        -Executor listenerExecutor
        -List~CacheEvictionListener~ listeners
        +newBuilder()$ CacheBuilder~K,V~
        +maximumSize(long) CacheBuilder
        +defaultTtl(Duration) CacheBuilder
        +evictionPolicy(Supplier) CacheBuilder
        +reaperInterval(Duration) CacheBuilder
        +clock(Clock) CacheBuilder
        +addListener(CacheEvictionListener) CacheBuilder
        +listenerExecutor(Executor) CacheBuilder
        +build() TtlCache~K,V~
    }

    class CacheEntry~V~ {
        <<immutable>>
        -V value
        -long expireAtMillis
        +value() V
        +isExpiredAt(long) boolean
    }

    class EvictionPolicy~K~ {
        <<interface>>
        +onInsert(K) void
        +onAccess(K) void
        +onRemove(K) void
        +evictionCandidate() Optional~K~
        +clear() void
    }

    class LruEvictionPolicy~K~ {
        -ReentrantLock lock
        -LinkedHashMap~K,Boolean~ recencyOrder
    }

    class LfuEvictionPolicy~K~ {
        -ReentrantLock lock
        -Map~K,Integer~ frequencyOf
        -TreeMap~Integer,LinkedHashSet~K~~ keysByFrequency
    }

    class CacheEvictionListener~K,V~ {
        <<interface>>
        +onEviction(K, V, EvictionCause) void
    }

    class EvictionCause {
        <<enumeration>>
        EXPIRED
        SIZE
        EXPLICIT
        REPLACED
    }

    class Clock {
        <<interface>>
        +currentTimeMillis() long
        +systemClock()$ Clock
    }

    class CacheStatsRecorder {
        -LongAdder hits
        -LongAdder misses
        -LongAdder evictions
        -LongAdder expirations
        +snapshot(long) CacheStats
    }

    class CacheStats {
        <<record>>
        +long hitCount
        +long missCount
        +long evictionCount
        +long expirationCount
        +long size
        +hitRate() double
        +missRate() double
    }

    Cache <|.. TtlCache
    TtlCache --> CacheEntry : stores
    TtlCache --> EvictionPolicy : delegates to
    TtlCache --> Clock : reads time from
    TtlCache --> CacheStatsRecorder : updates
    TtlCache --> CacheEvictionListener : notifies
    CacheStatsRecorder --> CacheStats : snapshots to
    CacheBuilder --> TtlCache : builds
    CacheBuilder --> EvictionPolicy : selects factory for
    EvictionPolicy <|.. LruEvictionPolicy
    EvictionPolicy <|.. LfuEvictionPolicy
    TtlCache --> EvictionCause : reports
```

## Sequence diagram: `get(key)` racing lazy expiry

```mermaid
sequenceDiagram
    participant Caller
    participant TtlCache
    participant CHM as ConcurrentHashMap
    participant Policy as EvictionPolicy
    participant Listener as CacheEvictionListener

    Caller->>TtlCache: get(key)
    TtlCache->>CHM: get(key)
    CHM-->>TtlCache: entry (CacheEntry, immutable)
    alt entry == null
        TtlCache-->>Caller: Optional.empty() [miss]
    else entry.isExpiredAt(now)
        TtlCache->>CHM: remove(key, entry)  %% conditional CAS-remove
        alt removal succeeded (entry still == the one we read)
            TtlCache->>Policy: onRemove(key)
            TtlCache->>Listener: onEviction(key, value, EXPIRED)
        else another thread already replaced/removed it
            Note over TtlCache,CHM: no-op: the newer entry (if any) is left untouched
        end
        TtlCache-->>Caller: Optional.empty() [miss]
    else entry is live
        TtlCache->>Policy: onAccess(key)
        TtlCache-->>Caller: Optional.of(entry.value()) [hit]
    end
```

## Sequence diagram: `computeIfAbsent` under a concurrent stampede

```mermaid
sequenceDiagram
    participant T1 as Thread 1
    participant T2 as Thread 2..N
    participant CHM as ConcurrentHashMap
    participant Loader as loader function
    participant Policy as EvictionPolicy

    par N threads call computeIfAbsent("k", loader) concurrently
        T1->>CHM: compute("k", remappingFn)
        T2->>CHM: compute("k", remappingFn)
    end
    Note over CHM: CHM serializes concurrent compute() calls\nfor the SAME key (bin-level lock); other keys unaffected
    CHM->>Loader: loader.apply("k")  [exactly once, by whichever thread wins]
    Loader-->>CHM: loadedValue
    CHM-->>T1: new CacheEntry(loadedValue)
    CHM-->>T2: same CacheEntry (no second load)
    T1->>Policy: onInsert("k")
    Note over T2: T2's branch saw loadedFreshValue==false -> onAccess("k") instead
```

## Sequence diagram: size-based eviction on `put`

```mermaid
sequenceDiagram
    participant Caller
    participant TtlCache
    participant CHM as ConcurrentHashMap
    participant Lock as evictionLock (tryLock)
    participant Policy as EvictionPolicy
    participant Listener

    Caller->>TtlCache: put(key, value, ttl)
    TtlCache->>CHM: put(key, newEntry)
    CHM-->>TtlCache: previous (null => new key)
    TtlCache->>Policy: onInsert(key)
    TtlCache->>TtlCache: enforceCapacity()
    alt store.mappingCount() > maximumSize
        TtlCache->>Lock: tryLock()
        alt lock acquired
            loop while over capacity
                TtlCache->>Policy: evictionCandidate()
                Policy-->>TtlCache: victim key
                TtlCache->>CHM: remove(victimKey)
                TtlCache->>Policy: onRemove(victimKey)
                TtlCache->>Listener: onEviction(victimKey, value, SIZE)
            end
            TtlCache->>Lock: unlock()
        else lock held by another thread
            Note over TtlCache: skip; the other thread's in-flight\neviction pass will restore capacity
        end
    end
    TtlCache-->>Caller: (void)
```
