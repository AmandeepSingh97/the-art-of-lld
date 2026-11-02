# Follow-Up Questions

25+ realistic interviewer follow-ups, categorized, each with a full answer.

---

## Object-Oriented Design

**1. Why is `FileSystemEntry` abstract rather than a concrete class with a `boolean isDirectory`
flag?**
An abstract base with `File`/`Directory` subclasses lets `isDirectory()` and `size()` be
polymorphic — callers that just want "the size of this node" or "is this a leaf" never need to
branch on a flag, and the compiler prevents constructing a `FileSystemEntry` that is neither.
A flag-based design would also let `File` carry a (meaningless) children map or `Directory` carry
a (meaningless) content field unless manually guarded — the type split makes invalid states
unrepresentable.

**2. Why does `Directory` expose only package-private mutators (`putChild`, `removeChild`, ...)
instead of public ones?**
So that `FileSystem` is the only class in the codebase capable of mutating tree structure, which
makes "every mutation happens under the tree lock" an enforceable property of the package
boundary rather than a convention every caller must remember. Public mutators would let any code
anywhere bypass the locking policy entirely.

**3. Why is `Session` a separate class instead of just a `Directory cwd` field passed around as a
parameter?**
`Session` models "one caller's point of view into the shared tree" as a first-class concept —
multiple sessions can share one `FileSystem` with independent cwds, which is the actual
requirement (multiple terminal tabs/users over one tree). Passing a raw `Directory` as a
parameter everywhere would lose the encapsulation of "this mutable pointer belongs to exactly one
caller" and would make it easy to accidentally share/mutate the wrong cwd reference.

**4. Why does `PathResolver` have no instance state and only static methods?**
It performs pure, deterministic string transformations (segment splitting, `.`/`..` resolution)
with zero dependency on the tree, locks, or sessions — making it a stateless utility is the
correct modeling choice (there's nothing to encapsulate as instance state), and it keeps the class
trivially unit-testable and reusable without needing a `FileSystem` instance.

**5. Could `File` and `Directory` have been modeled as one class with a nullable children map
instead of two subclasses?**
Technically yes, but it reintroduces the "invalid state representable" problem from Q1 (a `File`
with a non-null children map, or a `Directory` with non-null content, would be a bug waiting to
happen) and loses polymorphic dispatch — every call site would need an `if (children != null)`
check instead of relying on `isDirectory()`/method overriding.

---

## SOLID Principles

**6. Where does this design apply the Single Responsibility Principle most clearly?**
`PathResolver` (path string math only), `File`/`Directory` (data + its own lock, no path logic,
no command parsing), `Shell` (tokenizing + dispatch, no tree logic), and `FileSystem` (tree
mutation + locking, no shell-parsing logic) each have exactly one reason to change. Contrast with
a hypothetical single `FileSystem` class that also parsed command-line strings — a change to flag
syntax (`-rf` vs `-r -f`) would then risk touching tree-mutation code.

**7. How does this design satisfy the Open/Closed Principle?**
Adding a new shell verb means adding a new `Command` implementation and registering it with
`Shell` — no existing `Command` class or `Shell.execute` changes. Adding a new typed exception
(e.g. a future `SymlinkCycleException`) means adding a new `FileSystemException` subclass — no
existing exception-handling code needs modification since `Shell` catches the common base type.

**8. Does `mv`/`cp`'s destination-is-existing-directory special case ("copy/move into it")
violate the Liskov Substitution Principle anywhere?**
No LSP concern arises because there's no subtyping relationship involved in that logic — it's
behavioral branching on runtime type (`isDirectory()`) within one method, not substitution of a
subtype for a supertype. LSP would be relevant if, say, `Directory` overrode a method in a way
that violated `FileSystemEntry`'s contract (e.g. if `Directory.size()` threw instead of summing
children) — it doesn't.

**9. Where does Interface Segregation apply here?**
`Command` is a minimal two-method interface (`name()`, `execute(...)`) — a class implementing one
verb never needs to implement unrelated behavior (contrast with a hypothetical fat
`ShellVerb` interface also requiring `getHelpText()`, `getFlags()`, `validate()` that every verb
must stub out even if unused).

