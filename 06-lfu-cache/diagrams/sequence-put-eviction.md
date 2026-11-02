# Sequence Diagram: `put(key, value)` with eviction

Shows the path where the key is new and the cache is at capacity, forcing
an eviction before the insert.

```mermaid
sequenceDiagram
    participant Client
    participant LFUCache
    participant keyToNode as keyToNode (HashMap)
    participant minList as FrequencyList[minFrequency]
    participant freq1List as FrequencyList[1]
    participant Listener as EvictionListener

    Client->>LFUCache: put(key, value)
    LFUCache->>keyToNode: get(key)
    keyToNode-->>LFUCache: null (new key)
    LFUCache->>keyToNode: size()
    keyToNode-->>LFUCache: size == capacity

    LFUCache->>LFUCache: evictLeastFrequentlyUsed()
    LFUCache->>minList: removeTail()
    minList-->>LFUCache: evicted node (LRU within min frequency)
    LFUCache->>keyToNode: remove(evicted.key)
    opt minList now empty
        LFUCache->>LFUCache: frequencyToList.remove(minFrequency)
    end
    LFUCache->>Listener: onEvict(evicted.key, evicted.value)

    LFUCache->>LFUCache: new Node(key, value), frequency = 1
    LFUCache->>keyToNode: put(key, node)
    LFUCache->>freq1List: addToHead(node)
    LFUCache->>LFUCache: minFrequency = 1
    LFUCache-->>Client: (void)
```

**Why `minFrequency` resets to 1 on every insert, unconditionally:** a
brand-new node always starts at frequency 1, and frequency 1 is always
`<=` any other bucket's frequency, so it is always safe (and correct) for
the new minimum to be 1 — no comparison against the previous `minFrequency`
is needed, which keeps `put` O(1) instead of requiring a scan of existing
buckets.
