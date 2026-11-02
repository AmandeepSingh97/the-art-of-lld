# Staff-Level Deep Dive — In-Memory File Management System

Twenty-four problem-specific, reasoning-based questions a Staff/Principal-level interviewer
might raise against this exact design — not generic "tell me about CAP theorem" questions.

---

### 1. "You said a single `treeLock` is correct for the rm-vs-cp race. Prove that it's also correct for three or more concurrent structural operations touching overlapping subtrees, not just two."

The correctness argument doesn't actually depend on there being exactly two threads — it depends
on `ReentrantReadWriteLock`'s write lock being exclusive against *all* other acquisitions,
read or write, regardless of count. With N threads each calling a structural method
(`mkdir`/`mv`/`cp`/`rm`), the lock imposes a total order: whichever thread's `lock()` call is
granted first runs its entire operation to completion before the next is granted. This reduces
the N-thread case to a sequence of the two-thread case repeated N-1 times — each operation sees
the tree exactly as the previous one left it, which is the definition of linearizability. The
only thing that changes with more threads is *latency* (more operations waiting), never
*correctness*. The place this reasoning would break is if any method released and re-acquired the
lock mid-operation (e.g. per recursion level) — this design explicitly does not do that, which is
exactly why it's reviewable in one read of `FileSystem`'s methods rather than requiring a
whiteboard proof per method.

### 2. "Your cycle check in `mv` is a single ancestor-walk. What if, between the check and the actual move, another thread's `mv` changes a parent pointer along that exact ancestor chain?"

It can't, and this is the crux of why the design holds one write lock for the whole method rather
than "check under lock, then release, then move." `mv`'s cycle check
(`source.isAncestorOf(effectiveDestParent)`) and the subsequent detach/re-parent/attach all
execute between a single `lock()`/`unlock()` pair. No other thread can be mutating *any* parent
pointer anywhere in the tree during that window, because every parent-pointer mutation anywhere
in the tree also requires that same write lock. This is a textbook "check-then-act" race, and the
fix is the standard one: make the check and the act atomic with respect to each other by holding
one lock across both — which this design already does, not as an afterthought but as the central
locking decision.

### 3. "Why is `File`'s content lock separate from the tree lock instead of just reusing the tree lock for everything, including content writes?"

Two reasons, one about correctness-independence and one about throughput. Correctness: content
mutation doesn't need to be serialized against *unrelated* structural mutation — writing to
`/a/big.log` and running `mkdir /b/c` have no data dependency, so coupling them through one lock
would be an unjustified throughput cost with no correctness benefit. Throughput: file I/O is
almost certainly the hottest path in any realistic workload (far more `cat`/`write` calls than
`mkdir`/`mv`/`rm` calls over a session's lifetime); if it shared the tree lock, every write to any
file anywhere would serialize against every structural change anywhere, which is a much worse
bottleneck than the current design's "structural ops serialize with each other" (already a
narrower, less costly restriction, per §14 of the README).

### 4. "Could you deadlock between the tree lock and a file's content lock?"

No, and the proof is by construction, not by luck: trace every code path that acquires both.
`FileSystem.write()` acquires `treeLock.writeLock()`, does the tree-structure work (find-or-create
the `File` node), **releases `treeLock`**, and only then calls `file.write(...)`, which acquires
the file's own content lock. At no point does any method hold `treeLock` and a content lock
simultaneously. Since a thread never holds more than one of these lock "kinds" at a time, a
circular wait — the necessary condition for deadlock — cannot form, regardless of how many
threads or files are involved. The one thing to watch if this code evolves: a future change that
tried to "hold the tree lock through the whole write for extra safety" would reintroduce exactly
the throughput problem from Q3 without adding any correctness benefit (the tree lock isn't needed
once the `File` node reference itself is pinned).

### 5. "What's the actual blast radius if someone adds a tenth structural operation (say, `chmod -R` or `find`) and forgets to acquire the tree lock?"