**10. Where is Dependency Inversion applied, if at all?**
`Shell` depends on the `Command` abstraction, not on concrete `MkdirCommand`/`CpCommand`/etc
classes — it holds a `Map<String, Command>` and dispatches polymorphically. This is a direct,
appropriate use: `Shell` would need no code change to support a brand-new verb class it has never
seen before, as long as that class implements `Command`.

---

## Design Patterns

**11. Why Composite instead of a simpler flat `Map<String, byte[]>` keyed by full path string?**
A flat map "fakes" hierarchy via string prefixes, which makes `mv`/`cp` of a directory an O(all
keys with this prefix) string-rewrite operation, makes cycle detection a string-prefix check
instead of an O(depth) pointer walk, and makes "list immediate children of X" require scanning
every key for the right prefix depth instead of O(1) map lookup on one real `Directory` node.
Composite is the structurally honest representation of what is fundamentally a tree.

**12. Why not use the Visitor pattern for `size()` and `deepCopy`?**
With exactly two recursive operations and no indication a third/fourth is imminent, a `Visitor<R>`
interface would add a layer of indirection (double-dispatch, a visitor class per operation) without
a second concrete traversal algorithm to justify the abstraction — premature generalization. If a
third traversal (e.g. `find`, `du -h` formatting, `chmod -R`) is added, introducing Visitor at that
point is well-motivated; today it is not.

**13. Isn't `FileSystem` itself close to a God Class, given how many responsibilities it has?**
It has one *cohesive* responsibility — "coordinate structure-affecting access to the tree under a
consistent locking policy" — expressed through many methods, not many unrelated responsibilities.
Each method (mkdir, cp, mv, rm, ...) is a facet of the same concern: "safely mutate or read the
shared tree." Contrast with an actual God Class smell, e.g. `FileSystem` also containing shell
tokenization logic or HTTP-serving code — that would be multiple unrelated responsibilities
fused together, which this design explicitly avoids by keeping `Shell`/`Command` separate.

**14. Why Facade (`FileSystem`) instead of letting `Command` classes talk to `Directory`/`File`
directly?**
Centralizing all tree access behind one facade means locking policy, path resolution, and
exception translation live in exactly one place. If `Command` classes called `Directory` methods
directly, each would need to independently get locking right — multiplying the risk surface by
the number of commands instead of concentrating it in one reviewable class.

**15. Did you consider the Observer pattern anywhere, and why isn't it here?**
Yes — for a hypothetical `watch`/file-change-notification feature (see
`STAFF_LEVEL_DEEP_DIVE.md` Q6). It isn't implemented because nothing in the stated requirements
asked for change notification; adding Observer machinery speculatively would be unused
complexity. It is explicitly named as the natural extension point if that requirement appears.

---

## Data Structures

