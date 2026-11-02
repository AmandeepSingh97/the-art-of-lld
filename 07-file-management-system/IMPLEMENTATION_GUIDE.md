# Implementation Guide — In-Memory File Management System

## Which classes to write first

1. `FileSystemEntry` (abstract) — id, name, parent, timestamps, `isDirectory()`, `size()`,
   `isAncestorOf()`.
2. `File` — content + its own `ReentrantReadWriteLock`.
3. `Directory` — children `LinkedHashMap` + package-private mutators.
4. `PathResolver` — stateless path-string math (`.`/`..`/absolute/relative).
5. `Session` — cwd pointer, `AtomicReference`.
6. `FileSystem` — the facade; write this *after* 1–5 exist and are individually sensible, since
   every method here composes them.
7. Exception hierarchy (`FileSystemException` + 6 subtypes) — can be written any time before
   `FileSystem`, since `FileSystem` throws them.
8. `Command`/`CommandResult`/`Shell` + one class per verb — last, since these are thin wrappers
   over an already-working `FileSystem`.

## Minimal compilable implementation outline

```java
abstract class FileSystemEntry {
    private final long id;
    private volatile String name;
    private volatile Directory parent;
    abstract boolean isDirectory();
    abstract long size();
}

final class File extends FileSystemEntry {
    private byte[] content; // + its own ReadWriteLock
    boolean isDirectory() { return false; }
}

final class Directory extends FileSystemEntry {
    private final Map<String, FileSystemEntry> children = new LinkedHashMap<>();
    boolean isDirectory() { return true; }
}

final class FileSystem {
    private final Directory root;
    private final ReadWriteLock treeLock;

    Directory mkdir(Session s, String path, boolean createParents) { ... }
    void cd(Session s, String path) { ... }
    String pwd(Session s) { ... }
    List<FileSystemEntry> ls(Session s, String path) { ... }
    File touch(Session s, String path) { ... }
    String cat(Session s, String path) { ... }
    void write(Session s, String path, String content, boolean append) { ... }
    void rm(Session s, String path, boolean recursive, boolean force) { ... }
    void cp(Session s, String src, String dest, boolean recursive, boolean overwrite) { ... }
    void mv(Session s, String src, String dest, boolean overwrite) { ... }
}
```

This is enough structure to compile and start filling in method bodies incrementally — get
`mkdir`/`cd`/`pwd`/`ls` working and tested before touching `cp`/`mv`.

## Suggested implementation sequence (with rough time budget for a 45-min round)

1. (5 min) `FileSystemEntry`/`File`/`Directory` skeletons, no locking yet.
2. (5 min) `PathResolver.normalize` + `resolveToAbsoluteSegments` — test with a few `System.out`
   calls or a quick unit test before wiring into `FileSystem`.