This is the honest answest to "where is this design most fragile?" There is no compiler-enforced
guarantee that a new method acquires `treeLock`; it's a convention enforced by `Directory`'s
package-private mutators only being reachable from within the `lld.filesystem` package, which
narrows the blast radius to "a bug introduced inside this one package" rather than "any caller
anywhere." But within the package, a new recursive method that walks `Directory.listChildren()`
and mutates state without taking `treeLock` first would silently reintroduce the exact race this
design exists to prevent, and nothing short of a concurrency stress test (like
`ConcurrencyTest`) or code review would catch it. The mitigating factor: every existing mutating
method follows the identical `treeLock.writeLock().lock() / try / finally unlock()` pattern, so a
reviewer has a clear template to check new code against — but this is a process guarantee, not a
type-system one. A stronger version (not built here, intentionally, to avoid over-engineering a
single-package LLD solution) would wrap all `Directory` mutator access behind a single
`withStructuralLock(Supplier<T>)` helper that *cannot* be bypassed, making the convention
mechanical rather than remembered.

### 6. "Interviewer adds a requirement mid-interview: 'now support watch/subscribe — notify a listener when a file under a given directory changes.' Can you add this without touching existing code?"

Partially, and the honest answer identifies exactly which part needs to change. Adding a
`FileSystem.watch(Session, String path, Listener listener)` method and a `List<Listener>` field
on `Directory`/`File` is additive (Open/Closed-compliant) for the *registration* API. But firing
notifications correctly requires every existing mutating method (`mkdir`, `touch`, `write`, `rm`,
`cp`, `mv`) to call into a notification hook at the point of mutation — that is a real, if
mechanical, touch of existing code, and it has a locking wrinkle: should listener callbacks run
*while* `treeLock` is held (risk: a slow or buggy listener blocks all structural operations
tree-wide) or *after* release (risk: by the time the listener runs, the tree may have changed
again, so the "current state" the listener sees might already be stale)? The correct answer for a
production system is to publish notifications to an async queue from inside the lock (fast,
non-blocking enqueue) and have listeners consume off-thread — i.e. this feature pulls in an
event-bus/queue component that genuinely wasn't needed before, which is exactly the kind of
scope-changing addition worth flagging to the interviewer rather than quietly squeezing in.

### 7. "Your `rm` refuses to delete an ancestor of the *calling* session's cwd, but you admitted a *different* session's cwd can still be orphaned. Why is that asymmetry acceptable, and when would it stop being acceptable?"

It's acceptable here because the check is a convenience guard against a confusing self-inflicted
UX problem (the active caller yanking the rug from under their own next `pwd`), not a safety or
data-integrity guarantee — the orphaned directory chain is still fully intact, reachable through
any reference anyone already holds to it (e.g. the `Session.cwd` reference itself is still a
valid `Directory` object with valid children; it has just been detached from `root`). It stops
being acceptable the moment some other part of the system assumes "every live `Directory` is
reachable from root" as an invariant — for example, if `size()`-style tree-wide reporting or
garbage-collection-by-tree-walk were added, an orphaned-but-referenced subtree would silently
keep consuming memory forever (a real memory leak) since nothing walks from root would ever free
it, and nothing currently tracks "detached roots" to clean them up. That's the concrete production
gap flagged in §17 (`StaleSessionException`), and it's worth saying out loud unprompted if asked
"is there anything you'd fix given more time?"

### 8. "At 10 million files, what's the first thing that breaks?"

Not raw capacity — a `LinkedHashMap`-backed tree of 10M nodes fits comfortably in a modern JVM
heap (a few hundred bytes per node × 10M ≈ low single-digit GB), and lookups stay O(1)
average/O(depth) regardless of total node count. The first real break is **GC pause time**: a
heap holding tens of millions of long-lived small objects (`FileSystemEntry` subclasses) puts
sustained pressure on a generational collector, and a full/major GC pause scales with live-object
count, which can show up as multi-second stalls under a G1/ZGC default without tuning — directly
hurting tail latency for every operation, since nothing here is GC-isolated. The second break,
specifically for this design, is **structural-mutation throughput**: a single `treeLock` means
that at 10M files spread across thousands of concurrent sessions, any burst of concurrent
`mkdir`/`mv`/`rm` calls (even on totally unrelated subtrees) serializes completely — this is
where the "partition the lock by top-level directory" evolution from §17 stops being optional and
starts being load-bearing.

