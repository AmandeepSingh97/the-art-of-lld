# Sequence Diagrams

## 1. Create a contact

```mermaid
sequenceDiagram
    actor Client
    participant CS as ContactService
    participant Builder as Contact.Builder
    participant Repo as InMemoryContactRepository
    participant IM as IndexManager

    Client->>CS: create(builder)
    CS->>Builder: build()
    Builder-->>CS: Contact (validated: non-blank name)
    CS->>CS: validateBusinessInvariants(contact)\n(>=1 phone or email)
    CS->>Repo: save(contact)
    Repo->>Repo: store.put(id, contact)
    Repo-->>IM: onChange(CREATED, contact)
    IM->>IM: indexAll(contact)\n(name tokens, phones, emails,\nfavorite flag, group membership)
    Repo-->>CS: (void)
    CS-->>Client: contact
```

## 2. Update a contact (optimistic concurrency / CAS retry loop)

```mermaid
sequenceDiagram
    actor Client
    participant CS as ContactService
    participant Repo as InMemoryContactRepository
    participant HS as HistoryService

    Client->>CS: update(id, mutator)
    loop up to MAX_CAS_RETRIES
        CS->>Repo: findById(id)
        Repo-->>CS: current (immutable snapshot)
        CS->>CS: builder = current.toBuilder();\nmutator.accept(builder);\nupdated = builder.build()
        CS->>Repo: compareAndSet(id, current, updated)
        alt current is still the mapped value (reference ==)
            Repo-->>CS: true
            CS->>HS: record(id, current)\n(memento = old snapshot)
            CS-->>Client: updated
        else another writer swapped it first
            Repo-->>CS: false
            Note over CS: retry with fresh current
        end
    end
```

## 3. Merge two duplicate contacts (+ undo)

```mermaid
sequenceDiagram
    actor Client
    participant CS as ContactService
    participant Lock as structuralLock
    participant Repo as InMemoryContactRepository
    participant Merger as ContactMerger
    participant HS as HistoryService

    Client->>CS: mergeContacts(keepId, duplicateId)
    CS->>Lock: lock()
    CS->>Repo: findById(keepId), findById(duplicateId)
    Repo-->>CS: keep, duplicate
    CS->>Merger: merge(keep, duplicate)
    Merger-->>CS: merged (unioned phones/emails/addresses/groups)
    CS->>Repo: compareAndSet(keepId, keep, merged)
    Repo-->>CS: true
    CS->>HS: record(keepId, keep)
    CS->>Repo: delete(duplicateId)
    Repo-->>CS: removed (duplicate)
    CS->>Lock: unlock()
    CS-->>Client: MergeResult(merged, keep, duplicate)

    opt undo
        Client->>CS: undoMerge(result)
        CS->>Repo: save(result.preMergeKeepState())
        CS->>Repo: save(result.removedContact())
    end
```

## 4. Duplicate candidate generation (indexed blocking, not O(n^2))

```mermaid
sequenceDiagram
    actor Client
    participant DDS as DuplicateDetectionService
    participant IM as IndexManager
    participant Repo as InMemoryContactRepository
    participant Strat as DuplicateMatchStrategy

    Client->>DDS: findPotentialDuplicates(target)
    loop each phone/email/name token of target
        DDS->>IM: findByNormalizedPhone / findByNormalizedEmail / searchByNamePrefix
        IM-->>DDS: candidate ids (O(1) / O(|token|))
    end
    DDS->>DDS: union candidate ids, remove target.id()
    loop each small candidate id set
        DDS->>Repo: findById(candidateId)
        Repo-->>DDS: candidate
        DDS->>Strat: isDuplicate(target, candidate)
        Strat-->>DDS: true/false
    end
    DDS-->>Client: matching candidates
```

## 5. Undo the most recent edit (Memento)

```mermaid
sequenceDiagram
    actor Client
    participant CS as ContactService
    participant HS as HistoryService
    participant Repo as InMemoryContactRepository

    Client->>CS: undo(id)
    CS->>HS: undo(id)
    HS-->>CS: previous snapshot (or empty)
    CS->>Repo: save(previous.toBuilder().updatedAt(now).build())
    Repo-->>CS: (void, fires UPDATED event -> reindex)
    CS-->>Client: restored contact
```
