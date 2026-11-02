# Staff-Level Deep Dive — Google Drive / Cloud File Storage System

Twenty-four problem-specific, staff-level questions with reasoned (not one-line) answers. These
are the questions meant to find the edges of the design in this repo specifically — not generic
LLD boilerplate.

---

### 1. Two users concurrently upload byte-identical content for the first time (no blob exists yet). Walk through exactly what happens, instruction by instruction, and show there's no window where the blob is created twice or where one upload silently loses its reference.

Both threads compute the same SHA-256 hash and both call `blobStore.storeAndRetain(content)`,
which calls `ConcurrentHashMap.compute(hash, remappingFunction)`. `ConcurrentHashMap` guarantees
that for a *given key*, only one `compute` invocation executes at a time — internally this is
implemented as a lock on the table bin containing that key, held for the duration of the
remapping function. So the two calls are serialized by the map itself, not by any code we wrote:
whichever thread's `compute` call is scheduled first sees `existing == null`, constructs a new
`Blob` with `refCount = 1`, and that return value becomes the map's new value for that key. The
second thread's `compute` call cannot even *begin* running its remapping function until the first
one has returned (that's the atomicity guarantee), so it necessarily sees the just-inserted
`Blob` as `existing`, takes the `incrementRef()` branch, and returns the same object with
`refCount = 2`. There is no interleaving where both threads independently observe `existing ==
null`, because "observe the current value" and "install a new value" are the same atomic step for
the `compute` API — unlike `get()` followed by `putIfAbsent()`, which *would* have this race.

### 2. Suppose the design used `ConcurrentHashMap.putIfAbsent` + a separate `AtomicInteger` ref count on `Blob`, instead of `compute`. What exactly breaks, and would it break often or rarely in practice?

`putIfAbsent` atomically handles "is there already a blob for this hash", but the increment for an
*existing* blob would have to be a separate step: `Blob existing = map.get(hash); if (existing !=
null) existing.refCount.incrementAndGet();`. The break is between `get()` returning a non-null
`existing` and the subsequent `incrementAndGet()` call: another thread could, in that exact window,
be running `release()` on that same blob, observe `refCount` drop to zero, and remove it from the
map — at which point the first thread's `incrementAndGet()` succeeds on an `AtomicInteger` that
belongs to a `Blob` object no longer reachable from the map at all. The increment "succeeds" (no
exception) but is silently meaningless: a reference was recorded on an object the store no longer
tracks, and that "retained" blob can never be found again via `get(hash)` by the caller who thinks
they hold a valid reference, nor will its ref count ever be the basis of a future `release()`
check. In practice this is rare under light load (the window is a few nanoseconds) and would
manifest as an intermittent, environment-dependent "file download returns null / 404s" bug — the
worst kind of bug to debug precisely because it reproduces inconsistently and only under real
concurrent traffic, never in a single-threaded manual test.

### 3. Mid-operation crash: a process dies exactly between `quotaManager.reserve(...)` succeeding and `blobStore.storeAndRetain(...)` being called in `createFile`. In the current in-memory system, is this observable, and what would the equivalent failure mode look like in the DB-backed evolution from section 12?

In the current in-memory, single-JVM system, a process crash destroys all state (`ConcurrentHashMap`s
included), so there's nothing "left behind" to be inconsistent — on restart, everything is gone,
including the reservation. This failure mode is therefore unobservable in the as-built system,
which is an honest limitation of choosing in-memory storage, not a solved problem. In the
DB-backed evolution, this *is* a real and classic distributed-systems hazard: if "reserve quota"
and "write the blob row" are two separate transactions/statements, a crash between them leaves
`users.used_bytes` permanently inflated with no corresponding file ever created — a silent quota
leak that accumulates over time and is very hard to detect without an explicit reconciliation job
(sum of `file_versions.size_bytes` per owner vs. `users.used_bytes`, periodically). The fix is
making "reserve quota" and "create the node + version row" part of one database transaction (or,
if the blob write genuinely must go to a different system like object storage outside the
transaction boundary, using a saga/outbox pattern: write an intent record transactionally with the
quota reservation, and a reconciler compensates/reverses the reservation if the blob write never
completes within a timeout).

### 4. Why does `createFile` reserve quota *then* write the blob, while a more "fail-safe" ordering might seem to be write-then-check? Defend the ordering against a pointed challenge that it wastes a CAS operation on uploads that are about to be rejected anyway for being duplicate names.

The challenge conflates two different checks that happen in a very specific order for a reason:
name-collision is checked *first*, using only the (cheap, no side effects) read-locked
`hasChildNamed` lookup, *before* quota is ever touched — so a request doomed by a duplicate name
never reaches the quota CAS at all. Only after that cheap check passes does quota get reserved,
and only after quota succeeds does the (comparatively expensive, and in a real system, I/O-bound)
blob write happen. This is a strict "cheapest, most-likely-to-fail-fast checks first" ordering:
reject invalid requests before touching any shared contended resource, and only pay for actual
storage I/O once you're confident the request is going to succeed. The *remaining* risk — another
concurrent create of the same name slipping in between the first cheap check and the final
commit — is handled by the explicit re-check under the write lock (with compensating
quota/blob release on collision), which is strictly cheaper than reserving quota speculatively for
every request regardless of its eventual outcome.

### 5. The permission model lets an explicit grant at a deeper node override an inherited grant from an ancestor — including making it *more restrictive* (e.g., ancestor grants EDITOR, a specific file is explicitly set to VIEWER for that user). Is that the right default, and what's the failure mode if a product manager assumes the opposite ("most permissive wins")?

It's a defensible default and matches how real sharing products behave (you can share a folder
broadly, then lock down one sensitive file inside it more tightly), but it is a genuine product
decision with a real failure mode if assumed wrong: if callers/PMs expect "most permissive always
wins" semantics (i.e., being in *any* shared context should never make something *less* accessible
than it would otherwise be), then `InheritedAclPermissionStrategy`'s current behavior would be
reported as a bug ("I shared the whole folder as EDITOR, why can't I edit this one file inside
it?") when it's actually working exactly as coded. The fix isn't a code bug fix, it's confirming
the product semantics explicitly up front (clarifying question #1 in the README) and, if "most
permissive wins" is actually desired, changing `resolveEffectiveRole` to take the *maximum* role
found at any level in the chain instead of stopping at the first explicit entry found — a small,
localized change precisely because the strategy is isolated behind an interface, which is the
whole point of having pulled it out as a Strategy in the first place.

### 6. At 10 million users, what's the first thing in this design that falls over, and why that one specifically rather than something else?

The single global `ReentrantReadWriteLock` guarding tree structure is the first thing that falls
over — not because any individual operation is slow, but because it serializes every structural
write (create/rename/move/trash/restore/purge) across the *entire* user base in one process, when
in reality the vast majority of these operations are entirely independent (Alice creating a
folder has nothing to do with Bob moving a file). At small scale this coarse-grained lock is
invisible; at 10M users with meaningfully concurrent structural writes, it becomes a hard ceiling
on throughput regardless of how many CPU cores or how much memory you add, because adding more
threads just means more of them queued on the one lock. The CAS-based quota and `compute`-based
blob ref-counting, by contrast, scale naturally with user/content cardinality because they shard
contention down to "per user" and "per content hash" respectively — they are not the bottleneck.
The concrete fix (section 14) is sharding the structural lock (e.g., one per user's root subtree,
since this domain never moves things across users' root trees directly), not redesigning the
locking primitive itself.

### 7. Add a new requirement — "folders can have a size quota separate from the owning user's overall quota, e.g., a shared team folder with its own 50 GB cap" — without changing any existing public method signature. Is that possible with this design, and where would the change actually land?

Yes, and it's a good test of whether responsibilities were actually separated correctly. The
change lands almost entirely in `QuotaManager` and the orchestration inside `DriveService.
createFile`/`uploadNewVersion`: today, `reserve`/`release` take a single `userId` key; a
folder-level quota would mean a given upload needs to succeed against potentially *two*
independent quota checks — the uploading user's personal quota and (if the target folder has one
configured) the folder's quota — both via the same CAS-based `reserve` primitive, just keyed by
`folderId` instead of/in addition to `userId`. `QuotaManager` already has no notion of "what a
quota belongs to" baked into its API beyond a string key, so it can be reused unchanged for
folder-scoped quotas with zero modification to its CAS logic. The genuinely new work is: (a) a way
to look up "does this folder (or its nearest ancestor) have its own quota configured" — which is
structurally identical to permission inheritance and could reuse the exact same tree-walk
pattern as `PermissionResolutionStrategy`, and (b) `DriveService` calling `reserve` twice (user,
then folder) and, if the folder reservation fails after the user reservation succeeded,
compensating by releasing the user reservation before throwing — the same compensating-rollback
pattern already used for the duplicate-name race. No existing public method signature changes;
this is additive.

### 8. The design asserts `File`'s per-file `synchronized` monitor means "uploads to different files never contend." Is that actually true once you account for the structural read lock every upload also briefly takes at the start? Be precise.

Mostly true, with one precise caveat worth stating out loud rather than glossing over. Every
upload (`createFile`/`uploadNewVersion`) does take `structuralLock.readLock()` briefly at the
start to resolve the node and check permissions — and `ReentrantReadWriteLock`'s read lock is
shared (multiple readers proceed concurrently), so two uploads to two different files do *not*
block each other there; they only *would* contend if some third thread is simultaneously holding
the *write* lock for a structural operation (a create/move/rename/trash elsewhere in the tree),
in which case every reader — including unrelated uploads' brief read-lock acquisition — queues
behind that one writer. So the precise claim is: uploads to different files never contend with
each other *on the file-level monitor* (true, unconditionally, since they're different monitor
objects), but they can transiently contend with each other *indirectly*, via both queuing behind
an unrelated concurrent structural writer holding the tree-wide write lock. This is a real,
measurable (if usually brief) coupling between "someone somewhere is renaming a folder" and "your
unrelated file upload's initial node-resolution step" — exactly the cost called out as the
trade-off of a single coarse-grained structural lock in section 16.

### 9. Why is `DriveNode.aclEntries` a `CopyOnWriteArrayList` rather than being protected by the same structural lock that already exists? Isn't adding a second concurrency mechanism unnecessary complexity?

It's a deliberate choice to decouple two different kinds of "who can see what" state that change
at very different rates and are read from very different code paths. The structural lock protects
*tree shape* (parent/child pointers) and is taken by operations that change what the tree looks
like. ACL reads, by contrast, happen on *every single operation* (every permission check walks up
the chain reading `aclEntries` at each node) — including operations like `downloadLatest` that
intentionally do *not* take the structural lock at all (section 11.4) because they don't touch
tree shape. If ACL reads required the structural lock, every download would need to briefly
acquire it just to check permissions, reintroducing exactly the lock coupling described in
question 8, but now for *every* read instead of just the brief node-resolution step. Using a
`CopyOnWriteArrayList` means ACL reads need no lock at all — iteration is always over a
consistent, immutable snapshot array — while writes (grants/revokes, which are comparatively rare)
pay a full-array-copy cost. This is not accidental extra complexity; it's using the narrowest
synchronization mechanism that actually matches each piece of state's read/write ratio, rather
than reflexively reusing one lock for everything adjacent to it.

### 10. The design chose "every file version counts against quota forever, only purge releases it." A user complains their quota is nearly full despite "only having a few small files." What's actually happening, and is this a bug?

Not a bug — a direct, documented consequence of the chosen invariant (README sections 3 and 16):
if a user has been iteratively editing a handful of files many times (e.g., a document saved 200
times over a year), every one of those 200 versions' bytes still counts against quota even though
only the latest version is "visible" in normal use, because nothing except a full-file purge frees
version bytes. The user's mental model ("I have 3 small files") and the system's actual accounting
("you have 3 files × ~hundreds of retained versions each") have diverged, and that's a real
UX/support problem this design explicitly trades off against the alternative complexity of
version-level quota release (which, per the README, creates its own problem: what does it even
mean to "delete" the bytes for version 5 of 10 when version 6 might have been a no-op edit that
produced byte-identical content already deduplicated against version 5's blob?). The practical
mitigation that doesn't require re-architecting the invariant: expose version *count*/size as its
own queryable metric per file (trivial, `File.totalLogicalBytesAcrossAllVersions()` already exists
for exactly this), and offer a user-facing "clear version history older than N" action that is
really just a bulk, explicit purge of selected old `FileVersion` entries — a new, additive
operation, not a change to the core accounting rule.

### 11. `TrashService.purgePermanently` is described as "idempotent per node" via an `existsById` check. Does that actually make the *whole* operation idempotent, or only protect against exactly-once-per-node double-processing? Construct a scenario where idempotence at the node level is insufficient.

`existsById` protects against re-running the *entire* purge for a node that's already gone — it
does not protect against a crash *mid-way through processing a single node's multiple versions*.
Walk through it: `purgePermanently` iterates every `FileVersion` of a file and, for each, calls
`blobStore.release(...)` then `quotaManager.release(...)`, in a loop, before finally calling
`nodeRepository.deleteById(n.getId())` for that node *after* the loop over its versions completes.
If a crash happened after releasing the blob/quota for version 1 of 3 but before processing
versions 2 and 3, a naive retry that re-checks `existsById` would find the node *still* exists
(it's only deleted from the repository *after* all versions are processed) and would then
re-process *all three* versions — including re-releasing version 1's blob reference and quota a
second time, which is exactly the double-release bug this idempotence check was supposed to
prevent. The node-level `existsById` check is correctly idempotent for *whole-node* re-processing
across separate top-level `purgePermanently` calls (e.g., the retention sweep re-scanning), but
it is not sufficient on its own to make the *internal* per-version loop crash-safe mid-node. The
real fix for a persistent, crash-safe implementation is per-version idempotence: track which
specific `(fileId, versionNumber)` pairs have already had their blob ref/quota released (e.g., a
`purged_at` column on `file_versions`, checked before each release), not just per-node existence.
This repo's in-memory implementation doesn't need this (a JVM crash wipes all state including
partial progress, so there's no "partially purged node" to retry against), but the design
documentation (section 13) is honest that this is a gap specific to the hypothetical persistent
evolution, not a claim the in-memory code is crash-safe in a way it provably isn't.

### 12. Restoring a trashed item whose original parent folder was *also* trashed and subsequently purged: what does `restoreFromTrash` actually do, and is the resulting behavior something a user would consider correct?

Looking at the implementation: `restoreFromTrash` tries to resolve `record.getOriginalParentId()`
via `getFolderOrThrow`, and if that throws `NodeNotFoundException` (because the parent was
purged), it catches that specifically and falls back to `targetParent = null`. With `targetParent
== null`, the method skips the re-attach block entirely (it only renames/re-parents/adds-as-child
"if targetParent != null") and just clears the trash record — meaning the restored node ends up
existing in the repository (so it's not lost, and still downloadable/shareable by id) but
unreachable from any folder's children list, effectively "orphaned but alive." Whether that's
"correct" depends entirely on product expectations nobody asked about in the original
requirements: a reasonable product behavior would instead be "restore orphaned items to the
user's root folder with a note," which is a small, well-contained change (replace the `null`
fallback with a lookup of the user's root folder) but one this design explicitly does not make
silently — it's called out here specifically so it doesn't ship as a silent "your restored file
just vanishes from view" surprise. This is exactly the kind of edge case staff-level review should
catch before it reaches a user, not after.

### 13. Could two different physical `Blob` objects for the exact same content ever simultaneously exist in `InMemoryBlobStore`, even transiently? Prove it one way or the other.

No — and the proof is structural, not probabilistic. `storeAndRetain` always calls
`blobs.compute(hash, ...)`, and the *only* places a `Blob` is ever constructed in this codebase are
inside that one `compute` callback's `existing == null` branch. Since `ConcurrentHashMap.compute`
is guaranteed atomic per key, there can never be two concurrent executions of that callback for
the same hash — meaning there is no interleaving under which two threads both observe
`existing == null` for the same hash and each construct their own `Blob`. The map's invariant
"at most one `Blob` instance is ever reachable from a given hash key at any point in time" holds
by construction, not by locking discipline that a future change could accidentally violate
elsewhere in the code — there simply isn't anywhere else in the codebase a `Blob` for an existing
hash *could* be constructed, since `Blob`'s constructor is public but nothing outside
`InMemoryBlobStore.storeAndRetain`'s single call site is ever invoked with a hash that might
collide with an existing entry.

### 14. What happens if `HashingStrategy` is swapped to a non-cryptographic hash (e.g., CRC32) purely for a "simpler, faster" refactor someone proposes in a later PR? Trace the actual failure mode, not just "collisions are bad."

CRC32 is a 32-bit checksum designed for error detection, not collision resistance against
adversarial or even just large-scale incidental collision — with enough distinct files (the
birthday-paradox threshold for a 32-bit space is only about 77,000 files for a 50% collision
chance), two genuinely *different* files would eventually hash to the same `contentHash` value.
Because `BlobStore.storeAndRetain` treats equal hashes as equal content unconditionally (it never
compares actual bytes on a hash hit, by design, since byte comparison would defeat the performance
point of hashing), the *second* file to produce a colliding hash would silently be "deduplicated"
against the *first* file's actual content: the second user's upload would succeed, report what
looks like a normal new version/file, but any later download of that second file would return the
*first* file's bytes instead — silent data corruption with no error ever raised anywhere, the worst
possible failure mode for a storage system. The fix if a faster hash were genuinely needed for
performance reasons would be to keep the fast hash as a first-pass *lookup* key but fall back to a
full byte-for-byte comparison on any hash match before treating content as identical ("hash as an
index, not as proof") — a real technique used in some dedup systems — rather than trusting a
non-cryptographic hash as a unique content identity, which is the assumption this design's current
SHA-256 choice makes safe only because SHA-256 specifically has strong collision resistance.

### 15. The `move` operation's cycle check (`isAncestorOf`) walks from the destination folder up to the root checking for the moving node. What's the time complexity of this check, and is there a scenario in this design where that becomes a real cost rather than a rounding error?

`isAncestorOf` walks `candidateAncestor.getParent()` repeatedly until either hitting the moving
node (cycle found) or `null` (no cycle), so its cost is O(depth of the destination folder in the
tree) — bounded by however deeply nested folders are allowed to get, not by the total number of
nodes in the system. For realistic folder nesting (tens of levels at the absolute extreme for a
human-organized file tree) this is a handful of pointer dereferences and is genuinely a rounding
error next to the surrounding lock acquisition and map operations. It would only become a real
cost in a pathological scenario this design doesn't defend against: nothing currently caps maximum
folder nesting depth, so a (malicious or buggy) client could programmatically create an
extremely deep folder chain (thousands of levels) purely to make every subsequent `move`
targeting a folder near the bottom of that chain do thousands of pointer hops under the write
lock — a cheap-to-construct, denial-of-service-shaped cost amplification. The practical mitigation
(not implemented, worth naming if asked) is a simple maximum-depth check in `DriveNodeFactory`
at folder-creation time, which bounds this cost by a constant for free.

### 16. Why is `User` deliberately designed to hold *no* quota state, when it would be simpler for `QuotaManager.reserve` to just take a `User` object and mutate a field on it directly?

Putting quota counters directly on `User` would conflate two very different concurrency profiles
under one object: `User` as currently designed is an effectively-immutable identity/profile value
(id, name, email) that's safe to read from anywhere without any synchronization concerns at all,
while quota counters are exactly the opposite — extremely hot, extremely contended, mutated on
every single upload, requiring careful CAS-loop discipline to avoid the exact race analyzed in
question 1 of this document. If `used bytes` lived as a field on `User`, every piece of code that
needs to read a user's *name* for display purposes would be reading from the same object class
that also needs rigorous concurrency discipline for its *quota* fields — increasing the chance
that some future contributor, editing `User` for an unrelated reason (say, adding a
`lastLoginAt` field), touches the file containing the CAS loop and either breaks it by accident or
reasonably assumes (wrongly) that `User` in general needs defensive locking, over-synchronizing
code that didn't need it. Separating "identity" from "quota accounting" into two classes with two
different concurrency profiles makes each one simpler to reason about and to review in isolation
— this is the same instinct that led to extracting `QuotaManager` as its own service in the first
place (README section 4), just stated as a "why not do it the simpler-looking way" defense.

### 17. API evolution: six months from now, product wants "shared drives" (a folder owned by a team, not a single user, where OWNER-equivalent actions require majority team-admin approval instead of a single owner's say-so). How much of the existing API breaks?

None of the existing *method signatures* need to break, but a core assumption baked into the
domain model does: `DriveNode.ownerId` is a single `String` and `InheritedAclPermissionStrategy`
hard-codes "owner == OWNER role, full stop" as its very first check. "Shared drive" ownership by a
team-with-approval-workflow doesn't fit a single-string-owner model at all — the honest answer is
this requires a genuine model change, not a pure additive extension like the folder-quota example
in question 7. The contained version of that change: introduce an `Owner` abstraction (an
interface with at least `UserOwner` and `TeamOwner` implementations) in place of the raw
`ownerId: String` field, and move "is this user authorized to act as owner right now" out of
`InheritedAclPermissionStrategy`'s hard-coded identity check into a method on that `Owner`
abstraction (`owner.hasOwnerAuthority(userId)`, which for `UserOwner` is a simple equality check
and for `TeamOwner` would consult a separate approval-workflow service). Every *caller* of
`PermissionService`/`DriveService` is unaffected — they still call `requireRole(node, userId,
Role.OWNER)` exactly as before — because the change is isolated to how "is this userId the owner"
is decided, which was already behind the `PermissionResolutionStrategy` interface specifically so
this kind of change wouldn't ripple outward. This is a good illustration of where the original
Strategy extraction earns its complexity budget months later, and also an honest admission that
not every future requirement is free — some genuinely require touching the domain model, and
pretending otherwise would be dishonest about the design's actual flexibility.

### 18. Backward compatibility: if `Role` gained a new value between `VIEWER` and `EDITOR` (say, `COMMENTER`), what breaks, given that `Role.isAtLeast` relies on enum ordinal order?

This is precisely the risk flagged in `Role`'s own javadoc. Inserting `COMMENTER` between `VIEWER`
and `EDITOR` shifts every subsequent constant's ordinal value by one (`EDITOR` goes from ordinal 2
to 3, `OWNER` from 3 to 4) — any *persisted* representation of a role that stored the ordinal
integer directly (which nothing in this in-memory system does, but a naive DB column `role_ordinal
INT` in the section-12 evolution plausibly could) would silently reinterpret old stored values as
the wrong role after the enum change: a row that meant `EDITOR` (previously ordinal 2) would now
be read back as `COMMENTER` (now ordinal 2) — a silent privilege *change* (could go either
direction) with no error raised anywhere. The in-memory system as built is actually safe from this
specific failure because nothing serializes `Role` by ordinal — `AccessEntry` holds the enum value
itself in memory, and nothing persists it — but this is exactly the kind of landmine that's
invisible until the first persistence layer is added, which is why the javadoc flags it
preemptively rather than waiting for it to bite during that future migration. The durable fix
before ever persisting roles: store the enum's `name()` string (`"EDITOR"`) or an explicit,
manually-assigned integer `rank` field on each constant instead of relying on declaration-order
ordinal, so inserting new constants anywhere in the list is safe.

### 19. The `move` operation re-checks for a duplicate name under the write lock, but *not* for a cycle under some earlier "optimistic" read-locked pass the way `createFile` does its two-phase check. Is that an inconsistency, or deliberate?

It's deliberate, and the reason is that `move`'s entire sequence — resolving both nodes, checking
permissions, checking for a cycle, checking for a name collision, and performing the detach/attach
— happens as one *single* critical section under the write lock from start to finish (unlike
`createFile`, which splits into a cheap read-locked pre-check, unlocked quota/blob work, then a
write-locked commit specifically to avoid holding the write lock across the comparatively
expensive blob write). `move` never does any comparatively expensive I/O-like work (it's pure
in-memory pointer rewiring), so there's no performance incentive to split it into phases the way
`createFile` is split, and because it's one uninterrupted critical section, there is no window
between a "check" and a later "re-check" for anything to race into — the cycle check and the
name-collision check and the actual structural mutation all happen atomically with respect to
every other structural operation. This is actually the simpler and more obviously-correct of the
two patterns; `createFile`'s two-phase approach is more complex specifically because it has a
genuine reason (avoiding holding a coarse lock across real I/O) to accept that complexity, and
`move` doesn't have that reason, so it doesn't pay that cost.

### 20. For a 2-week ship deadline, what would you cut from this design, and — more importantly — what would you refuse to cut even under schedule pressure, and why?

Cut, in priority order: (1) the inverted-index search service entirely, replaced with a linear
scan over a user's reachable nodes filtered by substring match — functionally equivalent for
small-to-medium drives, just worse asymptotically, and asymptotics rarely matter in week one of a
real product's life; (2) the `NotificationService`/Observer wiring — nothing downstream depends on
it yet, it's pure speculative extensibility; (3) tiered/per-user trash retention — ship one global
constant; (4) the explicit compensating-rollback path in `createFile` for the duplicate-name race
— genuinely rare in practice (requires two clients racing on the identical filename in the
identical folder within a few milliseconds), and if it does occur, worst case is a confusing error
on the losing request, not data loss, so a "fine to not perfectly handle in v1" risk. Refuse to cut,
under any schedule pressure: the quota CAS correctness and the blob ref-counting correctness. These
are not "nice to have more robust" — the alternative is either users intermittently able to exceed
their storage limit (a resource-exhaustion and billing-integrity issue, not a cosmetic one) or
blobs freed while still referenced (silent data loss/corruption for a user who did nothing wrong).
Both are the kind of bug that doesn't show up in a demo, doesn't show up in a manual QA pass, and
then shows up in production as a confusing, hard-to-reproduce support ticket three weeks after
ship, at which point it's materially more expensive to diagnose and fix than it would have been to
get right the first time by spending the extra hour writing the CAS loop and the `compute()`-based
ref counting correctly from day one. This is the single clearest "staff judgment" call in the
whole design: not every correctness property is equally cuttable, and the ones that aren't are
precisely the ones that are invisible until they silently aren't true anymore.

### 21. `SearchService` tolerates stale index entries (a token pointing at a node id that no longer resolves) by silently skipping them in `search`. Is "tolerate and skip" actually safe, or could it produce a misleading result for the user?

It's safe in the sense that it can never cause a *wrong positive* result (you can't be shown a
node that doesn't exist — the `nodeResolver` returning `null` for a purged id means that id is
simply excluded from the results list, full stop). The risk is purely one of *staleness as a user
experience gap*, not a correctness gap: if index cleanup genuinely lagged (which, in the current
code, it doesn't — `TrashService.purgePermanently` calls `searchService.removeFromIndex`
synchronously in the same call that removes the node from the repository, so the window for a
stale entry to even exist is effectively zero in this implementation), a user could search and
briefly not find something that still technically exists (opposite direction — a false negative is
impossible here too, since removal happens, not addition being delayed). The actual purpose of the
"tolerate a null resolve" code path is defensive robustness against a *future* change that
decouples index cleanup from node deletion (e.g., moving to an async-rebuilt search index in
the production evolution), where eventual consistency between the index and the source of truth
becomes a real, intentional trade-off — the code is written to not crash if that trade-off is ever
introduced, even though today's synchronous cleanup means it never actually exercises that path.

### 22. Defend the choice to make every `DriveService` method take a `String userId` as the first parameter rather than, say, injecting a `CurrentUserContext`/`SecurityContext` the way a typical Spring application would.

Explicit `userId` parameters make every method's authorization dependency visible at its call
site and in its signature — reading `createFolder(String userId, ...)` tells you, without looking
at the implementation, that this operation is user-scoped and permission-checked; an implicit
`SecurityContext.getCurrentUser()` pattern hides that dependency inside the method body, which is
fine in a web framework with a well-understood request-scoped context but is actively worse for an
LLD artifact meant to be read and reasoned about as plain code, and it's outright *necessary*
here for a specific reason beyond style: several operations (`share`, `moveToTrash`) involve
*two* distinct identities at once — the actor performing the action and, separately, the resource's
existing owner/grantee being checked against — and a single ambient "current user" context doesn't
naturally extend to "and also, who is this *other* user I'm granting access to." Explicit
parameters make that two-identity shape unambiguous in the method signature itself
(`share(String requesterId, String nodeId, String granteeUserId, Role role)`), whereas an ambient
context pattern would need some parallel explicit parameter anyway for the second identity,
making the "cleaner" implicit pattern only partially implicit and arguably more confusing for
exactly the methods where getting the two identities straight matters most.

### 23. The design explicitly chose not to implement real-time collaborative editing (two users editing the same file simultaneously, Google-Docs style). If asked to sketch how you'd extend `FileVersion`/`File` to support that without redesigning everything, what would you say?

The honest first thing to say is that this is a fundamentally different problem from everything
else in this design — real-time collaborative editing needs either Operational Transformation or
CRDTs to merge concurrent *sub-document* edits, which requires the content itself to be modeled as
structured, mergeable operations (character/block inserts and deletes with causal ordering), not
as opaque `byte[]` blobs addressed by whole-content hash the way this design treats every file
uniformly. You cannot bolt OT/CRDT merging onto the existing `FileVersion`/`Blob` model without
changing what a "version" fundamentally means for collaboratively-edited file types specifically —
which is exactly why real products (Google Docs vs. Drive-stored binary files) actually use two
different underlying storage/sync models even though they present a unified UI. The honest,
scoped answer: this design's `FileVersion`/dedup/quota model remains exactly right for the vast
majority of file types in a drive product (PDFs, images, binaries, spreadsheets-as-files) where
"last write wins, with full history" is the correct and sufficient semantics, and collaborative
document types would be a parallel, specialized subsystem (a different content representation
entirely) that happens to live in the same folder tree and share the same permission/sharing
model (`DriveNode`/`Folder` composite, `PermissionService`) — composition and sharing stay reused,
content versioning does not, and pretending otherwise would be overengineering this design to
accommodate a requirement it was explicitly scoped not to solve.

### 24. If you had to add one single automated check to this codebase today that would catch the highest-value class of future regression, what would it be and why that one?

A property-based or heavily-parameterized concurrency regression test that specifically asserts
the *exact* quota-used-bytes and blob-ref-count invariants (not just "no exception was thrown")
under randomized concurrent workloads — generalizing `QuotaManagerConcurrencyTest` and
`BlobStoreConcurrencyTest` from fixed scenarios (50 threads, 100-byte requests) into a loop that
randomizes thread count, request size, and timing, re-running many iterations in CI. The reasoning
for *this specific* check over any other: every other correctness property in this design (naming
uniqueness, cycle prevention, permission resolution) is a straightforward, deterministic unit-test
target where a regression would reliably fail on the very first CI run after being introduced —
but concurrency bugs are exactly the class of regression that a future refactor (e.g., someone
"simplifying" the CAS loop into a `synchronized` block that looks equivalent but has a subtly
wrong critical-section boundary) could introduce and have it pass every existing fixed-parameter
test by sheer luck of thread-scheduling timing on that one CI run, only to then manifest
intermittently in production weeks later exactly as described in question 2. Broadening the
concurrency tests' parameter space and iteration count directly targets that blind spot, and it's
the one category of bug in this entire design where "the tests passed" is weakest evidence of
actual correctness unless the tests are specifically designed to hunt for timing-dependent
failures rather than just exercise the happy path under contention.