### 9. "You chose pessimistic locking throughout. Make the strongest case for why that's wrong for this specific workload."

The strongest case against: most real file-system-like workloads are read-heavy (far more `ls`/
`cat`/`pwd` than `mkdir`/`mv`/`rm`), and a `ReentrantReadWriteLock`'s read lock already allows
read-read concurrency, so the pessimistic cost is paid almost entirely on the write side, which is
the rarer case — meaning the actual throughput cost of "pessimistic" here is smaller than it
sounds, because the common case (reads) is already concurrent. Where pessimistic genuinely loses:
if the dominant workload were instead *many small, independent structural mutations across
unrelated subtrees* (e.g. 10,000 users each running `mkdir` in their own home directory
simultaneously), every single one serializes against every other despite having zero actual data
dependency — that's a workload shape where optimistic (versioned nodes, retry on conflict) or
partitioned locking would materially outperform this design. The honest conclusion: pessimistic
is the right *starting point* given the ambiguity of "what's the real workload" in an interview,
but it is not unconditionally correct, and should be revisited the moment real traffic data is
available.

### 10. "Walk through what happens if the JVM crashes mid-`mv` — specifically between `oldParent.removeChild(...)` and `effectiveDestParent.putChild(...)`."

Because this is pure in-memory state with no persistence, a JVM crash destroys the entire heap,
including the half-completed move — there is no "recovery" question in the traditional sense,
because there is nothing to recover *to*; the next process start begins with an empty tree. This
is actually a meaningful simplification worth stating explicitly: the entire "partial failure /
crash recovery" problem class that a persistent file system must solve (e.g. ext4's journal,
or a WAL) simply does not apply here, *because* persistence was explicitly scoped out (§3 Q1).
The moment persistence is added (§17), this exact question becomes the central design problem:
the fix would be to make the two mutations (`removeChild` + `putChild`) a single atomic log record
(or DB transaction) so that replaying the log after a crash either sees the whole `mv` applied or
not at all — i.e. the in-memory design's "hold one lock for the whole method" pattern maps
directly onto "wrap the whole method in one transaction" once persistence exists.

### 11. "Your `deepCopy` and your cycle-check (`isAncestorOf`) are both recursive with no explicit depth limit. Is that a real production risk, and if so, where exactly does it bite?"

Yes, and it's worth being precise about exactly which paths are at risk rather than hand-waving
"recursion is scary." `isAncestorOf` recurses (iteratively, actually — it's a `while` loop
following `parent` pointers, not a recursive call) up to the tree's *depth*, which is
user-controlled only insofar as users can create arbitrarily deep directory chains via repeated
`mkdir -p a/a/a/a/...`. `deepCopy`, by contrast, genuinely recurses (real stack frames) to a depth
equal to the *source subtree's depth* being copied. Both are bounded by directory depth, not by
total node count or total file size, so the realistic risk is specifically "someone constructs an
adversarially deep directory chain (tens of thousands of levels) and then `cp -r`s it," which
would risk a `StackOverflowError`. This is flagged, not fixed, in §13's Failure Scenarios table —
the fix (converting `deepCopy` to an explicit-stack iterative traversal) is mechanical but was
deliberately deferred because it would meaningfully reduce code clarity for a benefit (defending
against adversarial directory depth) that is out of scope unless the interviewer explicitly raises
untrusted/adversarial input as a requirement.

### 12. "You rejected per-node locking as 'too easy to get wrong.' Make the opposing, strongest case: when would the coarse lock actively cause a production incident that per-node locking would have prevented?"

A plausible incident: a single very large `cp -r` or `rm -r` on a subtree with, say, a million
nodes holds `treeLock`'s write lock for the entire duration of that walk — during which **every**
other structural operation anywhere in the tree (a `mkdir` for an unrelated user, a `touch` for an
unrelated session) blocks completely, even though none of those operations touch the subtree
being copied/removed. Under per-node locking, those unrelated operations would proceed
unaffected. This is the single most concrete, measurable cost of the coarse-lock design, and it
scales with "largest single recursive operation someone can trigger," which is exactly why §14
calls out structural-mutation throughput as the metric to watch and names lock-partitioning (not
full per-node locking) as the first production lever — a middle ground that fixes this specific
incident shape (one huge subtree blocking everything) without taking on full lock-coupling's
ordering-correctness risk.

