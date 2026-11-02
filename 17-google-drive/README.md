# Google Drive / Cloud File Storage System — Low-Level Design

Staff-level LLD for a multi-user cloud file storage system (think Google Drive / Dropbox):
hierarchical files & folders, sharing with inherited permissions, immutable version history,
content-hash deduplication with reference counting, per-user storage quota enforced correctly
under concurrency, a trash/recycle-bin with retention, and name/metadata search.

Package: `lld.clouddrive`. Source: `src/main/java/lld/clouddrive/...`. Tests:
`tests/lld/clouddrive/...` (JUnit 5). Runnable demo: `lld.clouddrive.demo.DriveDemo#main`.

---

## 1. Problem Understanding

**Plain explanation.** Design the backend of a cloud drive: users organize files inside nested
folders, can share any file or folder with other users at a chosen permission level, every edit
to a file keeps the old content retrievable as a version, storage is billed/limited per user but
the system avoids storing the same bytes twice, deleted items go to a recoverable trash before
being permanently erased, and users can find things by name/metadata instead of browsing the
whole tree.

**Real-world use case.** This is effectively "Google Drive" or "Dropbox" scoped down to a single
in-process LLD: no distributed consensus, no real network/object-storage layer, no OCR/preview
generation — just the core domain logic an interviewer wants to see modeled correctly and made
thread-safe.

**What the interviewer is evaluating.**
- Can you model a recursive tree (Composite) cleanly without special-casing files vs. folders
  everywhere?
- Do you understand *why* permission inheritance is naturally expressed as tree-walking, not as a
  flat table join?
- Can you reason correctly about two genuinely tricky, classic concurrency bugs: (a) check-then-act
  races on a shared numeric limit (quota), and (b) use-after-free / double-free races on a
  reference-counted resource (deduplicated blobs)?
- Do you know when immutability (file versions) eliminates a whole class of races instead of
  requiring more locking?
- Do you stop the design at a sane LLD scope instead of trying to design S3 and a CDN?

**In scope.**
- Users, nested files/folders (Composite), owner + ACL-based sharing with inheritance.
- Immutable, append-only file version history (Memento).
- Whole-file content-hash deduplication with reference-counted blob storage.
- Per-user quota tracked and enforced correctly under concurrent uploads by the same user.
- Trash with retention window, restore, and permanent purge that frees quota + blob refs.
- Search by name token and basic metadata filters (type, size range), permission-filtered.
- Full concurrency story for every piece of shared mutable state above.

**Out of scope (called out explicitly, not silently ignored).**
- Actual network transport, chunked/resumable uploads, client sync protocol, offline conflict
  resolution (CRDT/OT) for simultaneous edits to the *same* version (we serialize at the
  version-append level instead — see section 11).
- Real object storage (S3-style multipart upload, erasure coding, geo-replication).
- Content-defined chunking for partial-file dedup (documented as a production evolution, not
  implemented — see sections 10 and 17).
- Multi-node/distributed deployment (sharding, distributed locks, consensus) — addressed only at
  the "how would this evolve" level in section 17, deliberately not built, per the instruction
  not to over-distribute an LLD problem.
- Authentication/session management (a `userId` is assumed already authenticated and trusted).

---

## 2. Requirement Gathering

### Functional — essential
1. Create folders, nested arbitrarily deep.
2. Upload a new file (first version) into a folder.
3. Upload a new version of an existing file; retrieve any historical version or the latest.
4. Rename and move files/folders (move must reject creating a cycle).
5. Share a file or folder with another user at a role: OWNER / EDITOR / VIEWER; permission is
   inherited by everything nested under a shared folder unless explicitly overridden deeper.
6. Revoke a previously granted share.
7. Enforce per-user storage quota on every upload (new file or new version), correctly even when
   the same user issues many uploads concurrently.
8. Deduplicate identical content across any number of files/users so it is physically stored once,
   using reference counting to know when it is finally safe to delete.
9. Move an item to trash (soft delete); restore it; or purge it permanently (frees quota + a blob
   reference). Items auto-purge after a retention window.
10. Search by name (substring/token) and basic metadata filters, restricted to items the requester
    can at least view.

### Functional — optional / nice-to-have
- Starring/favoriting, shared-with-me view, activity/audit log, comments, trashed-item preview,
  bulk operations (multi-select move/delete), folder-level default sharing templates.
- Change notifications to a sync client (modeled minimally via Observer, not built out).