3. (10 min) `FileSystem` constructor + `mkdir`/`cd`/`pwd`/`ls`, single-threaded, no lock yet (add
   the lock once this works, so you're not debugging locking and logic simultaneously).
4. (5 min) `touch`/`cat`/`write`.
5. (5 min) `rm` (non-recursive, then `-r`).
6. (10 min) `cp` then `mv`, including the cycle check — save these for last, they're hardest.
7. (5 min, if time remains) add `treeLock` acquisition around every method; state the strategy
   verbally if out of time to code it.

## Important methods (the ones worth getting exactly right)

- `PathResolver.normalize(String path)` — the one piece of logic every other method depends on;
  a bug here silently breaks everything downstream.
- `FileSystem.mv` — specifically the cycle check
  (`source.isAncestorOf(effectiveDestParent)`) *before* any mutation.
- `FileSystemEntry.isAncestorOf` — a simple `while (cursor != null) { if (cursor == this) return
  true; cursor = cursor.getParent(); }` loop; easy to get backwards (walking the wrong direction)
  under interview pressure — double check which side walks up.
- `Directory.listChildren()` — must return a defensive copy (`new ArrayList<>(children.values())`),
  not the live map's view, so callers can safely iterate after a lock is released.

## Common coding mistakes (and how to avoid them)

- **Forgetting to validate the destination doesn't already exist before mutating, in `cp`/`mv`.**
  Always do all existence/collision checks *first*, mutate *last* — otherwise a thrown exception
  can leave a half-completed operation.
- **Checking the cycle condition in `mv` with the arguments backwards.** It's "is `source` an
  ancestor of the *destination*", not the other way around — write a one-line comment above the
  check while coding it to keep this straight.
- **Mutating `children` map while iterating it directly** (e.g. `for (var e : dir.children())`
  then calling `dir.removeChild(...)` inside the loop) — causes `ConcurrentModificationException`.
  Always snapshot with `listChildren()` first if you need to mutate during/after iteration.
- **Splitting a path into segments without handling a trailing slash or double slash** (`"/a//b/"`)
  — test this explicitly; a naive `path.split("/")` without filtering empty strings breaks on it.
- **Forgetting that `touch` on an existing file should be a no-op, not an error** — only throw if
  the existing entry is a directory.
- **Holding a lock across a recursive call without being sure it's reentrant-safe** — if using
  `ReentrantReadWriteLock`, remember a thread holding the *write* lock cannot also acquire the
  *read* lock on the same lock instance within the same call without being blocked indefinitely in
  some lock implementations' semantics around read/write lock reentrancy — keep operations using
  one lock mode consistently per call tree.

## Edge cases (make sure you mention these even if you don't have time to code all of them)

- Moving/copying a directory into itself or its own descendant.
- `cp`/`mv` onto an existing file/directory without an overwrite flag.
- `rm` on a non-empty directory without `-r`.
- `cd`/`ls` through a path where an intermediate segment is a file, not a directory.
- `..` beyond root (clamp to root, don't error).
- Removing/moving the current working directory or one of its ancestors.
- Concurrent structural mutation of the same subtree from two threads (the rm-vs-cp race).
- Empty path segments from double slashes, and `.`/empty-string segments.

## How to narrate the code while writing it

- Say what invariant you're protecting *before* writing the check, not after: "Before I do the
  actual move, I need to make sure the destination isn't inside the source — otherwise I'd create
  a cycle. Let me write that check first, then the mutation."
- When you make a trade-off, say it out loud in one sentence, not silently: "I'm doing all my
  validation before any mutation here, specifically so a thrown exception never leaves a partial
  move."
- When skipping something for time, say what and why: "I'm not implementing the `-p` flag's full
  chain-creation logic right now, I'll stub it to only handle one missing parent level and note
  that as a known gap."

## What can be safely omitted under time pressure

- `-p`/`-f`/`-a` flags (implement bare commands first).
- The `Command`/`Shell` layer entirely — a direct `FileSystem` API with a hard-coded demo
  (`fs.mkdir(...)`, `fs.cp(...)`) in a `main` method proves the same design knowledge without the
  extra wrapper layer, and is a reasonable thing to say out loud: "I'll skip the command-pattern
  wrapper and just call FileSystem directly from main, to spend my remaining time on cp/mv/rm
  correctness instead."
- Full concurrency *implementation* (locks everywhere) — verbally describing the two-tier locking
  strategy and why it's correct for the rm-vs-cp race is an acceptable substitute if time runs out
  before you can add the actual `ReentrantReadWriteLock` calls.
- Directory-merge semantics on `mv`/`cp` overwrite (just require the destination to be absent or
  empty-and-replaceable).

## A simplified version implementable in 20–30 minutes

Scope: single-threaded, no `Session`/`Shell`/`Command` layer, no flags.

```java
class FsNode {
    String name;
    FsNode parent;
    boolean isDir;
    Map<String, FsNode> children; // null if file
    String content;              // null if directory
}

class SimpleFileSystem {
    FsNode root = new FsNode("/", null, true, new LinkedHashMap<>(), null);
    FsNode cwd = root;

    void mkdir(String path) {
        List<String> segs = normalize(path);
        FsNode cur = path.startsWith("/") ? root : cwd;
        for (String s : segs) {
            cur.children.putIfAbsent(s, new FsNode(s, cur, true, new LinkedHashMap<>(), null));
            cur = cur.children.get(s);
        }
    }

    void cd(String path) { cwd = resolveDir(path); }
    String pwd() { /* walk cwd.parent chain to build "/a/b/c" */ }
    List<String> ls(String path) { return new ArrayList<>(resolveDir(path).children.keySet()); }

    void rm(String path, boolean recursive) {
        FsNode node = resolve(path);
        if (node.isDir && !node.children.isEmpty() && !recursive) throw new RuntimeException("not empty");
        node.parent.children.remove(node.name);
    }

    void mv(String src, String dest) {
        FsNode node = resolve(src);
        FsNode destParent = resolveParentFor(dest);
        if (node.isDir && isAncestor(node, destParent)) throw new RuntimeException("cycle");
        node.parent.children.remove(node.name);
        node.parent = destParent;
        node.name = lastSegment(dest);
        destParent.children.put(node.name, node);
    }

    // cp omitted for brevity in this 20-min scope — state verbally: "cp is mv plus a deep-copy
    // of content/children instead of re-parenting the same node."
}
```

This captures the core tree + path-walk + cycle-check ideas (the parts an interviewer is actually
grading) without the full locking/Command-pattern ceremony — exactly what's appropriate to produce
if explicitly told you have 20–30 minutes total.