### 13. "If you had to ship a stripped-down version of this in two weeks, what would you cut, and what would you refuse to cut?"

Cut first: the `-p`/`-f`/`-a` flag matrix (ship single, unambiguous-behavior commands first,
flags are additive later), the `Session`-per-caller abstraction (ship a single implicit global
cwd if only one caller exists in week one), and any notion of "merge directories on overwrite"
(always require the destination to be absent or of a trivially-replaceable empty kind). Refuse to
cut: the Composite base (`FileSystemEntry`/`File`/`Directory`) — retrofitting a proper tree
structure onto a flat path-string map later is a full rewrite, not an incremental change; and the
cycle-prevention check on `mv` — shipping a `mv` that can corrupt the tree into a cycle is a
data-integrity bug, not a missing nicety, and every consumer downstream (anything that walks the
tree, e.g. `ls`, `size`, future `find`) would infinite-loop on a cyclic tree.

### 14. "Your `Directory` uses a `LinkedHashMap`. At what child-count does that stop being the right choice, and what would you switch to?"

The `O(1)` average lookup/insert/remove holds regardless of child count — a hash map doesn't
degrade with more entries the way, say, a linear scan would. What *does* change at high child
counts (tens of thousands of children in one directory, e.g. a flat "uploads" directory) is `ls`'s
cost: it already returns a full `ArrayList` snapshot of every child (O(children), unavoidable if
the caller genuinely wants to see all of them), but if the real use case needed *paginated*
listing (show me children 1000–1010), the current API has no way to do that cheaply — it would
materialize and discard the first 1000 every time. That would be the trigger to introduce a
structure with efficient ordered range queries (e.g. a `ConcurrentSkipListMap` keyed by name, or
an explicit pagination cursor keyed by the last-seen name) — not because `LinkedHashMap` itself
is slow, but because the *access pattern* (pagination) would have changed.

### 15. "Explain precisely why `equals`/`hashCode` on `FileSystemEntry` are based on `id`, not on `name`+`parent`, and what would break if you'd chosen the latter."

Basing equality on `id` gives entries stable identity across a rename or move — the same `File`
object is `.equals()` to itself before and after an `mv`, which is the semantically correct notion
of "is this the same file" (POSIX inode semantics, essentially — an inode's identity doesn't
change when you rename it). Basing equality on `(name, parent)` would break the moment any `mv`
or rename happened: an entry stored in, say, a `HashSet<FileSystemEntry>` (e.g. a future "set of
open file handles") would become unfindable by its original reference after being moved, because
its hash code would have silently changed out from under the set — a classic "mutable hash key"
bug. This is exactly why `FileSystemEntry.getId()` — immutable, assigned once at construction — is
the one field other code (lock ordering, equality) can safely depend on, while `name`/`parent`
are explicitly documented as mutable.

### 16. "If two completely independent `FileSystem` instances needed to be merged into one tree (e.g. merging two users' home directories), what in your design would have to change?"

The `AtomicLong` id generator is `static`, scoped to the whole JVM, which actually makes this
*easier* than it could be: ids from two different `FileSystem` instances in the same JVM are
already guaranteed not to collide (both draw from the same counter), so a naive "graft subtree B
under a node in tree A" merge wouldn't hit an id collision. What *would* need to change: the
single-root assumption (`FileSystem` currently has exactly one `root`; a merge needs a policy for
what happens to two different roots — pick one as the surviving root and re-parent the other's
children under some node in it) and, more subtly, the name-collision policy at the merge point
(two home directories both having a top-level `Documents` folder is exactly the "merge two
non-empty directories" case this design explicitly refuses to silently resolve — see §13 — so a
real merge feature would need an explicit, user-facing conflict-resolution UI/policy, not just a
tree-level API change).

### 17. "What does 'API evolution' / backward compatibility mean concretely for `FileSystem`'s public methods, and which of your current signatures would you regret in two years?"

Concretely: once `FileSystem.cp(Session, String, String, boolean, boolean)` has external callers,
adding a *new* optional behavior (e.g. "preserve timestamps on copy") either means a backward-
compatible overload (`cp(Session, String, String, CopyOptions)` with `CopyOptions` as an
extensible builder/record) or a breaking signature change. The signature I'd most regret: the
stacked `boolean recursive, boolean overwrite` parameters on `cp`, and `boolean overwrite` alone
on `mv` — boolean parameters don't self-document at call sites (`cp(s, d, true, false)` reads
poorly) and don't scale as more options get added (a hypothetical sixth boolean flag would be
unreadable). The fix, if I were designing this for a longer-lived public API rather than an
interview exercise, is an options value object (`CopyOptions.builder().recursive(true).build()`)
from day one — deliberately not done here because for a 10-method interview-scope API, the
extra ceremony isn't yet worth it, but it's the first thing I'd refactor before this became a
genuinely long-lived public library.

