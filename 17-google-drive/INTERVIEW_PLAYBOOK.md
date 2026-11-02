# Interview Playbook — Google Drive / Cloud File Storage System

A speakable, timeline-driven guide for running this problem live in a 30/45/60-minute round.

---

## 1. How to open the discussion

Don't start drawing boxes. Start by restating the problem in your own words and staking out
scope, out loud, in under 90 seconds:

> "So we're designing the backend data model and core operations for something like Google
> Drive — files and folders that users can organize, share with others at different permission
> levels, edit with history preserved, and search. I'm going to scope this as a single-process
> in-memory system with a clean concurrency story, and explicitly leave out things like actual
> network transport, real object storage, and multi-node distribution unless you want me to go
> there. Does that match what you had in mind?"

This does three things: shows you can scope a problem instead of panicking at its size, invites
the interviewer to redirect early (cheap to do now, expensive at minute 40), and buys you
permission to *not* build a distributed system later.

## 2. First 5 clarifying questions to ask

Ask these, don't just think them — asking well is part of what's being graded.

1. "When a folder is shared with someone, does that implicitly share everything inside it,
   including things added later? And if a specific file inside gets its own, different share
   level, which one wins?"
2. "Does a file's edit history count against the owner's storage quota, or just the current
   version?"
3. "If two different users upload byte-identical content, should it be physically stored once?
   Should they both still be charged quota for it?"
4. "What happens on delete — straight to permanent delete, or a recoverable trash with some
   retention window?"
5. "For search, are we talking exact/substring name match, or do you want fuzzy/ranked search?"

## 3. Requirements to establish

Say this plainly once questions are answered, so you and the interviewer are provably aligned:

> "Okay — so: hierarchical files/folders, owner/editor/viewer sharing that inherits down a
> folder tree with explicit overrides possible deeper, append-only version history, dedup'd
> storage with correct reference counting, per-user quota that has to be bulletproof under
> concurrent uploads, a trash with retention and restore, and name-based search filtered by
> permission. I'll flag anywhere I'm making a scoping call instead of guessing silently."

## 4. How to identify core entities

Narrate the nouns-to-classes exercise live:

> "The core thing here is really a filesystem tree, so I want a `DriveNode` base with `Folder`
> and `File` as the two concrete shapes — that's the Composite pattern, and it means permission
> checks, search, and trash don't need to special-case folders vs. files anywhere. A `File` isn't
> one blob of bytes, it's a sequence of immutable `FileVersion`s — that's a Memento, each edit
> appends rather than mutates. Content itself is addressed by hash in a separate `Blob` store so
> identical bytes are only stored once, with a reference count. And then I need a few services
> that aren't entities themselves — permission resolution, quota tracking, trash lifecycle — each
> with one clear job."

## 5. How to explain class relationships

> "`Folder` *has* children — composition, a child's conceptual lifetime is tied to being
> reachable from the tree or sitting in trash. A `File` *has* versions, same composition, fully
> immutable once created. A `FileVersion` *references* a `Blob` by hash, but doesn't own it — many
> versions, across many files and even different users, can point at the same blob, and that's
> exactly why the blob needs a reference count instead of being owned by one file."

## 6. How to draw the architecture

Draw top-down, not alphabetically:

1. One box: `DriveService` (the facade) at the top.
2. Below it, in a row: `PermissionService`, `QuotaManager`, `BlobStore`, `TrashService`,
   `SearchService` — "these are the things DriveService orchestrates; none of them know about
   each other directly."
3. Below `PermissionService`: the `PermissionResolutionStrategy` interface — "pulled out as a
   strategy because inheritance-based resolution is a policy I might want to swap."
4. Below `BlobStore`: `HashingStrategy` — same reasoning.
5. Last: the model layer (`DriveNode`/`Folder`/`File`/`FileVersion`/`Blob`) as a separate cluster
   underneath everything, with an arrow showing the facade and services operate *on* it.

Say while drawing: "I'm deliberately keeping the model layer dumb — no business logic lives on
`DriveNode` itself beyond basic invariants, because the interesting multi-step logic (like
move needing to check for cycles across two folders) needs context no single node has."

## 7. Which interfaces to create first

In this order, and say why:

1. `Role` enum — "everything else references this, zero dependencies, get it out of the way."
2. `DriveNode`/`Folder`/`File` — "the shape everything else operates on."
3. `BlobStore` interface — "I want dedup behind an interface before I write the tricky
   ref-counting logic, so I'm forced to think about its contract first."
4. `PermissionResolutionStrategy` interface — "same reasoning, for inheritance."
5. `NodeRepository` interface — "trivial, but keeps `DriveService` from hard-coding storage."

## 8. Which classes to implement first

1. `DriveNode`/`Folder`/`File`/`FileVersion` (the model) — get the tree compiling and testable in
   isolation before any service logic.
2. `InheritedAclPermissionStrategy` + `PermissionService` — this is pure logic over the tree you
   just built, no I/O, easy to unit test standalone, and unlocks every later permission check.
3. `InMemoryBlobStore` with `Sha256HashingStrategy` — the dedup/ref-count core; write its
   concurrency test *immediately* after, while the invariant is fresh.
4. `QuotaManager` — same treatment, write the concurrency test right after.
5. `DriveNodeFactory`, `TrashService`, `SearchService`.
6. `DriveService` last — it's the thin(ish) orchestrator over everything above; writing it last
   means every collaborator it calls already has a known, tested contract.

## 9. Which design patterns to introduce and when

Introduce a pattern at the moment its problem becomes concrete, not upfront as a checklist:

- **Composite** — the instant you're asked "how do files and folders relate", not before.
- **Memento** — the instant "editing a file" comes up; say explicitly "I don't want to mutate
  content in place, I want each edit to produce a new immutable version."
- **Strategy** — when you notice you're about to hard-code "walk up the tree" logic inside
  `PermissionService`; pull it out *while writing it*, narrating "I'm putting this behind an
  interface because the resolution policy is exactly the kind of thing that varies."
- **Factory** — only if you notice `Folder`/`File` construction logic (validation, id generation)
  starting to repeat across two call sites; don't pre-emptively add it for one call site.
- **Repository** — when discussing persistence or when writing `DriveService`'s constructor and
  realizing it shouldn't take a raw `Map`.
- **Facade** — this one is almost automatic once you have 5+ collaborator services; call it out
  by name when you introduce `DriveService`.
- **Observer** — only if there's time left and the interviewer asks about sync clients/
  notifications; it's legitimately optional scope.

## 10. How to discuss concurrency

Don't wait to be asked — flag it the moment you write a shared counter or a shared map:

> "This `used bytes` counter is going to be hit by concurrent uploads from the same user, so a
> plain read-check-write is a race — I'm going to use a compare-and-swap loop so the check and
> the reservation happen atomically against the same snapshot."

And for dedup:

> "Reference counting a shared blob has a classic bug: one thread decrementing to zero and
> freeing it while another thread is simultaneously incrementing it believing it's still live. I'm
> going to do both operations inside a single `ConcurrentHashMap.compute()` call per hash, because
> the JDK guarantees that's atomic per key — there's no window where those two operations can
> interleave."

If asked "how do you know that's actually atomic" — cite the javadoc guarantee directly, don't
hand-wave: `compute()`'s remapping function is "performed atomically" with respect to other
updates for that key.

## 11. How to discuss edge cases

Walk through these out loud even if not asked, they show depth:

- Moving a folder into its own descendant (cycle) — must be checked *before* any mutation.
- Two concurrent creates with the same target name — a duplicate-name race across a lock
  "upgrade" window; show you know you need a re-check, not just a pre-check.
- Restoring an item whose original parent folder was itself deleted in the meantime — fall back
  gracefully (reattach elsewhere or leave detached) instead of crashing.
- A user trashing, then trying to download, a file they just trashed — should still work, because
  trash is a soft delete; only purge actually removes content.
- The last reference to a deduplicated blob being released exactly when another upload is
  concurrently adding a new reference to the same content — the headline concurrency scenario.

## 12. How to handle interviewer interruptions

- If interrupted mid-explanation with a question, answer it directly and briefly, then explicitly
  resume: "Good question — [answer]. Picking back up where I was: ..."
