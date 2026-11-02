# Sequence Diagrams

## 1. `mkdir -p /home/user/docs` (path resolution + structural write lock)

```mermaid
sequenceDiagram
    actor User
    participant Shell
    participant MkdirCommand
    participant FileSystem
    participant PathResolver
    participant Directory as Directory(root..docs)

    User->>Shell: execute("mkdir -p /home/user/docs")
    Shell->>MkdirCommand: execute(fs, session, ["-p","/home/user/docs"])
    MkdirCommand->>FileSystem: mkdir(session, "/home/user/docs", true)
    FileSystem->>FileSystem: treeLock.writeLock().lock()
    FileSystem->>PathResolver: resolveToAbsoluteSegments(path, cwdSegments)
    PathResolver-->>FileSystem: [home, user, docs]
    loop for each segment
        FileSystem->>Directory: getChild(segment)
        alt missing and createParents
            FileSystem->>Directory: new Directory(segment, cursor); putChild(...)
        else exists and is a Directory
            FileSystem->>Directory: cursor = existing
        else exists and is a File
            FileSystem-->>MkdirCommand: throw NotADirectoryException
        end
    end
    FileSystem->>FileSystem: treeLock.writeLock().unlock()
    FileSystem-->>MkdirCommand: Directory(docs)
    MkdirCommand-->>Shell: CommandResult.ok()
    Shell-->>User: success
```

## 2. `cp -r /src /dest` (recursive copy, whole duration under one write lock)

```mermaid
sequenceDiagram
    actor User
    participant CpCommand
    participant FileSystem
    participant SrcTree as /src subtree
    participant DestParent as destination parent Directory

    User->>CpCommand: cp -r /src /dest
    CpCommand->>FileSystem: cp(session, "/src", "/dest", true, false)
    FileSystem->>FileSystem: treeLock.writeLock().lock()
    FileSystem->>FileSystem: resolve source "/src" (must exist)
    FileSystem->>FileSystem: resolve dest parent "/" , name "dest"
    FileSystem->>DestParent: getChild("dest")
    DestParent-->>FileSystem: null (no collision)
    FileSystem->>FileSystem: deepCopy(source, "dest", destParent)
    activate FileSystem
    FileSystem->>SrcTree: listChildren() [snapshot]
    loop each child
        FileSystem->>FileSystem: deepCopy(child, child.name, newDir) [recursive]
    end
    deactivate FileSystem
    FileSystem->>DestParent: putChild(copiedDirectory)
    FileSystem->>FileSystem: treeLock.writeLock().unlock()
    FileSystem-->>CpCommand: void (success)
    CpCommand-->>User: CommandResult.ok()
```

## 3. Concurrent `rm -r /x` vs `cp -r /x /dest` (the motivating race)

```mermaid
sequenceDiagram
    participant ThreadA as Thread A (rm -r /x)
    participant ThreadB as Thread B (cp -r /x /dest)
    participant Lock as FileSystem.treeLock (write)
    participant Tree as Shared tree

    par Both threads call in
        ThreadA->>Lock: writeLock().lock()
        ThreadB->>Lock: writeLock().lock()
    end
    Note over Lock: Exclusive write lock: only one thread proceeds past this point at a time.
    Lock-->>ThreadA: acquired (A arrived first)
    ThreadA->>Tree: remove /x and everything under it
    ThreadA->>Lock: writeLock().unlock()
    Lock-->>ThreadB: acquired (B was blocked until now)
    ThreadB->>Tree: resolve "/x" for copy
    Tree-->>ThreadB: PathNotFoundException ("/x" no longer exists)
    ThreadB->>Lock: writeLock().unlock()
    ThreadB-->>ThreadB: cp fails cleanly, no partial copy created
```

**Why no interleaving produces corruption:** because both operations hold the *same* write lock
for their *entire* duration (not re-acquired per node), there is no point at which Thread B can
observe `/x` half-deleted, and no point at which Thread A can observe a half-created copy that it
needs to also delete. The two operations are fully serialized with respect to each other, which
is what "linearizable" means here -- see `FileSystem`'s class javadoc and
`STAFF_LEVEL_DEEP_DIVE.md` for the trade-offs of this choice versus finer-grained locking.