### 18. "Your `PathResolver.normalize` silently clamps `..` above root instead of erroring. Defend that as a conscious choice, not an oversight."

It mirrors real shell/OS behavior deliberately: `cd /../../..` in a real Unix shell lands you at
`/`, not an error — "going above root" has one obvious, safe interpretation (you're already at the
top, so an upward move is a no-op), unlike, say, resolving a genuinely nonexistent path segment
(no reasonable interpretation exists, so that correctly throws `PathNotFoundException`). The
alternative — throwing on excess `..` — would make every relative-path-heavy script defensively
have to count directory depth before using `..`, which is not how any real shell behaves and would
surprise users badly. The place this *would* become a real design question is if `PathResolver`
needed to distinguish "user typo" from "legitimate upward-clamped navigation" for, say, audit
logging purposes — that's a plausible future requirement this design doesn't currently serve, and
would need a different return type (e.g. a result object noting "N segments were clamped") rather
than a silent `List<String>`.

### 19. "You said `cp` into a directory's own descendant 'just works' because the copy snapshots children at call time. Trace through byte-for-byte why that's true and not an accident."

`deepCopy(source, ...)` calls `sourceDir.listChildren()` exactly once per directory, up front,
returning a defensive `ArrayList` copy of whatever children existed *at that instant* — before any
recursive call that might add new children to that same directory. Even though the overall `cp`
operation subsequently creates new nodes (the copies) and attaches some of them *inside* the very
subtree being copied (when the destination is a descendant of the source), those newly-created
copy nodes are never visited by `deepCopy`, because the only children `deepCopy` will ever examine
for a given source directory were captured in that one `listChildren()` call, which happened
before any new nodes existed. This is why the design note in the README says "not an accident":
it's a direct, provable consequence of snapshot-then-recurse, not a happy coincidence — and it's
exactly the kind of claim this deep-dive and the test suite (`cpDirectoryIntoOwnDescendantTerminatesAndIsConsistent`)
both verify rather than assert.

### 20. "Where exactly would you add a feature flag / gradual rollout mechanism if you needed to ship a behavior change to `mv`'s collision semantics without breaking existing callers?"

The natural seam is `FileSystem.mv`'s `overwrite` boolean — today it's binary (fail vs. force).
A behavior change (e.g. "merge non-empty directories instead of rejecting") would be introduced
as a *new*, additive mode rather than changing what `overwrite=true` means for existing callers
(changing existing semantics under existing callers is exactly the backward-compatibility trap
from Q17). Concretely: add a third state — an enum `CollisionPolicy { REJECT, REPLACE, MERGE }` —
with `REJECT`/`REPLACE` mapping to today's `false`/`true` exactly (so existing boolean callers via
a compatibility overload see zero behavior change), and `MERGE` as the new opt-in path. This is
the general pattern for evolving binary flags into richer policies without breaking anyone: widen
the type, map the old values onto a strict subset of the new ones, never repurpose an existing
value's meaning.

### 21. "Your tests create a brand-new `FileSystem` per test method. Is that actually testing the concurrency claims at realistic scale, or just demonstrating they don't immediately fail?"

