# Sequence Diagrams

## 1. Upload a new file (quota reserve -> dedup -> structural commit)

```mermaid
sequenceDiagram
    actor U as User
    participant DS as DriveService
    participant PERM as PermissionService
    participant QM as QuotaManager
    participant BS as BlobStore
    participant NR as NodeRepository
    participant SRCH as SearchService

    U->>DS: createFile(userId, parentId, name, content)
    DS->>DS: structuralLock.readLock()
    DS->>PERM: requireRole(parent, userId, EDITOR)
    PERM-->>DS: ok (or throws AccessDeniedException)
    DS->>DS: check parent.hasChildNamed(name)
    DS->>DS: structuralLock.readUnlock()
    DS->>QM: reserve(userId, content.length)
    Note right of QM: CAS loop: atomic check-then-reserve,<br/>throws QuotaExceededException if over limit
    QM-->>DS: ok
    DS->>BS: storeAndRetain(content)
    Note right of BS: ConcurrentHashMap.compute(hash):<br/>dedupe or create with refCount=1
    BS-->>DS: Blob
    DS->>DS: structuralLock.writeLock()
    DS->>DS: re-check hasChildNamed (race since read-unlock)
    alt name taken by a racing writer
        DS->>QM: release(userId, content.length)
        DS->>BS: release(hash)
        DS-->>U: throw DuplicateNameException
    else still free
        DS->>NR: save(new File + version 1)
        DS->>SRCH: indexNode(file)
        DS->>DS: structuralLock.writeUnlock()
        DS-->>U: File
    end
```

## 2. Upload a new version of an existing file (Memento)

```mermaid
sequenceDiagram
    actor U as User
    participant DS as DriveService
    participant PERM as PermissionService
    participant QM as QuotaManager
    participant BS as BlobStore
    participant F as File (per-file monitor)

    U->>DS: uploadNewVersion(userId, fileId, content)
    DS->>DS: structuralLock.readLock() [resolve node]
    DS->>PERM: requireRole(file, userId, EDITOR)
    DS->>DS: structuralLock.readUnlock()
    DS->>QM: reserve(userId, content.length)
    QM-->>DS: ok
    DS->>BS: storeAndRetain(content)
    BS-->>DS: Blob (new or deduped)
    DS->>F: synchronized(file) { addVersion(new FileVersion) }
    Note right of F: Prior FileVersion objects are untouched;<br/>any thread holding one keeps reading it safely.
    DS-->>U: FileVersion
```

## 3. Permission check with inheritance (share a folder, access a nested file)

```mermaid
sequenceDiagram
    actor Bob
    participant DS as DriveService
    participant PERM as PermissionService
    participant STRAT as InheritedAclPermissionStrategy

    Note over Bob,STRAT: Alice already ran: share(folder, Bob, VIEWER)
    Bob->>DS: downloadLatest(bobId, nestedFileId)
    DS->>PERM: requireRole(file, bobId, VIEWER)
    PERM->>STRAT: resolveEffectiveRole(file, bobId)
    STRAT->>STRAT: file.ownerId == bobId? no
    STRAT->>STRAT: file.aclEntries has bobId? no
    STRAT->>STRAT: walk to file.parent (the shared folder)
    STRAT->>STRAT: folder.aclEntries has bobId? yes -> VIEWER
    STRAT-->>PERM: VIEWER
    PERM-->>DS: VIEWER.isAtLeast(VIEWER) == true
    DS-->>Bob: file content bytes
```

## 4. Trash -> permanent purge (reference-counted blob release + quota release)

```mermaid
sequenceDiagram
    actor U as User
    participant DS as DriveService
    participant TS as TrashService
    participant BS as BlobStore
    participant QM as QuotaManager
    participant NR as NodeRepository

    U->>DS: moveToTrash(userId, nodeId)
    DS->>DS: structuralLock.writeLock()
    DS->>DS: detach node from parent.children
    DS->>TS: recordTrashed(node, oldParent, userId, now)
    DS->>DS: structuralLock.writeUnlock()

    Note over U,NR: ... retention window elapses, or owner purges explicitly ...

    U->>DS: purgePermanently(userId, nodeId)
    DS->>DS: structuralLock.writeLock()
    DS->>TS: purgePermanently(node)
    loop for each file version in node (and descendants if folder)
        TS->>BS: release(version.contentHash)
        Note right of BS: compute(hash): decrement refCount;<br/>remove mapping only if it reaches 0
        TS->>QM: release(version.ownerId, version.sizeBytes)
    end
    TS->>NR: deleteById(node.id) [+ each descendant]
    DS->>DS: structuralLock.writeUnlock()
    DS-->>U: done
```
