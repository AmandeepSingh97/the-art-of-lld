# File Management System (In-Memory `cp`, `mv`, `mkdir`, `pwd`, `ls`, `rm`)

A Staff-level low-level design of an in-memory, hierarchical file system -- the data structures
and algorithms behind a Unix-like shell's core verbs, built as a library (not a real OS/VFS), in
idiomatic Java 17+.

Source: [`src/main/java/lld/filesystem`](src/main/java/lld/filesystem) · Tests:
[`tests/lld/filesystem`](tests/lld/filesystem) · Diagrams: [`diagrams/`](diagrams)

---

## 1. Problem Understanding

**Plain explanation.** Design an in-memory file system that supports the core POSIX-like shell
verbs: `mkdir`, `cd`, `pwd`, `ls`, `touch`, `cat`, `write`, `cp`, `mv`, `rm`. The system models a
tree of directories and files held entirely in process memory (no disk, no OS syscalls), exposed
either as a direct Java API or through a tiny shell/REPL that parses command lines.

**Real-world use case.** This is the data-structure core of: an actual shell's VFS layer, an
in-memory test double for a real file system (so higher-level code can be unit-tested without
touching disk), a "virtual drive" feature in a web IDE or sandboxed code runner (e.g. an online
coding judge's scratch file system), or a config-tree / resource-tree abstraction (many internal
tools model hierarchical config as a "file system" because the mental model is so well
understood).

**What the interviewer evaluates.**
- Can you model a recursive tree with a clean Composite pattern (not a flat path-string map
  that fakes hierarchy)?
- Do you get path resolution right (absolute vs. relative, `.`, `..`, trailing slashes) without
  it turning into spaghetti string parsing?
- Do you handle the two genuinely hard correctness problems: **name collisions** (what does
  `cp`/`mv` do when the destination already exists?) and **cycle prevention** (can you move a
  directory into its own descendant)?
- Do you have a real opinion on concurrency, not just "I'd add a lock somewhere"?
- Do you resist over-engineering (no need for inodes, block allocation, journaling, permissions
  bits unless asked) while still being extensible.

**In-scope.**
- In-memory tree of `File` and `Directory` nodes (Composite pattern).
- Path resolution: absolute (`/a/b`), relative (`a/b`, `../a`), `.` and `..`, multiple slashes.
- Per-session current working directory (multiple independent "shells" over one shared tree).
- Commands: `mkdir [-p]`, `cd`, `pwd`, `ls`, `touch`, `cat`, `write [-a]`, `cp [-r] [-f]`,
  `mv [-f]`, `rm [-r] [-f]`, modeled as a Command pattern.
- Recursive copy/move semantics, name-collision handling (`FileAlreadyExistsException` vs.
  overwrite flag), cycle prevention for `mv`.
- Thread-safety for a shared, multi-session, concurrently-mutated tree.

**Out-of-scope** (explicitly called out, see Production Evolution for where these would go):
- Real disk/byte persistence, journaling, crash recovery of on-disk state.
- POSIX permission bits / ACLs / multi-user ownership (sketched as an optional extension only).
- Symbolic/hard links, mount points, quotas.
- Distributed/sharded file system (this is a single-process LLD problem, not a distributed
  systems one -- see "Performance and Scalability" for why we explicitly do not distribute this).

---

## 2. Requirement Gathering

### Functional

**Essential**
- `mkdir <path>` (optionally `-p` to create missing parents).
- `cd <path>`, `pwd` — per-session current working directory.
- `ls [path]` — list immediate children (defaults to cwd).
- `touch <path>` — create an empty file (no-op if it already exists as a file).
- `cat <path>` — read a file's content.
- `write [-a] <path> <content>` — overwrite or append file content.
- `cp [-r] <src> <dest>` — copy a file, or recursively copy a directory.
- `mv <src> <dest>` — move/rename a file or directory.
- `rm [-r] [-f] <path>` — remove a file, or recursively remove a directory.

**Optional / nice-to-have**
- `-f` force flags on `cp`/`mv`/`rm` (overwrite destination / suppress missing-path errors).
- Timestamps (`createdAt`/`modifiedAt`) per entry.
- Multiple independent sessions sharing one tree (modeled as `Session`).
- Permission bits (sketched, not fully implemented — see Domain Modelling §Extensions).

### Non-functional

| Concern | Requirement | How addressed |
|---|---|---|
| Performance | Path resolution and single-node ops should be O(depth), not O(tree size) | `Directory` backed by `HashMap`-like `LinkedHashMap` → O(1) average per-segment lookup |
| Scalability | Must support deep/wide trees without quadratic blowup | Recursive algorithms are linear in subtree size touched, not whole-tree size |
| Availability | N/A for single-process in-memory structure (see §12) | — |
| Consistency | A structural operation (mkdir/mv/cp/rm) must appear atomic to other threads | Single write lock held for the operation's entire duration (§11) |
| Extensibility | New verbs should not require touching existing code | Command pattern — `Shell` dispatches by name, new `Command` implementations plug in |
| Reliability | No operation should leave the tree in a partially-mutated, inconsistent state | Each mutating method either fully succeeds or throws before any mutation (mkdir/touch/write), or completes its whole recursive walk under one lock (cp/mv/rm) |
| Thread-safety | Concurrent sessions must not corrupt shared tree state | Documented, justified locking strategy — see §11 |

**Interview-necessary vs. production-extension split:** the essential functional list plus
single-process thread-safety is the interview bar. Permissions, persistence, and distribution are
production extensions explicitly deferred to §17.

---

## 3. Clarifying Questions

Documented here are the assumptions made because this exercise ran autonomously; in a live
interview each of these should be asked up front, as they materially change the design.