**16. Why `LinkedHashMap` and not `HashMap` for `Directory.children`?**
`LinkedHashMap` adds predictable insertion-order iteration at a small, constant memory/time
overhead over `HashMap`, giving `ls` deterministic, reproducible output (useful for tests and for
matching a user's mental model of "things appear in the order I created them") without needing a
separate sort step or auxiliary ordering structure.

**17. Why parent pointers instead of storing each node's full absolute path string?**
Storing full paths would require rewriting every descendant's stored path string on every `mv` of
a directory — an O(subtree size) string-rewrite cost for what is otherwise an O(1) structural
operation (detach + re-parent). Parent pointers make `mv` O(1) and push the (unavoidable) cost of
computing a path string to the point where it's actually needed (`pwd`, error messages), which is
O(depth), not O(subtree size).

**18. What's the time complexity of `cp -r` and `rm -r`, precisely, and why can't it be better?**
Both are O(N) in the number of nodes in the subtree being operated on (plus O(bytes) for file
content copied, in `cp`'s case) — this is optimal, not just acceptable, because both operations
are defined as "touch every node in the subtree" (copy it, or remove it); any correct
implementation must visit every node at least once.

**19. Why is `mv` O(1) but `cp` of a directory is O(subtree size) — isn't that inconsistent?**
No — it reflects a genuine semantic difference. `mv` never duplicates data; it re-parents one
node (the subtree root), and every descendant's parent-pointer chain still resolves correctly
without visiting them, because only the one moved node's own parent reference changes. `cp` must
produce an independent copy of every byte and every node, which is inherently O(subtree size);
there's no way to make a *real* copy cheaper than visiting what's being copied.

**20. Why `AtomicLong` for ids instead of `UUID.randomUUID()`?**
`AtomicLong.incrementAndGet()` is a single lock-free CAS loop, meaningfully faster than
`UUID.randomUUID()` (which draws from `SecureRandom`, a comparatively expensive operation), and
monotonic long ids are sufficient for this design's actual requirements (unique within one JVM
process, usable for lock ordering and equality) — global cross-process uniqueness (UUID's actual
value proposition) isn't needed for an in-memory, single-process structure.

---

## Java Internals

**21. Why does `FileSystemEntry` mark `name`, `parent`, and `modifiedAt` as `volatile`?**
To guarantee visibility: without `volatile`, the Java Memory Model does not guarantee that a
write to a plain field by one thread becomes visible to a read by another thread except through
an intervening synchronization action (lock acquisition, `volatile` read/write, etc). Since
`getName()`/`getParent()` can legitimately be called by a thread holding only the tree *read*
lock (which doesn't establish a happens-before relationship with a *different* thread's prior
*write*-lock-protected mutation of those same fields once that write-lock critical section has
ended and the read-lock critical section begins, unless the fields themselves are volatile or the
specific lock instance creates that ordering) — making the fields `volatile` closes this gap
cheaply without a second, redundant lock acquisition per field read.

**22. Why `equals`/`hashCode` based on `id` (a `long`), and what Java-specific pitfall does this
avoid?**
It avoids relying on `Object.hashCode()`'s default identity-based hash (which is not guaranteed
stable or unique in all JVMs/GC implementations, and is explicitly discouraged for any contract
requiring stability across the object's lifetime) and avoids the classic "mutable hash key" bug:
if equality were based on mutable fields (`name`, `parent`), placing an entry in a
`HashSet`/`HashMap` and then mutating those fields (via `mv`) would make the entry unfindable at
its original hash bucket — a `long id`, assigned once and never changed, sidesteps this entirely.

**23. What's the significance of using `ReentrantReadWriteLock` specifically, rather than a plain
`synchronized` block, for the tree lock?**
`synchronized` provides only mutual exclusion — every entry, read or write, blocks every other
entry. `ReentrantReadWriteLock` lets multiple readers (ls, pwd, path resolution) proceed
concurrently while still giving writers exclusive access, which matters because reads are the
overwhelmingly common case in most realistic usage. A plain `synchronized` block would be simpler
but would serialize `ls` calls against each other for no correctness reason.

**24. Why is `FileSystemException` unchecked (`extends RuntimeException`) rather than checked?**
Because nearly every operation in this API can fail for several independent, often non-recoverable-
at-the-call-site reasons (missing path, wrong type, collision, cycle) — forcing every caller to
declare or catch each specific checked exception type would pollute every method signature up the
call stack for marginal benefit; the one place that actually needs to catch them (`Shell`) catches
the common unchecked base type once.

**25. Does `File.copyAs` need its own lock acquisition, or could it rely on the caller already
holding `treeLock`?**
It explicitly still acquires `File`'s own content *read* lock (not `treeLock`), because content
and structure are independently-lockable concerns in this design (§11) — `treeLock` says nothing
about the safety of reading `content` concurrently with another thread's `write()` to that same
`File`. Even though `cp` already holds `treeLock` for the whole operation (preventing a
*concurrent structural* change to the file being copied, e.g. it being deleted mid-copy), a
different thread could still be calling `write()` on that exact `File` at the same moment (content
mutation doesn't need `treeLock` at all), so `copyAs` independently protects against a torn read
of `content`.

---

## Multithreading

**26. What's the actual proof that the rm-vs-cp race can't produce a corrupted tree?**
See `STAFF_LEVEL_DEEP_DIVE.md` Q1/Q2 and the README §11: both operations hold the same exclusive
write lock for their entire duration (not per-node), so a `ReentrantReadWriteLock`'s mutual-
exclusion guarantee means one operation completes fully before the other starts — there is no
instant where either thread observes a half-mutated tree. This is additionally verified, not just
argued, by `ConcurrencyTest.recursiveRemoveAndRecursiveCopyOfSameSubtreeAreLinearizable()`.

**27. Why is there no deadlock risk between `treeLock` and per-file content locks?**
Because no code path ever holds both simultaneously — `FileSystem.write()` releases `treeLock`
before calling into `File.write()`'s content lock. With at most one lock "kind" held per thread
at any instant, a circular wait (deadlock's necessary condition) cannot occur. Full reasoning in
`STAFF_LEVEL_DEEP_DIVE.md` Q4.

**28. What would you change if profiling showed `treeLock` contention was the actual bottleneck
in production?**
First lever: partition the single lock into a small fixed number of locks keyed by top-level
directory (bounded added complexity, directly reduces unrelated-subtree contention). Only if that
proved insufficient would full per-node lock-coupling or a copy-on-write persistent tree be worth
the much higher implementation/review cost (§11, §17).

---

## Database and Persistence

**29. Why is there no database in this design, and is that actually defensible or just lazy?**
It's defensible because the problem is explicitly scoped as an in-memory data structure (§3 Q1) —
introducing a DB would add transaction/ORM/I/O-latency overhead to every operation for a
durability requirement that was never stated, directly working against the O(1)/O(depth)
complexity goals. README §12 gives the concrete relational schema (`entries` + `file_content`
tables, adjacency-list `parent_id`) that would be used if persistence were added, and explains why
an adjacency-list schema (not materialized path/nested sets) matches this design's access pattern.

**30. If you did add persistence, what isolation level would structural mutations need, and why?**
At minimum `REPEATABLE READ` with explicit row locks on affected parent rows, or `SERIALIZABLE` —
the direct database analogue of holding `treeLock`'s write lock for an entire structural
operation in the in-memory version, needed to prevent the identical rm-vs-cp race at the DB layer
(e.g. one transaction's recursive CTE delete racing another's recursive CTE copy over the same
rows).

---

## Performance

**31. What's the actual bottleneck at 10 million files, and is it capacity or something else?**
Not raw capacity (a few hundred bytes/node × 10M fits easily in a modern heap) — the real
bottlenecks are GC pause time under sustained pressure from tens of millions of long-lived small
objects, and structural-mutation throughput under the single `treeLock` if concurrent
`mkdir`/`mv`/`rm` traffic across unrelated subtrees is high (full reasoning in
`STAFF_LEVEL_DEEP_DIVE.md` Q8).

**32. Why doesn't a large `cp -r`/`rm -r` get chunked or yielded to avoid blocking other
operations for too long?**
It's a deliberate trade-off, not an oversight: chunking the lock (release/reacquire partway
through a recursive walk) would reopen exactly the "operation observes a half-mutated tree"
problem this design holds one lock continuously to prevent. The accepted cost — one very large
operation blocks unrelated structural work for its full duration — is named explicitly in §12 of
`STAFF_LEVEL_DEEP_DIVE.md` as the concrete, measurable downside of the coarse-lock choice, with
lock partitioning (not chunking within one operation) as the recommended production fix.

---

## Failure Handling

**33. Why does a failed `cp`/`mv` guarantee no partial mutation occurred?**
Because all existence/collision/type checks happen before the first actual mutation
(`removeChild`/`putChild`/`setParent`) in both methods, and the whole method runs under one lock
acquisition with no intermediate release — if any check throws, execution never reaches the
mutating statements at all, so the tree is provably unchanged on any exception path.

**34. Why does `rm -f` on a non-existent path succeed silently instead of throwing?**
It matches well-established POSIX `rm -f` semantics explicitly (force = "don't complain about
missing things"), which is the behavior most callers actually want when scripting idempotent
cleanup (`rm -f /maybe/exists` should not need a try/catch).

---

## Production Architecture

**35. What's the first production concern you'd add that isn't in this design at all?**
Structured audit logging of every mutating operation (who, what path, when, outcome) — directly
useful for diagnosing the one accepted limitation this design has (a different session's cwd
becoming orphaned by another session's `rm`), and generally necessary for any multi-tenant shared
system where "who changed what" needs to be answerable after the fact.

**36. How would you monitor whether the two-tier locking strategy is still appropriate once this
is running in production?**
A histogram of `treeLock` hold duration and wait time, plus counters per verb — if hold-duration
is dominated by a few very large `cp -r`/`rm -r` calls blocking everything else, that's the
concrete signal to introduce lock partitioning (§17), rather than guessing at the right time to
add complexity.