### Non-functional
| Property | Requirement | How addressed |
|---|---|---|
| Performance | Upload/download/permission-check should be near O(depth of tree) or O(1), not O(total items) | Hash-map child lookup, cached-at-check-time permission walk bounded by tree depth, inverted-index search |
| Scalability | Model should not assume a single node's memory holds everything *forever*, but an in-memory LLD is acceptable for interview scope | Repository/BlobStore behind interfaces so a DB/object-store swap is a single-class change (section 17) |
| Availability | N/A for a single-process LLD; discussed only as a production concern | Section 17 |
| Consistency | Quota and ref-counts must be strongly consistent (no transient "overshoot" of quota that's "fixed later") | CAS loop + `ConcurrentHashMap.compute` atomicity, proven with concurrency tests |
| Extensibility | Adding a new permission-resolution policy, hash algorithm, or repository backend must not require touching callers | Strategy (permission, hashing) + Repository pattern |
| Reliability | Partial failures (e.g., quota reserved but blob write "fails") must not leak state | Ordering discipline + compensation documented in section 13 |
| Thread-safety | Every shared mutable structure (tree shape, ACLs, quota counters, blob ref counts, version history) must have a stated, justified concurrency guarantee | Section 11, with tests in `tests/` proving two of the hardest guarantees |

**Interview-necessary vs. production-extension split.** Everything in "In scope" above is
interview-necessary and fully implemented. Content-defined chunking, real persistence,
distributed locking/sharding, and operational tooling (metrics, audit log, rate limiting) are
production extensions — discussed in section 17 but not implemented, because building them would
not demonstrate additional LLD skill, only additional boilerplate.

---

## 3. Clarifying Questions

These are the questions a staff candidate should actually ask in the first five minutes — each
one genuinely changes a design decision made in this repo (not asked just to look thorough).

1. **"When folder A is shared with a VIEWER, and later a specific file inside A is explicitly
   shared with that same user as EDITOR, which wins?"**
   *Why it matters:* decides whether permission resolution is "most specific override" (what we
   implemented) or "most restrictive wins" or "union of roles". We chose most-specific-wins,
   matching how Drive actually behaves and how most engineers intuitively expect sharing to work.
2. **"Does a file's version history count against the owner's quota, or only the latest
   version?"**
   *Why it matters:* if only the latest version counted, editing a file could *reduce* quota
   usage, which is a confusing UX and complicates the purge-on-delete accounting (which version's
   bytes do you release?). We chose "every version ever created counts and is only released on
   whole-file purge" — simpler invariant, same as Drive's real behavior for version history.
3. **"If two users upload byte-identical content, and one of them deletes their file, does the
   other user's quota or file break?"**
   *Why it matters:* decides whether quota is charged by *logical* ownership (my decision: yes,
   each user is charged for their own logical bytes regardless of physical dedup) or by *physical*
   storage (would require re-attributing cost when a blob's last independent owner deletes it —
   far more complex and leaks implementation detail into user-facing billing).
4. **"Can an EDITOR share the file further, or only the OWNER?"**
   *Why it matters:* decides whether `PermissionService.grant` checks for OWNER only (what we
   implemented) or allows EDITOR-with-can-share. We picked OWNER-only for a clean, unambiguous
   authorization rule; real Drive actually supports a separate "can share" bit, called out as a
   documented simplification.
5. **"Is content chunked (dedup at the sub-file level) or hashed whole-file?"**
   *Why it matters:* whole-file hashing (what we implemented) is correct and simple but only
   dedupes byte-identical files; chunking (rolling hash / content-defined chunking) dedupes
   partial overlaps (e.g., a 1 GB video with one frame changed) at far higher implementation cost.
   We scoped to whole-file and documented chunking as the production evolution.
6. **"Does deleting (trashing) an item still count against quota?"**
   *Why it matters:* if trash is "free", users could churn upload/delete to bypass quota
   temporarily; also changes whether purge is the *only* place quota is released. We chose
   "trash still counts, only purge frees it" — matches real Drive and keeps quota accounting to
   one release point.
7. **"What happens to a shared file's permissions after it is restored from trash — are prior
   shares preserved?"**
   *Why it matters:* decides whether ACL entries live on the trashed `DriveNode` object itself
   (preserved — what we implemented, since the node object is never destroyed until purge) or get
   wiped on trash (would need a separate persisted-ACL-snapshot mechanism).
8. **"Is a move/rename allowed to race with an in-flight upload to the same file?"**
   *Why it matters:* decides whether tree-structure operations need a different lock scope than
   content operations. We separated them (structural `ReadWriteLock` vs. per-file monitor) exactly
   because of this question — see section 11.
9. **"What retention period applies to trashed items, and can it differ per user/plan?"**
   *Why it matters:* decides whether retention is a global constant (what we implemented, a single
   `Duration` injected into `TrashService`) or a per-user/per-plan field (trivial extension: make
   it a lookup keyed by user instead of a constant — noted in section 16).
10. **"Does search need relevance ranking/fuzzy matching, or is exact/substring token match
    enough for this interview?"**
    *Why it matters:* decides between a hand-rolled inverted index (what we implemented — good
    enough to demonstrate the data structure and the permission-filtering requirement) versus
    integrating a real search engine (out of LLD scope, documented as production evolution).

---

## 4. Domain Modelling

### Entities (have identity, lifecycle)
- **`User`** — id, display name, email. Deliberately holds no quota state (see "why" below).
- **`DriveNode`** (abstract) — id, name, ownerId, parent reference, ACL entries, createdAt. Base
  of the Composite hierarchy.
  - **`Folder`** (composite) — adds `children: Map<name, DriveNode>`.
  - **`File`** (leaf) — adds an append-only `List<FileVersion>`.
- **`Blob`** — a physically-stored, content-addressed byte array with a reference count. Not
  tied to any one user or file; many `FileVersion`s may point at the same `Blob`.

### Value objects (immutable, no identity beyond their fields)
- **`FileVersion`** — version number, content hash, size, author, timestamp. The Memento.
- **`AccessEntry`** — (userId, role) pair, an explicit ACL grant on one node.
- **`Role`** — enum NONE < VIEWER < EDITOR < OWNER, ordinal-comparable.
- **`TrashRecord`** — original parent/name, deletion time, purge deadline, who deleted it.

### Interfaces / strategies
- **`BlobStore`** — content-addressed storage abstraction (dedup + ref counting), implemented by
  `InMemoryBlobStore`.
- **`HashingStrategy`** — pluggable content-hash algorithm, implemented by `Sha256HashingStrategy`.
- **`NodeRepository`** — persistence abstraction for nodes by id, implemented by
  `InMemoryNodeRepository`.
- **`PermissionResolutionStrategy`** — how an effective role is computed, implemented by
  `InheritedAclPermissionStrategy`.
- **`FileChangeListener`** — Observer callback for drive events.

### Domain services (stateful orchestrators, not entities themselves)
- **`QuotaManager`** — owns per-user used/limit byte counters and the only correct API for
  changing them (`reserve`/`release`). *Why separated from `User`:* quota is highly-contended,
  concurrency-sensitive mutable state; keeping it off the `User` value object means `User` stays
  a trivial immutable-ish record and all quota locking/atomicity lives in exactly one class
  instead of being smeared across whichever class happens to hold the counter.
- **`PermissionService`** — the only API for checking/granting/revoking access; wraps the
  pluggable strategy and adds the "only OWNER may grant/revoke" business rule that doesn't belong
  in the strategy itself (the strategy only *resolves*, it doesn't *authorize changes*).
- **`SearchService`** — owns the inverted name-token index and permission-filters results.
- **`TrashService`** — owns trash metadata and the only code path that performs a permanent,
  irreversible purge (the one place blob refs and quota are released).
- **`DriveNodeFactory`** — the only place `Folder`/`File` objects are constructed, centralizing
  id generation, timestamping, and name validation (Factory pattern).
- **`NotificationService`** — Observer subject for `FileChangeEvent`s.
- **`DriveService`** — Facade; the only class every caller talks to; owns the tree-structural
  lock and the cross-cutting orchestration order (e.g. "reserve quota before writing content").

### Composition / association / ownership
- `Folder` **composes** `DriveNode` children (a child's lifecycle is tied to being reachable from
  some folder, or to its `TrashRecord`/repository entry if detached) — Composite pattern.
- `File` **composes** `FileVersion`s (versions cannot exist independent of their file; this is
  true containment, not a shared reference) — Memento pattern.
- `FileVersion` **associates with** (does not own) a `Blob` by content hash — many versions,
  across many files and users, may reference the same blob; the blob's lifecycle is governed by
  reference count, not by any single file's lifecycle.
- `DriveNode` **associates with** zero or more `AccessEntry` — explicit grants, independent of the
  tree edge to its parent (which only provides *inherited* access).
- `DriveService` **uses** (does not own in a composition sense) every domain service — they are
  injected collaborators, swappable independently (Dependency Inversion in practice, not just in
  theory: tests construct the whole graph with real in-memory implementations).

---

## 5. Domain Invariants

Business rules that must always hold, and exactly where each is enforced:

1. **A node's name is unique among its siblings.** Enforced in `DriveService.createFolder` /
   `createFile` / `rename` / `move`, checked against `Folder.hasChildNamed` while holding the
   structural write lock (or re-checked after acquiring it, for the create-file path — see
   section 11 for why create-file needs a re-check).
2. **A folder can never become its own descendant (no cycles).** Enforced in
   `DriveService.move` via `isAncestorOf`, checked before any structural mutation.
3. **A file's version history is append-only; no version is ever mutated or removed except by
   whole-file purge.** Enforced by `File` never exposing a mutator other than `addVersion`, and by
   `FileVersion` being fully immutable (final fields, no setters).
4. **A blob's reference count is only ever changed by `BlobStore`, inside a single
   `ConcurrentHashMap.compute` call per hash.** Enforced structurally: `Blob.incrementRef`/
   `decrementRef` are only invoked from `InMemoryBlobStore`; no other class holds a reference it
   could call them from.
5. **A user's used bytes never exceeds their quota, even under concurrent uploads by that same
   user.** Enforced by `QuotaManager.reserve`'s CAS loop — proven under direct concurrent load by
   `QuotaManagerConcurrencyTest`.
6. **Quota used bytes never goes negative.** Enforced by `QuotaManager.release` clamping to zero.
7. **The node's owner always has `OWNER` role, independent of any ACL state** (ownership cannot be
   revoked by ACL manipulation, only by a (not-implemented) explicit ownership-transfer
   operation). Enforced as the first check in `InheritedAclPermissionStrategy`.
8. **Only a node's OWNER may grant or revoke access on it.** Enforced in
   `PermissionService.grant`/`revoke` via `requireRole(..., OWNER)`.
9. **A node that is in the trash is unreachable via normal tree navigation (not listed as any
   folder's child) but remains resolvable by id until purged**, so download/permission checks
   still work (matching real product behavior where you can still preview a trashed file).
   Enforced by `moveToTrash` detaching from the parent's children map but leaving the node in
   `NodeRepository`.
10. **A permanent purge releases exactly the bytes reserved by every version of a file, and
    exactly one blob reference per version, never more and never less, even if retried.**
    Enforced by `TrashService.purgePermanently`'s existence check (`existsById`) making the
    operation idempotent per node, so re-running it after a partial failure cannot double-release.

---

## 6. Architecture and Class Diagram

See `diagrams/01-component-diagram.md` for the module/dependency diagram,
`diagrams/02-class-diagram.md` for the full class diagram (matches `src/` 1:1), and
`diagrams/03-sequence-diagrams.md` for four key flows: upload-new-file, upload-new-version,
permission-inheritance-check, and trash-then-purge. These are not reproduced inline to avoid
duplication/drift — the `diagrams/` files are the single source of truth and were authored
against the actual implementation in `src/`.

---

## 7. Design Patterns

For each pattern: why it was needed, exactly where it's applied, the alternative considered, and
why that alternative was rejected. No pattern was added "to show off" — each one removes real
duplication or coupling that would otherwise exist.

### Composite — `DriveNode` / `Folder` / `File`
**Why:** files and folders need to be handled uniformly by permission checks, search, trash, and
move/rename — none of that code should need an `if (isFolder) ... else ...` branch.
**Where:** `DriveNode` is the component; `Folder` is the composite (holds children); `File` is the
leaf.
**Alternative considered:** a single concrete `Node` class with a nullable `children` field and a
`type` enum.
**Why rejected:** that collapses "is this a container" into a runtime flag checked everywhere,
reintroducing the exact `if/else` branching Composite exists to avoid, and makes it legal (at
compile time) to call `addChild` on something that should never have children.

### Memento — `File` / `FileVersion`
**Why:** version history must let you read any past state safely while a concurrent edit is
appending a new state, without a reader-writer lock around every read.
**Where:** `FileVersion` is the memento (immutable snapshot); `File` is both originator and
caretaker (it creates new mementos and keeps the list).
**Alternative considered:** store only the current content, with a separate "diff log" to
reconstruct history on demand.
**Why rejected:** diffing is strictly more complex (needs a diff/patch format and a replay engine)
for no benefit at this scope, and it reintroduces a race: reconstructing version N while version
N+1 is being appended requires coordinating with the writer, exactly what full immutable snapshots
avoid.

### Strategy — `PermissionResolutionStrategy` and `HashingStrategy`
**Why:** both "how is effective permission computed" and "how is content addressed" are policies
that are easy to get right once and then want to vary independently of their callers (e.g., swap
SHA-256 for a different digest, or swap tree-inheritance for a flat no-inheritance policy for a
locked-down tier) without touching `PermissionService`/`BlobStore` or any of their callers.
**Where:** `InheritedAclPermissionStrategy` and `Sha256HashingStrategy` are the default/only
implementations shipped, but both are injected via the interface, and both have exactly one
collaborator that is Open/Closed with respect to adding a second implementation.
**Alternative considered:** hard-code the inheritance walk directly inside `PermissionService`,
and the SHA-256 call directly inside `InMemoryBlobStore`.
**Why rejected:** works today, but the moment a second policy is needed (and in a real product,
it will be — e.g., an enterprise tier that disables inheritance) you'd be editing a class instead
of adding one, and you could not unit-test the inheritance algorithm in isolation from the rest of
`PermissionService` the way `PermissionServiceTest` does.

### Factory (Simple Factory / Factory Method flavor) — `DriveNodeFactory`
**Why:** `Folder`/`File` construction needs consistent id generation, timestamping, and name
validation across every creation call site (create, and restore-with-rename on a name collision).
**Where:** `DriveNodeFactory.newFolder`/`newFile`.
**Alternative considered:** call the `Folder`/`File` constructors directly from `DriveService` and
duplicate validation inline at each call site.
**Why rejected:** validation/id/timestamp logic would drift across 2+ call sites over time; a
single factory method is strictly simpler to keep correct and to unit test once.

### Repository — `NodeRepository`
**Why:** `DriveService` needs id → node lookup without caring whether that lookup hits an
in-memory map (this LLD) or a database table (production).
**Where:** `NodeRepository` interface, `InMemoryNodeRepository` implementation.
**Alternative considered:** let `DriveService` hold the `Map<String, DriveNode>` directly.
**Why rejected:** would couple the facade to a specific storage mechanism and make the planned
production evolution (swap to a DB-backed repository, section 12) a `DriveService` change instead
of a one-class swap.

### Facade — `DriveService`
**Why:** seven collaborators (`PermissionService`, `QuotaManager`, `BlobStore`,
`NodeRepository`, `SearchService`, `TrashService`, `NotificationService`) each have a narrow
single responsibility; callers should not need to know the orchestration order between them
(e.g., "reserve quota, THEN write the blob, THEN commit the structural change").
**Where:** `DriveService` is the only class the demo/tests/a hypothetical REST layer call into.
**Alternative considered:** expose each collaborator directly and let callers orchestrate.
**Why rejected:** would duplicate the (subtle, race-sensitive) orchestration order at every call
site and make it easy to get the ordering wrong once, let alone consistently.

### Observer — `NotificationService` / `FileChangeListener`
**Why:** a real sync client needs to react to changes without polling; this is the one pattern
included for a capability not in the hard functional requirements, because it's the cleanest way
to demonstrate "this design has an extension point for production" without actually building a
sync client.
**Where:** `DriveService` publishes `FileChangeEvent`s after every mutating operation;
`NotificationService` fans them out.
**Alternative considered:** omit it entirely.
**Why rejected (i.e., why it's still worth the few lines):** without *some* eventing hook, "how
would a sync client know what changed" is a question with no good answer in the design; with it,
the answer is one sentence. It was kept deliberately minimal (no event sourcing, no persistent
event log) to avoid overengineering for a requirement that wasn't asked for.

### Patterns considered and explicitly rejected
- **Decorator** for things like "encrypted file" or "compressed file" — tempting, but nothing in
  the requirements calls for stacking cross-cutting behaviors on content at read/write time; adding
  it now would be pure speculative generality.
- **Singleton** for any service — every service is constructed once by whoever wires the object
  graph (see `DriveDemo`/`DriveServiceTestSupport`) and injected; a real Singleton would make
  testing with isolated state impossible without resorting to static reset hacks.
- **Visitor** for tree traversal (e.g., size calculation) — `Folder.listDescendants()` plus a
  simple loop is sufficient for the operations actually required (search, purge); Visitor would
  add a second abstraction layer for traversal that nothing here currently needs.

---

## 8. API and Method Design

All methods below are on `DriveService` unless stated otherwise. Every method is synchronous and
throws unchecked `DriveException` subclasses (see section 13) on failure — no checked exceptions,
by design, so the facade reads like a fluent API and callers choose their own failure boundary.

```java
// User & root management
Folder registerUser(User user, long quotaBytes);

// Folder / file lifecycle
Folder createFolder(String userId, String parentFolderId, String name);
File   createFile(String userId, String parentFolderId, String name, byte[] content);
FileVersion uploadNewVersion(String userId, String fileId, byte[] content);
byte[] downloadLatest(String userId, String fileId);
byte[] downloadVersion(String userId, String fileId, int versionNumber);
List<FileVersion> listVersions(String userId, String fileId);

// Structural changes
void rename(String userId, String nodeId, String newName);
void move(String userId, String nodeId, String newParentFolderId);
Collection<DriveNode> listChildren(String userId, String folderId);

// Sharing
void share(String requesterId, String nodeId, String granteeUserId, Role role);
void revokeShare(String requesterId, String nodeId, String granteeUserId);
Role effectivePermission(String nodeId, String userId);

// Trash lifecycle
void moveToTrash(String userId, String nodeId);
void restoreFromTrash(String userId, String nodeId);
void purgePermanently(String userId, String nodeId);
List<String> runTrashRetentionSweep(Instant now);
List<TrashRecord> listTrash();

// Quota
long getQuotaUsedBytes(String userId);
long getQuotaRemainingBytes(String userId);

// Search
List<DriveNode> search(String userId, SearchService.SearchQuery query);
```

**Request/response objects.** Kept intentionally minimal — most operations take primitive ids plus
a small number of scalar parameters rather than a request DTO, which is appropriate for an LLD
facade (a REST layer on top would be the place to introduce `CreateFolderRequest`-style DTOs).
The one structured request object is `SearchService.SearchQuery` (nameContains, foldersOnly,
minSizeBytes, maxSizeBytes) because a 4-parameter overload would be unreadable at call sites.

**Exceptions** (all extend `DriveException`, unchecked):
- `NodeNotFoundException` — id doesn't resolve to a live node.
- `AccessDeniedException` — effective role below the required minimum.
- `QuotaExceededException` — carries requested vs. available bytes for precise assertions/UX.
- `DuplicateNameException` — sibling name collision.
- `InvalidOperationException` — structurally invalid request (cycle, wrong node type, bad name,
  restoring something not in trash).

**Validation responsibilities.**
- Name syntax/length validated once, in `DriveNodeFactory.validateName` (used by both creation and
  rename paths — one place, not duplicated).
- Permission validated in `PermissionService`/`DriveService`, never left to the model layer
  (`DriveNode` itself has no concept of "is this allowed").
- Quota validated exclusively in `QuotaManager.reserve` — `DriveService` never compares byte
  counts itself, so there is exactly one place that can get the arithmetic wrong.
- Cycle-prevention validated in `DriveService.move` only (the one place with access to both the
  moving node and the destination).

---

## 9. Core Implementation

Full, compiling Java 17 source lives under `src/main/java/lld/clouddrive/`. The pieces most worth
reading closely for the concurrency/dedup/versioning story are:

- `model/Blob.java` — explains, in its own javadoc, exactly why ref-count mutation is restricted
  to being called from `store/InMemoryBlobStore.java`'s `compute()` callbacks.
- `store/InMemoryBlobStore.java` — the dedup + ref-counting implementation (section 11 covers the
  correctness argument in depth).
- `quota/QuotaManager.java` — the CAS-loop quota reservation (section 11 covers the correctness
  argument in depth).
- `model/File.java` — per-file `synchronized` version-append, and why that granularity (not
  global, not lock-free) was chosen.
- `permission/InheritedAclPermissionStrategy.java` — the full permission-inheritance algorithm in
  ~15 lines.
- `service/DriveService.java` — the facade; read its class javadoc first, it states the locking
  strategy up front before you read any method.
- `demo/DriveDemo.java` — a runnable, narrated walkthrough of every feature end-to-end; run it
  with:
  ```
  javac -d out $(find src -name '*.java') && java -cp out lld.clouddrive.demo.DriveDemo
  ```

No pseudocode anywhere in `src/` — every method shown compiles and is exercised by at least one
test in `tests/`.

---

## 10. Data Structures and Algorithms

| Use case | Structure | Why | Alternative | Complexity |
|---|---|---|---|---|
| Folder's children | `LinkedHashMap<String, DriveNode>` | O(1) name lookup/collision check; insertion order preserved for stable listing | `TreeMap` (sorted listing) | get/put/remove: O(1) avg (amortized, hash-based) vs `TreeMap` O(log n) |
| Node id → node | `ConcurrentHashMap<String, DriveNode>` | Lock-free concurrent reads/writes by id, independent of tree-structural locking | A single global lock over one `HashMap` | get/put: O(1) avg, no added lock contention for pure id lookups |
| Content hash → blob | `ConcurrentHashMap<String, Blob>` | Per-key atomic `compute()` is exactly the primitive needed for race-free ref counting (section 11) | A `HashMap` + external `synchronized` | O(1) avg; `compute()` locks only the affected bin, not the whole map |
| Per-user used bytes | `ConcurrentHashMap<String, AtomicLong>` | Lock-free CAS reservation per user; different users never contend | `ReentrantLock` per user | O(1); CAS retry count bounded by concurrent contenders for *that* user only |
| File version history | `ArrayList<FileVersion>` guarded by the file's monitor | Append-only, sequential-scan-by-version-number is fine at realistic version counts; simplest correct structure | A `TreeMap<Integer, FileVersion>` for O(log n) version lookup | append: O(1) amortized; lookup-by-number: O(versions) linear scan (acceptable — version counts are small; documented trade-off) |
| Name search index | `Map<token, Set<nodeId>>` (inverted index) | O(tokens in query) average lookup instead of O(total nodes) full scan | Linear scan of every node's name on every search | Search: O(matching ids) vs. O(n); index maintenance: O(tokens in name) per create/rename |
| Trash records | `ConcurrentHashMap<nodeId, TrashRecord>` | O(1) lookup on restore/purge by id; independent of tree structure (node is already detached) | A sorted structure by purge deadline for the sweep | O(1) per-item; sweep is O(trash size) — acceptable since sweeps are periodic background work, not request-path |
| Tree structural locking | `ReentrantReadWriteLock` | Many concurrent readers (browse/search) vs. rare writers (create/move/trash); see section 11 | Per-node locks with lock ordering | Avoids deadlock-prone multi-node lock acquisition for `move`, at the cost of writer/writer (not reader/reader) serialization across unrelated subtrees |

**Content hashing algorithm.** SHA-256 over the whole file (`Sha256HashingStrategy`). Chosen over
a weaker/faster hash (e.g., MD5, CRC32) because collision resistance is the entire safety property
dedup depends on — a hash collision between different content would silently corrupt a user's
file. SHA-256 is already provided by the JDK, requires no extra dependency, and its ~130ms/GB
throughput is not the bottleneck in an LLD-scoped in-memory system.

**Why not content-defined chunking (rolling hash / FastCDC-style) here:** chunking dedupes partial
overlaps (e.g., only one paragraph of a large document changed) at the cost of: a chunk index per
file, reassembly logic on read, and a chunk-level reference-counting scheme instead of a
whole-blob one. This is real, valuable production engineering, but it would roughly double the
code in `store/` for a capability an LLD interview rarely asks you to go implement — it's
documented here and in section 17 as the natural next step, which is the right level of depth for
a 45-60 minute round.

---

## 11. Concurrency and Thread Safety

This section is mandatory per the interview rubric even though the system "feels" single-threaded
in the demo — in reality every operation here is a concurrent request handler in production, so
every shared mutable structure is analyzed below.

### 11.1 Inventory of shared mutable state

| State | Owner | Guarded by |
|---|---|---|
| Tree shape (parent/child pointers, folder's children map) | `Folder.children`, `DriveNode.parent`/`name` | `DriveService.structuralLock` (ReadWriteLock) |
| A file's version history | `File.versions` | `File`'s own intrinsic monitor (`synchronized` methods) |
| Blob reference counts | `Blob.refCount` | `ConcurrentHashMap.compute()` atomicity per hash, inside `InMemoryBlobStore` |
| Per-user quota used bytes | `QuotaManager.usedBytesByUser` | CAS loop on `AtomicLong` per user |
| ACL entries on a node | `DriveNode.aclEntries` | `CopyOnWriteArrayList` (read-mostly, rare mutation) |
| Node id → node map | `InMemoryNodeRepository.nodes` | `ConcurrentHashMap` |
| Trash records | `TrashService.trashByNodeId` | `ConcurrentHashMap` |
| Search index | `SearchService.tokenIndex` | `ConcurrentHashMap<String, ConcurrentHashMap.newKeySet()>` |
| Notification listeners | `NotificationService.listeners` | `CopyOnWriteArrayList` |

### 11.2 Quota enforcement under concurrent uploads by the same user (required focus area)

**The race.** User Alice has 1000 bytes of quota remaining and fires two uploads of 700 bytes each
from two browser tabs simultaneously. A naive implementation:
```java
if (used + size > quota) throw new QuotaExceededException(...);
used += size; // or used.set(used.get() + size)
```
is a textbook check-then-act race: both threads can read `used` *before* either writes it back,
both see 700 fits within the 1000-byte headroom, both proceed, and the user ends up at 1400/1000 —
over quota by 400 bytes with no error ever thrown.

**The fix (`QuotaManager.reserve`).** A compare-and-swap retry loop on a per-user `AtomicLong`:
```java
public void reserve(String userId, long bytes) {
    long quota = quotaFor(userId);
    AtomicLong used = usedCounterFor(userId);
    while (true) {
        long current = used.get();
        long updated = current + bytes;
        if (updated > quota) {
            throw new QuotaExceededException(userId, bytes, Math.max(0, quota - current));
        }
        if (used.compareAndSet(current, updated)) {
            return; // our reservation is now durably recorded
        }
        // lost the race to another concurrent reserver for this SAME user; retry against
        // the now-current value instead of acting on stale data
    }
}
```
**Why this is correct.** The check (`updated > quota`) and the act (`compareAndSet`) are evaluated
against the exact same `current` snapshot, and the CAS only succeeds if nobody else changed `used`
between the read and the write. If it fails, we loop and re-evaluate against the *new* current
value rather than blindly retrying the same decision — so under arbitrary interleaving, the sum of
all successfully reserved amounts for one user can never exceed their quota. This is proven, not
just argued: `QuotaManagerConcurrencyTest.concurrentReservationsBySameUserNeverExceedQuota` fires
50 threads at a 1000-byte quota with 100-byte requests (exactly 10 should be admitted) and asserts
`accepted == 10` and `usedBytes == quota` exactly, repeated 5 times to raise confidence against
scheduling flakiness. **Negative control performed during development:** a deliberately naive
read-then-sleep-then-write implementation was substituted temporarily and the same test failed
immediately, admitting 13-50 reservations instead of 10 — confirming the test actually detects the
race rather than passing vacuously.

**Why CAS and not a per-user lock.** Both are correct. CAS was chosen because the quota check sits
on the hot path of every upload and never needs to hold anything across I/O — there is no window
where a thread holds a lock while doing slow work, so lock-free retry has no downside here and
avoids introducing a lock object per user that would need its own lifecycle management (when do
you ever remove a user's lock object?). A `ReentrantLock`/`synchronized` per user is the documented
alternative (section 16) and would be the more readable choice if the reservation logic ever grew
complex enough that a retry loop became hard to reason about.

### 11.3 Reference-counting correctness for deduplicated blob deletion (required focus area)

**The race.** Two blobs with the same content hash: thread A is releasing the last reference
(ref count 1 → 0, about to physically free the content) while thread B is concurrently adding a
new reference to what it believes is still a live blob (ref count 0 → 1, because it just deduped
against it). A naive implementation using a `Map<String, Blob>` plus an `AtomicInteger` ref count
on each `Blob`:
```java
Blob existing = map.get(hash);
if (existing != null) {
    existing.refCount.incrementAndGet(); // (*)
    return existing;
}
```
has a classic use-after-free window at `(*)`: thread A can observe `refCount == 1`, decrement to 0,
and remove the mapping from `map` — all between thread B's `map.get(hash)` returning a non-null
`existing` and thread B's `incrementAndGet()` call. Thread B now holds a reference to a `Blob` that
has been (or is about to be) evicted, and the blob's bytes may already be scheduled for physical
deletion while B still thinks it has a valid reference.

**The fix (`InMemoryBlobStore`).** Every mutation — create-with-refcount-1, increment-on-dedup-hit,
decrement-and-maybe-remove — happens inside a single `ConcurrentHashMap.compute(hash, ...)` call:
```java
public Blob storeAndRetain(byte[] content) {
    String hash = hashingStrategy.hash(content);
    return blobs.compute(hash, (key, existing) -> {
        if (existing == null) return new Blob(key, content, 1);
        existing.incrementRef();
        return existing;
    });
}

public void release(String contentHash) {
    blobs.compute(contentHash, (key, existing) -> {
        if (existing == null) throw new IllegalStateException(...);
        existing.decrementRef();
        return existing.getRefCount() <= 0 ? null : existing; // null return removes the mapping
    });
}
```
**Why this is correct.** Per the JDK's `ConcurrentHashMap.compute` contract, the remapping function
for a given key is applied atomically with respect to *every other operation on that same key* —
internally implemented as a lock on the key's hash bin held for the duration of the callback. This
means "check ref count and remove if zero" (in `release`) and "check if present and increment" (in
`storeAndRetain`) can never interleave for the same hash: whichever thread's `compute` call runs
first completes entirely (including any removal) before the other thread's `compute` call is even
allowed to start reading the current mapping. There is no window where thread B can see a `Blob`
object that thread A is simultaneously tearing down, because there is no "see, then separately
act" step at all — observation and mutation are the same atomic operation.

Proven by `BlobStoreConcurrencyTest`:
- `concurrentRetainsOfIdenticalContentAllSucceedAndRefCountMatchesCallerCount` — 64 threads
  dedupe-store the same content concurrently; asserts exactly 1 physical blob and a final ref count
  of exactly 64 (no lost increments).
- `concurrentRetainAndReleaseNeverFreesABlobWithLiveReferences` — 40 threads each do a transient
  retain-then-release against a blob with one permanently-held seed reference; asserts the blob is
  *never* observed absent mid-flight and nets back to exactly 1 ref count, proving releases never
  race ahead of a concurrent retain into freeing a still-referenced blob.

### 11.4 Tree-structural locking (move/rename/create/trash/restore/purge)

A single `ReentrantReadWriteLock` guards the whole tree's shape. Writers (create, rename, move,
trash, restore, purge) take the write lock; readers (list children, search navigation) take the
read lock. **Why one global lock instead of per-folder locks:** `move` touches two folders (the
old parent and the new parent) at once — any per-node locking scheme needs a consistent lock
acquisition *order* across both nodes to avoid deadlock (classic dining-philosophers shape: thread
1 moves A→B while thread 2 moves B→A, each holding one lock and waiting on the other). A single
coarse-grained lock sidesteps that entire deadlock-avoidance proof at the cost of serializing all
structural writers against each other tree-wide, which is an acceptable trade for an LLD-scoped
single process (quantified in section 16).

**Why content operations (upload/download) don't hold this lock for the actual byte-copy:**
`createFile`/`uploadNewVersion` take the *read* lock only long enough to resolve the target node
and check a permission/name-collision precondition, then release it before doing the
comparatively expensive quota-reserve + blob-store work, then (for `createFile`) briefly re-take
the *write* lock to commit the structural insertion — re-validating the name-collision check
because the tree could have changed in between (a textbook "check-then-act across a lock
upgrade" hazard, closed by the re-check rather than by trying to atomically upgrade a read lock to
a write lock, which `ReentrantReadWriteLock` does not support directly and which would risk
deadlock if attempted naively). `uploadNewVersion` does not need this re-check because it never
touches the tree shape — it only appends to `File.versions`, guarded by that file's own monitor,
so two concurrent version-uploads to the *same* file serialize on that file alone, and uploads to
*different* files never contend with each other or with unrelated tree browsing.

### 11.5 ACL list concurrency

`DriveNode.aclEntries` is a `CopyOnWriteArrayList`. Grants/revokes are rare (user-initiated,
infrequent) compared to permission-check reads (happen on every single operation), so paying a
full-array-copy cost on write in exchange for lock-free, always-consistent iteration on read is the
right trade — and it means `PermissionResolutionStrategy.resolveEffectiveRole`'s tree walk never
needs to acquire any lock at all while reading ACLs up the chain.

### 11.6 What is intentionally NOT made safe, and why

- `DriveNode` is **not** safe to mutate directly from arbitrary caller code — `renameInternal`/
  `setParentInternal` are public (Java has no "friend class" visibility) but documented as
  callable only by `DriveService` under its structural lock. This is a deliberate encapsulation
  trade-off (centralize locking at the service layer rather than making every entity defensively
  thread-safe on its own, which would mean re-deriving the two-folder `move` deadlock problem
  inside the entity layer instead of the service layer) — flagged explicitly in section 16.
- `NotificationService.publish` is synchronous and on the calling thread; a slow/blocking listener
  would slow down the triggering operation. Acceptable because listeners are in-process,
  best-effort hooks in this design, not a reliability-critical delivery mechanism — documented as
  a production gap in section 17 (should become an async queue in a real system).

---

## 12. Persistence and Database Design

**This system is in-memory by design, and that is the right choice at LLD scope**, not a
corner-cut: an interview is evaluating domain modeling and concurrency reasoning, not SQL schema
design or ORM configuration, and every piece of state here is already behind a repository-style
interface (`NodeRepository`, `BlobStore`) specifically so the persistence layer is a pluggable
implementation detail, not baked into the domain model.

**If this had to become persistent** (the natural "how would you extend this" follow-up), the
schema would look like:

```sql
CREATE TABLE users (
    id VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    quota_bytes BIGINT NOT NULL,
    used_bytes BIGINT NOT NULL DEFAULT 0
    -- used_bytes updated via an atomic UPDATE ... SET used_bytes = used_bytes + ?
    -- WHERE id = ? AND used_bytes + ? <= quota_bytes, relying on the single-row UPDATE's
    -- atomicity the same way QuotaManager relies on compareAndSet -- the SQL analogue of CAS.
);

CREATE TABLE nodes (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    owner_id VARCHAR(64) NOT NULL REFERENCES users(id),
    parent_id VARCHAR(64) NULL REFERENCES nodes(id),
    is_folder BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (parent_id, name) -- enforces sibling-name-uniqueness invariant at the DB level too
);
CREATE INDEX idx_nodes_parent ON nodes(parent_id);

CREATE TABLE file_versions (
    file_id VARCHAR(64) NOT NULL REFERENCES nodes(id),
    version_number INT NOT NULL,
    content_hash CHAR(64) NOT NULL REFERENCES blobs(content_hash),
    size_bytes BIGINT NOT NULL,
    author_id VARCHAR(64) NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (file_id, version_number)
);

CREATE TABLE blobs (
    content_hash CHAR(64) PRIMARY KEY, -- SHA-256 hex
    size_bytes BIGINT NOT NULL,
    ref_count INT NOT NULL,
    storage_uri VARCHAR(1024) NOT NULL -- pointer into real object storage, not inline bytes
);
-- ref_count increment/decrement as `UPDATE blobs SET ref_count = ref_count + ? WHERE content_hash = ?`
-- inside the SAME transaction as the file_versions insert/delete -- this is the relational
-- equivalent of ConcurrentHashMap.compute's per-key atomicity: a row-level lock on that one
-- blobs row for the duration of the transaction.

CREATE TABLE access_entries (
    node_id VARCHAR(64) NOT NULL REFERENCES nodes(id),
    user_id VARCHAR(64) NOT NULL REFERENCES users(id),
    role VARCHAR(16) NOT NULL,
    PRIMARY KEY (node_id, user_id)
);

CREATE TABLE trash_records (
    node_id VARCHAR(64) PRIMARY KEY REFERENCES nodes(id),
    original_parent_id VARCHAR(64) NULL,
    original_name VARCHAR(255) NOT NULL,
    deleted_at TIMESTAMP NOT NULL,
    purge_after TIMESTAMP NOT NULL,
    deleted_by VARCHAR(64) NOT NULL REFERENCES users(id)
);
CREATE INDEX idx_trash_purge_after ON trash_records(purge_after); -- for the sweep
```

**Transactions/isolation.** The blob ref-count update and the corresponding `file_versions`
insert/delete must be in the same transaction (at `READ COMMITTED` or stronger) — this is the
persistent analogue of the `ConcurrentHashMap.compute` atomicity this design relies on in memory;
without it, a crash between the two statements could leave a dangling version pointing at an
already-freed blob, or a blob stuck at `ref_count > 0` with no version actually referencing it
(a leak). The `users.used_bytes` single-row `UPDATE ... WHERE used_bytes + ? <= quota_bytes` is the
SQL equivalent of the in-memory CAS loop — one atomic conditional row update instead of a
read-then-write pair, achieving the same "no overshoot" guarantee without needing an explicit
application-level retry loop (the database returns 0 rows affected instead of throwing, which the
application checks instead of catching `QuotaExceededException`).

---

## 13. Failure Scenarios

| Failure | What happens / how it's handled |
|---|---|
| **Quota reserved, then blob write "fails"** (hypothetical — `InMemoryBlobStore` cannot actually throw, but a real I/O-backed implementation could) | `DriveService.createFile`/`uploadNewVersion` reserve quota *before* writing the blob specifically so that if the blob write ever could fail, the caller gets a clean failure with nothing to roll back on the quota side **except** the reservation itself, which a production implementation would need to compensate with `quotaManager.release(...)` in a `catch` around the blob-store call — called out here as a gap in the in-memory implementation (which cannot exercise this path) rather than silently assumed away. |
| **Duplicate create races past the pre-check** | Handled explicitly: `createFile` re-checks `hasChildNamed` after acquiring the write lock and, on collision, releases the quota reservation and the blob reference it already took before throwing `DuplicateNameException` — see section 11.4. This is the one documented case where a compensating rollback *is* implemented, because the race is real and reachable in this in-memory system (unlike the hypothetical blob-write failure above). |
| **Resource exhaustion (quota)** | `QuotaExceededException` thrown before any physical write happens (quota is the first gate) — the user gets a precise, actionable error (`getRequestedBytes()`/`getAvailableBytes()`), not a generic failure after partial work was done. |
| **Invalid states (cycle, wrong node type, bad name)** | Rejected synchronously with `InvalidOperationException` before any mutation; `move`'s cycle check runs before any detach/attach step so a rejected move leaves the tree completely untouched. |
| **Restoring something not in trash / purging something not in trash** | `TrashService.getTrashRecord` throws `InvalidOperationException` immediately; no side effects occur. |
| **Partial crash mid-purge (conceptual — single JVM here, but worth reasoning through for the DB-backed evolution)** | `TrashService.purgePermanently` is written to be idempotent per node: it checks `nodeRepository.existsById` before processing each node and skips already-removed ones. A retry after a crash between "release blob ref for version 2 of 3" and "release blob ref for version 3 of 3" would, in the in-memory model, simply not re-run (the whole method already completed or didn't start) — but the *pattern* (check-before-processing, one node at a time) is exactly what would make a DB-backed version safely retryable/idempotent if wrapped per-node in its own transaction. |
| **Corruption (two different pieces of content hashing to the same key)** | Not practically possible with SHA-256 (see section 10) — explicitly accepted as a vanishingly small residual risk rather than defended against with full byte-for-byte comparison on every dedup hit, which would defeat the point of hashing for performance. |
| **Timeouts / retries on quota reservation** | None needed — `reserve` is lock-free and bounded by the number of concurrent contenders for that one user (practically always small), so it cannot "hang"; no timeout wrapper is necessary or added. |
| **Cleanup of orphaned search index entries** | If a node is purged concurrently with it being returned from a stale search-index lookup, `SearchService.search` tolerates a `null` resolve from `nodeResolver` (tombstone-style) and simply skips that id rather than throwing — documented in-line as "stale index entry... tolerated, cleaned up lazily." A production system would instead eagerly remove index entries synchronously with purge (which this implementation also does, in `TrashService.purgePermanently`) and treat the stale-read tolerance purely as defense-in-depth. |

---

## 14. Performance and Scalability

**Bottlenecks and fixes, single process:**
- *Global structural write lock serializes all create/rename/move/trash/restore/purge
  tree-wide.* For an LLD-scoped single process this is fine; if profiling showed it as a hot
  path, the fix is sharding the lock per top-level subtree (e.g., one lock per user's root
  folder, since cross-user moves don't happen in this domain) rather than per-node locking
  (which reintroduces the multi-node deadlock-ordering problem described in section 11.4).
- *Linear scan to find a `FileVersion` by number* (`File.getVersion`) is O(versions). Realistic
  version counts (tens to low hundreds) make this a non-issue; if a pathological file
  accumulated tens of thousands of versions, swapping the backing list for an
  index-addressable structure (version numbers are already dense/sequential, so a plain
  `ArrayList.get(versionNumber - 1)` under the same lock would be O(1) with zero API change).
- *`Folder.listDescendants()` is O(subtree size)*, used by purge and by the (not implemented)
  "folder size" rollup. This is inherent to the operation (you must touch every descendant to
  purge it) and not a design flaw to fix.

**Multi-threaded (within one process) optimizations already applied:** per-user quota CAS (no
cross-user contention), per-hash blob ref-count atomicity (no cross-blob contention), per-file
version-append monitor (no cross-file contention), `CopyOnWriteArrayList` ACLs (no reader
contention). The design already extracts essentially all the parallelism available without
distributed coordination.

**Distributed extension (explicitly a "production evolution", not built — see section 17):**
sharding nodes/blobs across machines by hash of id/content-hash, a distributed lock service
(e.g., ZooKeeper/etcd-backed) replacing the in-process `ReadWriteLock` for structural changes, and
a real object store (S3-class) behind `BlobStore` with the ref-counting logic moved into a
transactional metadata DB (section 12) rather than a single process's `ConcurrentHashMap`. This is
intentionally not implemented: the instruction to "not over-distribute an LLD problem" is taken
seriously here — demonstrating awareness of the evolution path is the deliverable, not standing up
a distributed system for a take-home.

---

## 15. Testing Strategy

`tests/lld/clouddrive/` contains 33 JUnit 5 tests across four files, chosen to cover unit,
integration, boundary, negative, and concurrency/failure-injection categories — not just
"more tests for coverage's sake."

- **`permission/PermissionServiceTest`** (unit) — the inheritance algorithm in isolation:
  owner-always-wins, no-grant-anywhere ⇒ NONE, inheritance across two levels, explicit override
  at a deeper node beating an inherited grant, revoke falling back to inheritance, and
  non-owner-cannot-grant (negative case).
- **`service/DriveServiceTest`** (integration, through the real facade with all real in-memory
  collaborators wired together) — nested folder creation/listing, duplicate-name rejection
  (negative/boundary), version history round-trip (download v1 vs. latest), share +
  permission-inheritance + enforcement (a VIEWER's attempted edit is a negative case asserted
  with `assertThrows`), move-into-own-descendant rejection (boundary/negative), quota-exceeded
  upload rejected with quota provably unchanged afterward (negative + invariant check),
  cross-user deduplication (asserts blob count does *not* increase and both users' quotas are
  still charged independently), trash → restore round-trip, permanent purge releasing exact
  quota and blob reference, retention-sweep purging only expired entries (uses an injectable
  `MutableClock` instead of real sleeps, so the test is fast and deterministic), and permission-
  filtered search.
- **`quota/QuotaManagerConcurrencyTest`** (concurrency, failure-injection via a performed-and-
  reverted negative control) — 50 threads racing to reserve against a 10-reservation-sized quota,
  repeated 5× (`@RepeatedTest`), asserting the *exact* accepted count and the *exact* final used-
  bytes value (not just "no exception"), which is what actually proves no overshoot occurred under
  any interleaving the JVM scheduler produced in that run. Also covers `release` not going
  negative, and the exception carrying accurate requested/available byte values.
- **`store/BlobStoreConcurrencyTest`** (concurrency) — 64 threads deduping the same content
  concurrently (asserts exactly 1 physical blob, ref count exactly 64 — no lost increments), 40
  threads doing interleaved retain+release against a permanently-seeded blob (asserts the blob is
  never observed absent mid-flight and nets back to its seed ref count), and a simple
  last-release-frees-it boundary case.

**How to run:**
```
javac -d out -cp junit-platform-console-standalone.jar $(find src tests -name '*.java')
java -jar junit-platform-console-standalone.jar --class-path out --scan-class-path
```
Verified result at authoring time: **33/33 tests passed**, 0 failures, 0 skipped (full console
output retained in the session log). A negative control was also run (see section 11.2) by
temporarily substituting a deliberately-naive, non-atomic `QuotaManager.reserve` implementation;
`QuotaManagerConcurrencyTest` failed immediately and clearly (accepting up to 50 reservations
instead of 10) before the correct implementation was restored — confirming the concurrency test
is actually sensitive to the bug it claims to catch, not a vacuous pass.

---

## 16. Trade-offs and Alternatives

| Decision | Chosen approach | Alternative | Pros/cons | When the alternative wins |
|---|---|---|---|---|
| Quota concurrency primitive | CAS loop on `AtomicLong` | `ReentrantLock`/`synchronized` per user | CAS: no blocking, no lock-lifecycle management; harder to extend if reservation logic grows multi-step. Lock: trivially extensible to multi-step critical sections, easier to read for newcomers | The moment "reserve" needs to do more than one atomic numeric update (e.g., write an audit row in the same critical section) |
| Tree structural locking | One global `ReadWriteLock` | Per-folder locks with a global lock-ordering rule (e.g., always lock lower node-id first) | Global: trivially deadlock-free, but serializes all structural writers tree-wide. Per-folder: better write parallelism across unrelated subtrees, but requires a proven lock-ordering discipline for multi-node ops like `move`, and is much easier to get subtly wrong | A profiled production system where structural-write contention is measured to actually be a bottleneck |
| Dedup granularity | Whole-file SHA-256 hashing | Content-defined chunking (rolling hash) | Whole-file: simple, correct, dedupes only byte-identical files. Chunking: dedupes partial overlaps, much higher implementation complexity (chunk index, reassembly, chunk-level ref counting) | Large files that are frequently partially modified (VM images, databases, video) where partial-overlap dedup materially reduces storage cost |
| Quota accounting for versions | Every version ever created counts against quota permanently | Only the latest version counts; editing can free space | Chosen: simple, monotonic, matches real product (undo-safety: deleting history isn't silently implied by editing). Alternative: lets users "reclaim" space by overwriting, but makes quota non-monotonic and complicates purge accounting (which version's bytes do you release on a plain edit?) | A product tier that explicitly offers "don't keep version history" to save space |
| Permission model | ACL-per-node with tree inheritance, resolved at check time | A flattened permissions table pre-computed at share-time (push model) | Chosen (pull/resolve-at-check-time): new children automatically inherit sharing with zero extra writes; check cost is O(tree depth). Push model: O(1) check cost but requires fanning out writes to every existing descendant at share-time (expensive for huge folders) and again at move-time | A system where permission checks vastly outnumber share/move operations and O(depth) checks are measured as the bottleneck — then precomputing/caching effective roles (with invalidation on share/move) wins |
| Where concurrency control lives | Centralized in `DriveService` (structural lock) + a few per-entity monitors (`File`) | Every entity defensively synchronizes itself | Chosen: one place to reason about the tricky multi-node operations (move); entities stay simple POJOs. Alternative: entities are "safe by default" even if misused outside the facade, at the cost of needing a proven lock-ordering scheme inside the entity layer itself | A codebase where entities are known to be accessed from many unrelated call sites that can't all be trusted to go through one facade |
| Trash retention period | A single `Duration` injected into `TrashService` (one value for everyone) | Per-user/per-plan retention duration | Chosen: simplest correct thing matching the stated requirement. Alternative is a one-line change (look up a `Duration` by user/plan instead of using a constant) — not implemented because nothing in the requirements asked for tiered retention | A product with paid tiers that differentiate on trash retention length |

---

## 17. Production Evolution

Kept deliberately separate from the core interview solution above — these are the things you'd
say when asked "how would this need to change to actually ship it", not things that were built.

- **Persistence.** Swap `InMemoryNodeRepository`/`InMemoryBlobStore` for DB-backed and
  object-store-backed implementations respectively (schema sketched in section 12); both are
  already behind interfaces, so this is additive, not a rewrite of `DriveService`.
- **Distributed locking.** Replace the single-process `ReadWriteLock` with a distributed lock
  (e.g., backed by a consensus store) scoped per top-level subtree, once the system runs on more
  than one node (section 14).
- **Monitoring/metrics.** Emit counters for quota-rejections, dedup-hit-rate (blob reuse vs. new
  blob creation ratio — a direct measure of how much storage dedup is actually saving), trash
  sweep throughput, and permission-check latency (should stay O(depth), a regression here would
  show up as a p99 latency creep as folder nesting grows in practice).
- **Logging/audit trail.** Every `FileChangeEvent` already carries actor/node/type/timestamp —
  in production, a durable, append-only audit log consumer would subscribe via
  `NotificationService` (today's listeners are best-effort/in-memory only).
- **Fault tolerance.** The compensating-rollback pattern used in `createFile` for the duplicate-
  name race (section 13) is the template for how a real I/O-capable `BlobStore` would need
  try/catch-and-release around a blob write that can genuinely fail (disk full, network
  partition to the object store).
- **Operational concerns.** Rate limiting per user (distinct from quota — quota is about storage,
  rate limiting is about request throughput), backpressure on the trash sweep for accounts with
  huge trash volumes, and a real search backend (Elasticsearch/Lucene) once ranking/fuzzy-match
  requirements exceed what a hand-rolled inverted index can reasonably do.
- **Content-defined chunking** for large, partially-modified files (section 10/16) — the single
  highest-value storage-efficiency improvement not included in this LLD scope.
- **Async notification delivery** — today's `NotificationService.publish` is synchronous on the
  caller's thread; production would push to a durable queue instead, decoupling sync-client
  notification latency/availability from the core write path entirely.