- If the interviewer pushes toward a direction you'd planned to defer (e.g., "what about
  distributed locking?") answer briefly at the conceptual level and offer to go deeper or stay on
  track: "We'd replace the in-process lock with a distributed one scoped per subtree — want me to
  detail that now, or keep building the single-node version first?"
- If you're challenged on a decision, don't get defensive — restate the trade-off: "Fair, the
  alternative is X, which wins when Y; I went with this because Z, but I can switch if you want to
  see that path instead."

## 13. How to prioritise if time is running out

Drop in this order (stop building, start *describing* instead):
1. Search — describe the inverted-index approach verbally, don't implement it.
2. Observer/notifications — mention it as an extension point, skip entirely.
3. Trash retention sweep — implement `moveToTrash`/`restoreFromTrash` fully, describe the sweep
   as "same idea, iterate trash records, purge if past deadline" without coding the scheduler.
4. Never drop: the Composite model, basic permission check, and at least one of the two
   concurrency guarantees (quota CAS or blob ref-counting) — these are the load-bearing parts of
   the problem; everything else is negotiable under time pressure.

## 14. Three full walkthroughs with realistic timelines

### 30-minute version
- 0-3 min: restate problem, scope out distribution/persistence/transport explicitly.
- 3-8 min: clarifying questions (section 2) + requirements recap.
- 8-15 min: entities + Composite + Memento, drawn/narrated, no code yet.
- 15-24 min: code `DriveNode`/`Folder`/`File`, `PermissionService` with inheritance, and a minimal
  `DriveService.createFolder`/`createFile`/`share`/`downloadLatest`.
- 24-28 min: narrate (don't fully code) the quota CAS loop and blob ref-counting — write the CAS
  loop's ~8 lines if time allows, explain the rest verbally.
- 28-30 min: summarize what was skipped (trash, search, full move/rename) and why, trade-offs.

### 45-minute version
- 0-3 min: open + scope.
- 3-8 min: clarifying questions + requirements.
- 8-16 min: entities, patterns, class diagram sketch.
- 16-30 min: code model layer, `PermissionService`, `QuotaManager` (CAS loop, in full, this is a
  headline piece), `InMemoryBlobStore` (ref-counting in full, also headline), `DriveService`
  create/upload/share/download.
- 30-38 min: trash (moveToTrash/restore/purgePermanently) and the cycle-check in `move`.
- 38-43 min: concurrency Q&A — walk through both races and how they're closed.
- 43-45 min: trade-offs, what you'd add for production.

### 60-minute version
All of the above, plus:
- Full `move`/`rename` with the lock-upgrade re-check discussion.
- Search with the inverted index, including the permission-filtering requirement.
- A couple of tests written live (one happy-path, one negative/concurrency test) to demonstrate
  the testing-strategy thinking, not just describe it.
- A deliberate 5-minute "staff-level" digression: discuss what breaks at 10M users, what the
  first production incident in this design would probably be (quota drift from a missed
  compensating rollback, most likely), and how you'd evolve persistence without a rewrite.

## 15. What I would say to the interviewer

- "I'm going to model files and folders as one Composite hierarchy so sharing and permission
  checks don't need two code paths."
- "Editing a file never mutates its content — every save creates a new version, so anyone reading
  an older version mid-edit never sees a half-written state."
- "I'm hashing content so two users uploading the same file only costs one copy of storage, but
  I need reference counting to know when it's actually safe to delete that copy."
- "The quota check has to be atomic — I don't want two uploads from the same user both squeezing
  through a quota check that was only valid for one of them."
- "I'm using a single lock for the tree's shape rather than locking individual folders, because
  moving a folder touches two parents at once, and getting per-node lock ordering provably
  deadlock-free is a lot of risk for not much payoff at this scale."
- "Trash is a soft delete — the file's still fully readable and shareable, it just isn't listed
  under its old folder anymore; only a permanent purge actually frees the quota and the blob
  reference."
- "I'm intentionally not building a distributed version of this — I'll tell you exactly how it'd
  evolve if you want, but standing up sharding and distributed locks isn't what proves out the
  core design here."
- "If I had to cut something right now to ship in two weeks, it'd be fuzzy search ranking and
  tiered retention periods — the core sharing/versioning/quota/dedup correctness is non-negotiable,
  search quality and trash policy knobs are not."