1. **Is this in-memory only, or does it need to persist to disk/DB?**
   *Why it matters:* persistence changes the storage layer entirely (see §12) and affects whether
   `File` content can be a simple `byte[]` or needs chunked/streamed storage.
   *Assumption made:* pure in-memory; explained in §12 why no DB is needed.

2. **Single-threaded CLI, or a shared tree accessed by multiple concurrent sessions/threads?**
   *Why it matters:* this is the single biggest driver of design complexity (§11). A
   single-threaded CLI needs zero locking.
   *Assumption made:* multiple concurrent sessions over one shared `FileSystem`, because the
   prompt explicitly asks for concurrent-mutation discussion.

3. **Do `cp`/`mv` need POSIX-exact semantics (e.g. "copy into existing dir vs. overwrite
   existing dir"), or can we define our own simplified rules?**
   *Why it matters:* POSIX's actual rules for `cp`/`mv` targeting an existing directory vs. an
   existing file are famously fiddly (different tools even disagree).
   *Assumption made:* POSIX-like but explicitly simplified and documented per-method (e.g.
   `cp src dest` where `dest` is an existing directory copies *into* it, matching common
   shell intuition); divergences are called out in the Javadoc of `FileSystem`.

4. **Should `rm` default to interactive confirmation / trash semantics, or immediate deletion?**
   *Why it matters:* a "soft delete with undo" is a materially different feature (needs a
   recycle-bin structure, retention policy).
   *Assumption made:* immediate, irreversible deletion, consistent with a library API (not an
   interactive terminal), with `-f` only suppressing the "missing path" error.

5. **Are symbolic/hard links in scope?**
   *Why it matters:* links turn the tree into a general graph, which breaks the simple recursive
   `isAncestorOf` cycle check and requires explicit cycle detection via visited-sets.
   *Assumption made:* out of scope; the tree stays a strict tree (every node has exactly one
   parent), which is what makes cycle prevention for `mv` a simple ancestor-walk.

6. **Does every session get its own isolated file system, or do they share one tree (like
   multiple terminals on one real machine)?**
   *Why it matters:* drives whether `Session` needs to reference a shared `FileSystem` (our
   design) or own a private copy.
   *Assumption made:* shared tree, independent cwd per session — mirrors real shells.

7. **What should happen to open file handles / the cwd of other sessions if a directory they
   depend on is deleted concurrently?**
   *Why it matters:* a naive design can leave a session's cwd pointing at a detached, orphaned
   subtree.
   *Assumption made:* `rm`/`mv` explicitly refuse to remove/move an ancestor of the *calling*
   session's own cwd (cheap, local check); a *different* session's cwd can still end up pointing
   at a detached subtree (documented as an accepted limitation in §13, with the production fix
   sketched in §17).

8. **Is a single root (`/`) sufficient, or do we need multiple roots / drive letters?**
   *Assumption made:* single root, consistent with "Unix-like", not Windows-drive-like.

---

## 4. Domain Modelling

| Type | Kind | Responsibility |
|---|---|---|
| `FileSystemEntry` | Abstract class | Common identity (`id`, `name`, `parent`, timestamps) and the `isAncestorOf` cycle-check shared by files and directories. |
| `File` | Concrete class (leaf) | Holds byte content; owns its own content lock, independent of tree structure. |
| `Directory` | Concrete class (composite) | Holds named children in a `LinkedHashMap`; exposes only package-private mutators so `FileSystem` is the sole caller under lock. |
| `Session` | Value/state holder | One caller's current working directory pointer into a shared `FileSystem`. |
| `PathResolver` | Stateless utility | Converts path strings (absolute/relative, `.`/`..`) into normalized absolute segment lists. No file-system access. |
| `FileSystem` | Facade / domain service | The only class that touches the tree lock; owns `root`; exposes `mkdir/cd/pwd/ls/touch/cat/write/cp/mv/rm`. |
| `Command` (interface) | Strategy/Command | One object per verb; converts shell args into a `FileSystem` call. |
| `CommandResult` | Value object | Immutable success/output or failure/error-message pair. |
| `Shell` | Command invoker | Tokenizes a command line, looks up the `Command` by name, converts `FileSystemException` into a failed `CommandResult`. |
| `FileSystemException` + subtypes | Exceptions | `PathNotFoundException`, `NotADirectoryException`, `NotAFileException`, `FileAlreadyExistsException`, `DirectoryNotEmptyException`, `InvalidOperationException`. |

**Composition / association / inheritance:**
- `FileSystemEntry` is the Composite base; `File` and `Directory` both extend it
  (inheritance for the "is-a node in the tree" relationship — this is the one place inheritance
  genuinely earns its keep, because `size()`/`isDirectory()` are naturally polymorphic and every
  consumer of the tree wants to treat files and directories uniformly).
- `Directory` **composes** `FileSystemEntry` children (strong ownership: a child's lifetime is
  tied to its parent directory; deleting a directory recursively deletes its children — this is
  composition, not aggregation).
- `FileSystem` **composes** the single `root` `Directory` (owns its lifecycle) and **associates**
  with zero or more `Session`s (a `Session` can outlive or be discarded independently of
  `FileSystem`; `FileSystem` does not own sessions' lifecycles, it only serves them).
- `Shell` **associates** with one `FileSystem` and one `Session`, and **aggregates** a registry of
  `Command` objects (commands are stateless/shareable; `Shell` doesn't own their lifecycle in any
  meaningful sense beyond holding references).

**Ownership / lifecycle:** `FileSystemEntry.id` is assigned once, at construction, by a process
of elimination the single source of a stable, unique, immutable identity — this is what the
lock-ordering story and equality rely on. A node's `parent` reference changes over its lifetime
(on `mv`); its `id` never does.

**Why no `Repository`/`Factory` abstraction:** a `Repository` would imply a pluggable storage
backend (DB, disk, memory) behind a common interface; since this problem is explicitly in-memory
only (§3 Q1), introducing a `Repository` interface with one implementation is premature
abstraction. `FileSystem` *is* the repository, by a different, more domain-appropriate name. If a
second backend (disk-backed) is added later, extracting an interface at that point is a cheap,
well-motivated refactor — not something to speculatively build now.

---

## 5. Domain Invariants

Rules that must hold at every observable point (i.e. after every public `FileSystem` method
returns, not necessarily mid-method):

1. **Single-parent tree.** Every non-root `FileSystemEntry` has exactly one `parent`, and that
   parent's children map contains it under its current `name`. *Enforced by:* `mv`/`cp`/`rm`
   always updating both sides (`Directory.putChild`/`removeChild` and `entry.setParent`) inside
   the same critical section.
2. **No cycles.** No directory can be its own ancestor. *Enforced by:* `mv`'s explicit
   `source.isAncestorOf(effectiveDestParent)` check performed under the write lock before any
   mutation.
3. **Name uniqueness within a directory.** A directory cannot have two children with the same
   name. *Enforced by:* `Map<String, FileSystemEntry>` keyed by name; collisions are detected
   before insertion and either rejected (`FileAlreadyExistsException`) or explicitly resolved
   (overwrite flag) — never silently clobbered.
4. **Valid names.** A name is never empty, `.`, `..`, or contains `/`. *Enforced by:*
   `FileSystemEntry.validateName`, called from every constructor and rename path (except the
   root sentinel, which is exempted by construction, not user input).
5. **Root is unremovable and unmovable.** *Enforced by:* explicit checks in `rm`
   (`absolute.isEmpty()`) and `mv` (`source.getParent() == null`).
6. **A session's own cwd is never left dangling by that session's own operation.**
   *Enforced by:* `rm`/`mv` refuse to remove/move an ancestor of the calling session's cwd.
7. **File content read/write never observes a torn write.** *Enforced by:* `File`'s own
   `ReentrantReadWriteLock` guarding the `content` field; writers hold the exclusive lock for the
   whole array-copy-and-swap.

---

## 6. Architecture and Class Diagram

Component diagram: [`diagrams/component-diagram.md`](diagrams/component-diagram.md)
Class diagram: [`diagrams/class-diagram.md`](diagrams/class-diagram.md)
Sequence diagrams (mkdir, recursive cp, concurrent rm-vs-cp race):
[`diagrams/sequence-diagrams.md`](diagrams/sequence-diagrams.md)

These diagrams are generated to match the actual class/method signatures in
[`src/main/java/lld/filesystem`](src/main/java/lld/filesystem) — verified by compiling the
source with `javac` (see §15 / final verification).

---

## 7. Design Patterns

| Pattern | Why needed | Where applied | Alternative considered | Why rejected |
|---|---|---|---|---|
| **Composite** | Files and directories must be treated uniformly for `size()`, identity, and tree-walking, while directories recursively contain more nodes. | `FileSystemEntry` (component) ← `File` (leaf), `Directory` (composite) | A single `Entry` class with a `boolean isDirectory` flag and a nullable children map. | Works but every consumer must branch on the flag; polymorphic `size()`/`isDirectory()` is the textbook motivation for Composite and keeps `File` and `Directory` each cohesive and small. |
| **Command** | Need to add shell verbs (`mkdir`, `cp`, ...) without a growing `switch` statement, and need each verb to be independently unit-testable. | `Command` interface + one class per verb, dispatched by `Shell` | A single `Shell.execute(String)` with a big `switch(verb)` calling `FileSystem` methods directly. | The switch works for 10 verbs but violates Open/Closed (every new verb edits the switch) and makes verb-specific flag-parsing logic (`-r`, `-f`, `-p`, `-a`) harder to unit test in isolation. |
| **Facade** | Callers (commands, tests, a future GUI) should not need to know about locking policy, path normalization, or the tree internals. | `FileSystem` is the sole public entry point into the tree; `Directory`/`File` mutators are package-private. | Expose `Directory`/`File` directly and let callers coordinate locking themselves. | Would make "every mutation is locked correctly" a convention instead of an invariant enforced by the type system/package boundary — a correctness risk not worth the marginal flexibility. |
| **Strategy (implicit)** | `PathResolver` encapsulates one algorithm (textual path normalization) independent of the data it operates on. | `PathResolver.normalize/resolveToAbsoluteSegments/splitParentAndName` | Inline the path-splitting logic into `FileSystem` methods. | Keeping it a separate, stateless, directly-unit-testable utility avoids duplicating `..`/`.`-handling logic across six different `FileSystem` methods and keeps `FileSystem` focused on tree mutation + locking. |

**Patterns deliberately *not* used:**
- **Visitor** for tree traversal was considered (e.g. for a future `find`/`du` command) but
  rejected for now: with only `size()` and `deepCopy` as recursive operations, a Visitor adds
  indirection without a second traversal algorithm to justify the abstraction. If a third or
  fourth tree-walking operation is added (e.g. `find`, `du`, `chmod -R`), introducing a
  `Visitor<R>` interface at that point is the right call — see §17.
- **Singleton** for `FileSystem` was rejected: nothing in the problem statement requires exactly
  one file system per JVM, and a singleton would make the class impossible to unit test in
  isolation (tests create a fresh `new FileSystem()` per test, which is exactly why 51 tests can
  run independently and in parallel without shared state).

---

## 8. API and Method Design

### `lld.filesystem.FileSystem` (core public API)

```java
public final class FileSystem {
    public Session newSession();

    public String pwd(Session session);
    public void cd(Session session, String path);                                   // throws PathNotFoundException, NotADirectoryException
    public List<FileSystemEntry> ls(Session session, String path);                   // throws PathNotFoundException, NotADirectoryException

    public Directory mkdir(Session session, String path, boolean createParents);     // throws PathNotFoundException, NotADirectoryException, FileAlreadyExistsException
    public File touch(Session session, String path);                                 // throws NotAFileException (path is an existing dir), PathNotFoundException (missing parent)
    public String cat(Session session, String path);                                 // throws PathNotFoundException, NotAFileException
    public void write(Session session, String path, String content, boolean append); // throws NotAFileException

    public void rm(Session session, String path, boolean recursive, boolean force);           // throws PathNotFoundException, DirectoryNotEmptyException, InvalidOperationException
    public void cp(Session session, String src, String dest, boolean recursive, boolean overwrite); // throws PathNotFoundException, InvalidOperationException, FileAlreadyExistsException
    public void mv(Session session, String src, String dest, boolean overwrite);              // throws PathNotFoundException, InvalidOperationException, FileAlreadyExistsException, DirectoryNotEmptyException
}
```

**Validation responsibilities:**
- `FileSystemEntry.validateName` rejects malformed names (`""`, `.`, `..`, containing `/`) at
  construction/rename time — the one place this check needs to live.
- `PathResolver` is purely textual: it does not validate existence; `FileSystem` is responsible
  for turning "segment not found" into the right typed exception (`PathNotFoundException` for a
  genuinely missing node, `NotADirectoryException` when an intermediate segment resolves to a
  file).
- All exceptions are unchecked (`FileSystemException extends RuntimeException`) — see
  `FileSystemException`'s javadoc for the rationale (every command can fail for many independent
  reasons; checked exceptions would force every signature to declare all of them).

### `lld.filesystem.command.Command` (extension point)

```java
public interface Command {
    String name();
    CommandResult execute(FileSystem fileSystem, Session session, String[] args);
}
```

`CommandResult` is an immutable value object with `ok(String)`, `ok()`, `failure(String)`
factories and `isSuccess()/getOutput()/getErrorMessage()` accessors — no exceptions cross the
`Shell.execute(String)` boundary.

---

## 9. Core Implementation

Full, compiling source lives under
[`src/main/java/lld/filesystem`](src/main/java/lld/filesystem):

- [`FileSystemEntry.java`](src/main/java/lld/filesystem/FileSystemEntry.java) — Composite base,
  identity, `isAncestorOf`.
- [`File.java`](src/main/java/lld/filesystem/File.java) — leaf node, own content lock.
- [`Directory.java`](src/main/java/lld/filesystem/Directory.java) — composite node, children map.
- [`PathResolver.java`](src/main/java/lld/filesystem/PathResolver.java) — path string ↔ segments.
- [`Session.java`](src/main/java/lld/filesystem/Session.java) — per-caller cwd.
- [`FileSystem.java`](src/main/java/lld/filesystem/FileSystem.java) — the facade; **read this
  file's class javadoc first**, it documents and justifies the entire locking strategy referenced
  throughout this README.
- [`command/Command.java`](src/main/java/lld/filesystem/command/Command.java),
  [`CommandResult.java`](src/main/java/lld/filesystem/command/CommandResult.java),
  [`Shell.java`](src/main/java/lld/filesystem/command/Shell.java), and one class per verb
  (`MkdirCommand`, `CdCommand`, `PwdCommand`, `LsCommand`, `TouchCommand`, `CatCommand`,
  `WriteCommand`, `CpCommand`, `MvCommand`, `RmCommand`).
- [`Demo.java`](src/main/java/lld/filesystem/Demo.java) — runnable `main()` exercising every verb
  through the `Shell`, including a cycle-prevention failure.

Representative excerpt — the cycle-prevention check inside `mv` (full method in
`FileSystem.java`):

```java
if (source.isDirectory() && (effectiveDestParent == source || source.isAncestorOf(effectiveDestParent))) {
    throw new InvalidOperationException(
            "mv: cannot move '" + sourcePath + "' into itself or one of its own descendants: '" + destPath + "'");
}
```

Run the demo:

```bash
javac -d build/out $(find src -name '*.java')
java -cp build/out lld.filesystem.Demo
```

---

## 10. Data Structures and Algorithms

| Structure / Algorithm | Used for | Why | Alternative | Complexity |
|---|---|---|---|---|
| `LinkedHashMap<String, FileSystemEntry>` per `Directory` | Children lookup by name | O(1) average get/put/remove; insertion-order iteration gives deterministic, stable `ls` output without sorting | `TreeMap` (sorted `ls`), `ArrayList` (linear scan) | O(1) avg vs. O(log n) (`TreeMap`) vs. O(n) (`ArrayList`) per lookup |
| Parent-pointer tree (each node stores one `Directory parent`) | `pwd` (walk up to root), cycle check (`isAncestorOf`) | `pwd`/ancestor-check are O(depth), which is the natural cost — no need to store full paths | Store absolute path strings per node, update on every ancestor rename/move | Path-string approach makes `mv` of a directory with N descendants an O(N) *string rewrite* of every descendant's stored path; parent-pointer approach makes `mv` O(1) in re-parenting cost (see below) |
| `mv` = detach + re-parent (no content copy) | Moving a file/directory | A move is purely a structural pointer update: remove from old parent's map, set `parent`/`name`, insert into new parent's map | Copy-then-delete | O(1) in subtree size for `mv` vs. O(subtree size) for copy-then-delete; this asymmetry is *why* the problem distinguishes `cp` (needs `-r` for directories, is O(subtree)) from `mv` (works on directories with no `-r` flag, is O(1)) |
| `cp -r` = recursive deep copy, pre-order, with a `listChildren()` snapshot per directory | Copying a subtree | Snapshot-then-recurse means a copy never needs to re-read a live, concurrently-mutating map mid-traversal (it already holds the exclusive tree lock for the whole operation, so this is belt-and-suspenders, not load-bearing) | In-place structural sharing (persistent tree, COW) | O(total nodes + total bytes) in the copied subtree, unavoidable since every byte must be duplicated |
| `PathResolver.normalize` — single left-to-right pass pushing onto an `ArrayList`, popping on `..` | Resolving `.`/`..`/empty segments | A path is processed in one O(path length) pass; using an `ArrayList` as a stack avoids a dedicated `Deque` import for a simple push/pop-last pattern | Regex-based path collapsing | O(k) where k = number of path segments either way; `ArrayList` approach is simpler to read and test in isolation (`PathResolverTest`-equivalent coverage is folded into `FileSystemBasicsTest`'s cd/mkdir tests since `PathResolver` is package-private) |
| `AtomicLong` monotonic id generator | Unique, stable node identity for lock-ordering and `equals`/`hashCode` | Guaranteed collision-free, unlike `System.identityHashCode` | `UUID` per node | `AtomicLong.incrementAndGet()` is O(1) and lock-free (single CAS loop internally); `UUID.randomUUID()` is meaningfully slower (SecureRandom) for no benefit here since global uniqueness within one JVM process is all that's required |

---

## 11. Concurrency and Thread Safety

> This section is mandatory per the spec even if the system were single-threaded; here it is the
> central design question, since the prompt explicitly asks: *what if multiple threads mutate the
> tree concurrently (e.g. one thread removing a directory while another is copying into it)?*

### Shared mutable state

1. **Tree structure**: which `Directory` holds which children, and each entry's `parent`
   pointer. Mutated by `mkdir`, `touch` (creates a child), `write` (creates a child if missing),
   `rm`, `cp`, `mv`.
2. **File content**: the `byte[]` inside a `File`. Mutated by `write`.
3. **Session cwd**: one `Directory` reference per `Session`. Mutated by `cd`; read by `pwd`, and
   implicitly by every relative-path resolution.

### The three strategies considered (recap from `FileSystem`'s class javadoc)

| Strategy | Read concurrency | Write concurrency | Deadlock risk | Complexity to get right | Verdict |
|---|---|---|---|---|---|
| Per-node lock + hand-over-hand (lock coupling) | High | High (fine-grained) | Real, requires a global lock-acquisition order for two-subtree ops (`mv`/`cp` across arbitrary directories) | High — exactly the kind of subtle bug an interviewer will probe for | Rejected for interview scope |
| Copy-on-write / persistent immutable tree (MVCC-style) | Perfect (readers never block) | Moderate (every mutation rebuilds the root-to-mutation-point path) | None | Moderate-high; more machinery than the problem needs | Rejected as primary design; documented as the production evolution path (§17) |
| **Two-tier: one coarse `ReentrantReadWriteLock` for structure + one lock per `File` for content** | Good (structure reads run concurrently via shared lock; unrelated file I/O never blocks on structure) | Structure writes are serialized tree-wide; content writes are per-file | **None** — only one coarse lock plus independent per-file locks, no two-lock ordering problem ever arises | Low — easy to state, easy to verify, easy to review | **Chosen** |

### Why the chosen strategy is correct for the motivating race

> *Thread A removes directory `/x` while thread B copies into `/x`.*

Both `rm` and `cp` acquire `FileSystem.treeLock.writeLock()` **once, for the entire operation**
(including the full recursive walk), not once per visited node. A `ReentrantReadWriteLock`'s
write lock is exclusive against every other read or write acquisition. Therefore:

- Whichever thread calls `lock()` first runs its *entire* recursive operation to completion
  (including existence checks and the full subtree walk) before the other thread's `lock()` call
  can return.
- There is no interleaving in which Thread B observes `/x` half-deleted (some grandchildren gone,
  others not), because Thread A either hasn't started removing anything yet (still blocked) or
  has already finished removing everything (lock released only after the whole `rm` body
  completes).
- Symmetrically, Thread A cannot observe a half-created copy it would need to also delete.
- The loser simply gets a clean, typed failure: if `rm` wins, `cp`'s subsequent
  `resolveEntryRequired` throws `PathNotFoundException` for `/x`; if `cp` wins, `rm` proceeds to
  delete `/x` (now containing the pre-copy tree — the copy already landed safely at its
  destination, which is a different subtree and therefore unaffected).
- This is proven, not just claimed: see
  `ConcurrencyTest.recursiveRemoveAndRecursiveCopyOfSameSubtreeAreLinearizable()`, which runs the
  two operations against a shared tree under a `CountDownLatch`-synchronized start and asserts the
  resulting state is always one of the two fully-consistent outcomes, never a partial one.

### Why recursive operations are correct under concurrent mutation in general

Any structural operation that needs to walk more than one node (`cp -r`, `rm -r`, and `mv`'s
ancestor-cycle check) acquires the write lock *before* starting the walk and releases it only
*after* the walk (and any resulting mutation) completes. This means the entire traversal sees a
single, unchanging snapshot of the tree's shape — equivalent to taking a consistent snapshot
without the memory overhead of actually copying the tree, because no other thread can be
mutating structure while the lock is held. `Directory.listChildren()` additionally returns a
defensive `ArrayList` copy of the children, so even code that (incorrectly) iterated after
releasing the lock would not throw `ConcurrentModificationException` — belt-and-suspenders, not
the primary correctness mechanism.

### Why content I/O does not need the structure lock

`File.write`/`read` use their own `ReentrantReadWriteLock`, entirely independent of
`FileSystem.treeLock`. This means: a long `write` appending megabytes to one file does not block
an unrelated `mkdir` elsewhere in the tree, and multiple concurrent `cat`s on the same file run
in parallel (shared read lock). This is validated by
`ConcurrencyTest.concurrentAppendsToSameFileDoNotLoseWrites()`, which fires 8 threads × 50
appends at one file and asserts the final length accounts for every single append (no lost
updates).

### Atomicity, visibility, and lock granularity, explicitly justified

- **Why a single tree-wide lock and not per-directory locks?** Per-directory locks require a
  consistent global ordering for any operation that must hold two directories' locks at once
  (`mv`/`cp` between unrelated subtrees). Establishing that ordering correctly (and proving it in
  review) is the single most error-prone part of tree-structured locking — the "deadlock from
  inconsistent lock order" bug class. A single coarse lock sidesteps the entire problem class at
  the cost of serializing *structural* operations tree-wide (not content I/O, which is
  independently lockable per file). For an LLD interview and for workloads where structural
  mutation (mkdir/mv/rm) is much rarer than content I/O (cat/write) — true of most real file
  system workloads — this is a well-justified trade-off, not a shortcut.
- **Why `volatile` on `name`/`parent`/`modifiedAt` in `FileSystemEntry` in addition to the tree
  lock?** The tree lock guarantees *atomicity* of compound structural operations. `volatile`
  additionally guarantees that a thread reading `getName()`/`getParent()` *without* holding the
  lock (e.g. `pwd`'s read-locked walk, which only needs the read lock, not the write lock) always
  sees the latest value rather than a stale cached one — necessary because the JMM does not
  otherwise guarantee cross-thread visibility of plain field writes.
- **Why `AtomicReference<Directory>` for `Session.cwd`?** Same visibility argument: `cd` (writer)
  and `pwd`/relative-path resolution (readers) can run on different threads for the same
  `Session` in pathological but legal usage (e.g. a UI thread reading `pwd` while a background
  task issues `cd`); an `AtomicReference` makes that race safe (one coherent cwd value is always
  observed) without needing a separate lock for a single-reference update.
- **Why no deadlock is possible:** there is exactly one tree-wide lock (`treeLock`) plus N
  independent per-file content locks, and no code path ever acquires a file's content lock while
  already holding another file's content lock, nor acquires `treeLock` while holding a content
  lock (content mutation in `write()` releases `treeLock` before calling `file.write(...)` — see
  `FileSystem.write`). With at most one lock of each disjoint "kind" ever held at a time per
  thread, a circular-wait (the necessary condition for deadlock) cannot form.
- **Optimistic vs. pessimistic locking:** this design is pessimistic throughout (acquire lock,
  then act) rather than optimistic (act, then validate a version/CAS and retry on conflict).
  Optimistic concurrency control suits high-contention, short, frequently-retryable operations;
  structural file-system mutations are comparatively rare and must not partially apply then roll
  back (rolling back a half-done recursive delete is itself a correctness hazard), so pessimistic
  locking is the safer, simpler choice here.

---

## 12. Persistence and Database Design

**This system is intentionally pure in-memory; no database is used, and that is a deliberate
design decision, not an omission:**

- The problem is scoped (§1, §3 Q1) as an in-memory data structure, analogous to a VFS layer or
  a test double — not a durable storage system. Every `FileSystemEntry` lives only as long as the
  `FileSystem` object referencing it; there is no requirement for data to survive a process
  restart.
- Introducing a database (even embedded, e.g. SQLite) would add transaction/ORM-mapping
  complexity, serialization overhead, and I/O latency to every operation, actively working
  against the O(1)/O(depth) complexity goals in §10, for a requirement that was never stated.
- If persistence *were* required (see §17 Production Evolution), the natural mapping is:
  - `entries(id BIGINT PK, name VARCHAR, parent_id BIGINT NULL FK→entries.id, type ENUM('FILE','DIR'), created_at, modified_at)` with a unique index on `(parent_id, name)` enforcing the name-uniqueness invariant (§5.3) at the database layer too.
  - `file_content(entry_id BIGINT PK FK→entries.id, content BLOB)` separated from `entries` so
    directory-structure queries (`ls`, path resolution) never need to touch potentially large
    BLOBs.
  - Structural mutations (`mv`, `rm -r`, `cp -r`) would be wrapped in a single DB transaction at
    `SERIALIZABLE` (or at minimum `REPEATABLE READ` plus explicit row locks on the affected
    parent rows) isolation level — the direct analogue of holding `treeLock`'s write lock for the
    whole operation in the in-memory version. The recursive subtree walk for `rm -r`/`cp -r`
    would use a recursive CTE (`WITH RECURSIVE`) keyed on `parent_id`, which is why the
    adjacency-list (`parent_id`) schema, not a materialized-path/nested-set schema, is the right
    match for this access pattern: the in-memory parent-pointer design translates directly.

---

## 13. Failure Scenarios

| Scenario | Behavior | Rationale |
|---|---|---|
| `mkdir` on an already-existing directory, no `-p` | `FileAlreadyExistsException` | Explicit failure over silent no-op: a caller expecting a *new* empty directory should not silently get a pre-existing (possibly non-empty) one. |
| `mkdir -p` on an already-existing directory | Succeeds, no-op | `-p` explicitly means "ensure this path exists", matching POSIX `mkdir -p`. |
| `cp`/`mv` destination already exists, no overwrite flag | `FileAlreadyExistsException`, **no mutation occurs** | The existence check happens before any mutation in both methods; a failed `cp`/`mv` never leaves a partial artifact. |
| `cp`/`mv` destination exists with overwrite flag, but is a different type (file vs. dir) | `InvalidOperationException`, no mutation | Overwriting a file with a directory (or vice versa) is almost always a user error worth surfacing explicitly rather than guessing intent. |
| `mv` a non-empty directory onto an existing (also non-empty) directory, with overwrite | `DirectoryNotEmptyException` | Silently merging two non-empty directory trees has no single obviously-correct semantics (which files win on a name collision inside the merge?); rather than guess, we fail loudly. (An empty existing directory *can* be safely replaced — implemented.) |
| `rm` a non-empty directory, no `-r` | `DirectoryNotEmptyException`, no mutation | Prevents accidental data loss from a one-flag-short command. |
| `rm`/`mv` a path that is an ancestor of the calling session's own cwd | `InvalidOperationException` | Prevents the calling session's own cwd from becoming a dangling pointer into a detached subtree (§3 Q7). |
| `mv` a directory into its own descendant (or into itself) | `InvalidOperationException`, no mutation | Would otherwise create a cycle (§5.2) or (for "into itself") a confusing no-op; the cycle check runs *before* any detach/attach, so a rejected move is a true no-op. |
| Path resolves through a file instead of a directory (e.g. `ls /a/file.txt/b`) | `NotADirectoryException` | Distinguished from "not found" because the failure mode (and typical fix) is different: the path up to that point *does* exist. |
| `rm -f` on a path that does not exist | Silent no-op success | Matches POSIX `rm -f` semantics explicitly (§3 assumption 4 applies only to confirmation UX, not to this flag's well-established meaning). |
| Resource exhaustion (extremely deep recursion in `cp -r`/`rm -r`/`deepCopy`) | `StackOverflowError` (unchecked `Error`, not caught) | Both recursive methods are depth-bound by the *tree's* depth, which in practice (a few hundred levels at most for any real use case) is far below default JVM stack depth (~10,000+ frames); converting to an explicit-stack iterative algorithm is a documented, cheap future change (§17) if arbitrarily adversarial depth is a real concern, not implemented here to avoid obscuring the (currently clearer) recursive form for an interview setting. |
| A session's cwd directory is deleted by a *different* session | Next `pwd`/relative-path resolution on the orphaned session walks a now-parentless chain and returns the last-known path components (the chain above the deletion point is unaffected, but it no longer resolves back to `/` through a live node) | Documented, accepted limitation (§3 Q7); the production fix (§17) is to have `Session.cwdSegments()` detect "unreachable from root" and fail fast with a typed exception instead of returning a stale path. |
| Duplicate concurrent `mkdir -p` for the same path from two threads | Exactly one thread creates each new segment; the other observes it already exists and treats it as satisfied (because `-p` on an existing directory is a no-op, §13 row 2) | `mkdir`'s whole per-segment loop runs under one write-lock acquisition, so there is no window where two threads could race to create the *same* missing segment — whichever thread's `lock()` call wins creates it; the other's subsequent `getChild` sees it. |

---

## 14. Performance and Scalability

**Bottlenecks and fixes, single-process:**
- `ls`/`pwd`/path resolution are O(depth) or O(children at one level) — no bottleneck at any
  realistic tree size.
- `cp -r`/`rm -r` are O(subtree size) — unavoidable (every node must be visited/copied/removed),
  but bounded strictly by the size of the subtree being operated on, not the whole tree.
- The single `treeLock` is a bottleneck *only* for concurrent *structural* mutation throughput
  (many threads all calling `mkdir`/`mv`/`rm` simultaneously); it is explicitly not a bottleneck
  for content-heavy workloads (many threads reading/writing *different* files), since those never
  touch `treeLock` at all. If profiling showed structural-mutation contention to be a real
  problem, the next lever (before full per-node locking) would be partitioning the single lock
  into a small fixed number of locks keyed by, e.g., top-level directory — see §17.

**Multi-threaded extension (already implemented, not hypothetical):** the two-tier lock already
described in §11 *is* the multi-threaded design; no "add locking later" gap exists.

**Why this problem should not be distributed:** sharding the tree across multiple
processes/machines would require a distributed-transaction protocol for any operation that
spans a shard boundary (a `mv` between nodes stored on different machines becomes a 2-phase
commit problem) for a workload that is, by construction, single-process in-memory data no larger
than fits comfortably in one JVM's heap. Distributing this would be solving a problem the prompt
never posed, at a large complexity cost, and is explicitly called out as *not* a goal — this is
an LLD exercise, not a distributed-systems one.

---

## 15. Testing Strategy

51 JUnit 5 tests, all passing (verified with `junit-platform-console-standalone`, see command
below), organized as:

- **Unit / boundary — path & single-node ops**:
  [`FileSystemBasicsTest`](tests/lld/filesystem/FileSystemBasicsTest.java) — mkdir (+ `-p`,
  collision, through-a-file), cd/pwd (`.`/`..`, clamping above root, through-a-file), touch
  (idempotent, type-collision), cat/write (round-trip, append, missing, wrong-type), ls (relative
  path, mixed file+dir listing).
- **Unit / negative / collision / cycle — recursive ops**:
  [`CopyMoveRemoveTest`](tests/lld/filesystem/CopyMoveRemoveTest.java) — `cp` (independent-copy
  semantics, missing `-r` rejection, recursive copy, copy-into-existing-dir, overwrite
  collision handling, copy-into-own-descendant termination), `mv` (rename, cross-directory move,
  move-into-existing-dir, **cycle prevention into own descendant and into itself**, overwrite
  collision handling, ancestor-of-cwd protection), `rm` (non-recursive-on-nonempty rejection,
  recursive removal, `-f` no-op on missing path, ancestor-of-cwd protection, root protection).
- **Concurrency / failure-injection**:
  [`ConcurrencyTest`](tests/lld/filesystem/ConcurrencyTest.java) — 16 threads creating disjoint
  directories concurrently (no corruption, every write lands); **the exact race from the prompt**
  (one thread `rm -r`'s a subtree while another `cp -r`'s into it) asserted to always end in one
  of exactly two fully-consistent states, never a partial one; 8×50 concurrent appends to one
  file asserted to never lose an update.
- **Command-pattern / integration**:
  [`ShellCommandTest`](tests/lld/filesystem/command/ShellCommandTest.java) — tokenization
  (including quoted multi-word arguments), unknown-command handling, exception-to-`CommandResult`
  conversion, flag parsing (`-r`, `-rf` shorthand).

Representative test (name-collision + cycle prevention together):

```java
@Test
void mvDirectoryIntoOwnDescendantThrowsInvalidOperation() {
    assertThrows(InvalidOperationException.class, () -> fs.mv(session, "/src", "/src/sub/nested", false));
}
```

**Verification performed (not just claimed):**

```bash
javac -d build/out -cp tools/junit-platform-console-standalone.jar $(find src tests -name '*.java')
java -jar tools/junit-platform-console-standalone.jar --class-path build/out --scan-class-path
```

Result: **51 tests found, 51 successful, 0 failed** (one real bug was found and fixed during this
process — `mv` of a directory onto itself was silently a no-op instead of throwing; see git
history / `FileSystem.mv`'s same-file check).

---

## 16. Trade-offs and Alternatives

| Decision | Chosen approach | Alternative | Pros/cons | When alternative wins |
|---|---|---|---|---|
| Locking granularity | Two-tier: one tree-wide structural lock + per-file content locks | Full per-node locking with lock-coupling | Chosen: simple, provably deadlock-free, easy to review. Alternative: higher structural-mutation concurrency, but much higher risk of a subtle ordering bug. | A production system under heavy, measured structural-mutation contention, with the engineering budget to get lock-ordering right and tested exhaustively. |
| Identity | Monotonic `AtomicLong` id per node | `UUID` per node | Chosen: faster, simpler, sufficient for single-JVM uniqueness. Alternative: globally unique across processes/machines. | A distributed system where nodes from different processes must be compared/merged. |
| Children storage | `LinkedHashMap` (insertion order) | `TreeMap` (sorted by name) | Chosen: O(1) avg ops, deterministic but non-alphabetical order. Alternative: O(log n) ops, but `ls` output is naturally sorted. | If alphabetical `ls` output is a hard requirement and lookup performance is not the bottleneck. |
| `mv` semantics for root/cwd-ancestor | Refuse to move root or an ancestor of the calling session's cwd | Allow it (real shells generally do allow moving your own cwd's ancestor, leaving cwd "orphaned but still readable") | Chosen: conservative, avoids a confusing dangling-cwd UX in a library/demo context. Alternative: more POSIX-faithful. | A faithful terminal-emulator reimplementation where matching real shell behavior exactly is the goal. |
| Error reporting across the Command boundary | Return value (`CommandResult`) | Let `FileSystemException` propagate to the caller of `Shell.execute` | Chosen: matches "a shell never crashes because one command failed" and makes testing trivial (assert on a value, no `assertThrows` boilerplate at the shell layer). Alternative: simpler internally (`Command` doesn't need to catch anything — `FileSystem` already doesn't), but pushes exception handling onto every `Shell` caller. | A pure library API with no shell/REPL framing, where letting exceptions propagate is the norm (this is exactly why `FileSystem` itself *does* throw — only the `Shell`/`Command` layer converts). |
| Cycle prevention mechanism | Walk `parent` pointers (`isAncestorOf`) under the write lock | Maintain a separate `depth`/ancestor-set index updated incrementally | Chosen: O(depth) per check, zero extra bookkeeping to keep consistent. Alternative: O(1) check, but requires correctly maintaining derived state on every structural mutation (a second invariant to get right). | A system with extremely deep trees and very frequent `mv` calls where O(depth) per-check is measured to matter. |

---

## 17. Production Evolution

Kept explicitly separate from the core interview solution above:

- **Persistence**: write-ahead log of structural operations (mkdir/mv/cp/rm as log records) plus
  periodic snapshots, or the relational schema sketched in §12, for crash recovery and durability.
- **Finer-grained concurrency**: evolve from one `treeLock` to either (a) a small fixed number of
  locks partitioned by top-level directory (cheap, bounded complexity increase), or (b) a full
  copy-on-write persistent tree with an atomically-swapped root reference for lock-free reads, if
  profiling under real load justifies the added complexity.
- **Distributed extension** (only if genuinely required — see §14's explicit argument against
  defaulting to this): shard top-level directories across nodes behind a metadata service;
  cross-shard `mv` becomes a two-phase-commit or saga; strongly discouraged unless the tree
  provably cannot fit in one process's memory.
- **Monitoring / metrics**: counters per verb (`mkdir.count`, `cp.bytes_copied`,
  `rm.directories_removed`), a gauge for current node count and total content bytes, a histogram
  of `treeLock` hold duration (the single most useful signal for whether the two-tier locking
  strategy is still appropriate at current load).
- **Logging**: structured audit log of every mutating operation (who, what path, when, success/
  failure) — directly useful for debugging the "which session's cwd got orphaned" failure mode
  from §13.
- **Fault tolerance**: the dangling-cwd limitation from §13 would be fixed by having
  `Session.cwdSegments()` detect a parent-chain that no longer terminates at the live `root`
  (e.g. by comparing against a node's own `isAncestorOf`/rootedness) and raise a typed
  `StaleSessionException`, prompting the caller to `cd /` or otherwise recover.
- **Permissions (sketch, since explicitly optional/out-of-scope above)**: add an `owner`/`mode`
  field to `FileSystemEntry`, and a `PermissionChecker` strategy consulted by `FileSystem` before
  each mutating/reading operation — deliberately not implemented, since no requirement in §2/§3
  called for multi-user isolation, and adding it speculatively would be the exact kind of
  over-engineering §1 warns against evaluating poorly.
- **Operational complexity**: a disk-backed or distributed version would need backup/restore
  tooling, schema migration for the persistence layer, and capacity planning — all explicitly
  out of scope for the in-memory LLD core but listed here so the boundary is explicit.