Fair challenge, and worth being honest about: `ConcurrencyTest` uses 8–16 threads and small
subtrees (a handful of files), which is enough to *reliably exercise* the specific race windows
being tested (the lock is held or not — there's no probabilistic "mostly works" state with a
mutex-based design, unlike, say, testing an optimistic-retry scheme where thread-count and timing
genuinely affect whether a bug manifests). Because correctness here rests entirely on mutual
exclusion (a binary property — either the lock serializes correctly or it doesn't), a small test
is actually sufficient to prove the property, unlike testing, say, a lock-free CAS-retry algorithm
where bugs are famously load/timing-dependent and need much higher concurrency and longer runs to
surface. What this test suite does *not* prove is throughput/contention behavior at 10,000
concurrent sessions (Q8's concern) — that would need a load test, not a correctness test, and is
explicitly a different kind of test with a different purpose.

### 22. "You said `Session.cwd` is an `AtomicReference` for visibility. Is that actually sufficient, or does `cd` need to hold `treeLock` for a reason you haven't stated?"

`cd`'s implementation does take `treeLock.readLock()` — not for the `AtomicReference` write
itself (which is already safe/visible on its own), but because *resolving* the target path
(walking from `root` or from the current cwd through possibly many directories) must see a
consistent tree shape while it runs, exactly like any other read operation (`ls`, `pwd`). The
`AtomicReference` guarantees that once `cd` *has* resolved the target and calls `setCwd`, that new
value is visible to any other thread reading `Session.getCwd()` without needing its own lock
acquisition — this matters specifically because `Session` is a plain object with no lock of its
own; without `AtomicReference` (a plain non-volatile field), a reader thread could legally observe
a stale cwd value indefinitely under the Java Memory Model. So both mechanisms are load-bearing,
for different sub-problems: `treeLock` for "the resolution walk sees a consistent tree,"
`AtomicReference` for "the result of that walk becomes visible to other threads."

### 23. "What would 'extensibility challenge' look like if someone asked you to support case-insensitive filenames (like Windows/macOS default)?"

This cuts across almost every assumption name-uniqueness currently rests on. Today, uniqueness
and lookup are both keyed by exact `String` equality in a `LinkedHashMap<String, ...>` — `getChild`,
`hasChild`, `putChild`, `removeChild` all assume this. Case-insensitive lookup-but-case-preserving
storage (the actual common real-world behavior: `Documents` and `documents` are the same file, but
the display name keeps whichever casing was used to create it) can't just swap in a
case-insensitive `Comparator` on a `TreeMap`, because a plain hash map has no notion of a custom
equality function for its keys without wrapping every key. The clean fix is to key the map by a
normalized-case `String` but store the original-case name in the `FileSystemEntry` itself (which
this design already separates — `getName()` is independent of the map key) — meaning the
*existing* separation of "map key" from "entry's own name field" (not originally done with
case-insensitivity in mind) turns out to make this extension cheaper than it would have been if
`getChild` assumed `child.getName().equals(key)`. This is a good concrete example of "a seemingly
unrelated design choice paying off for a later requirement" to raise if asked about extensibility.

### 24. "Final challenge: what's the single architectural decision in this design that would be hardest to reverse later, and why?"

The single-tree-wide-lock-for-structure decision (§11) is the hardest to walk back cleanly,
specifically because every structural method's *correctness argument* (not just its performance)
currently depends on "the whole operation runs under one lock acquisition." Moving to
per-node locking later isn't a localized change — it requires re-deriving a lock-acquisition
ordering protocol and re-proving every recursive method (`cp`, `rm`, `mv`'s cycle check) correct
under the new scheme, essentially redoing the hardest 20% of this whole design from scratch,
rather than incrementally refactoring it. Contrast with, say, swapping `LinkedHashMap` for a
different map implementation (Q14) — that's a fully local, type-contained change with no
correctness re-derivation needed elsewhere. This asymmetry — "data-structure choices are cheap to
reverse, locking-protocol choices are expensive to reverse" — is the single most important
generalizable lesson from this problem, and is worth stating explicitly if asked "what would you
be most careful about getting right the first time."
