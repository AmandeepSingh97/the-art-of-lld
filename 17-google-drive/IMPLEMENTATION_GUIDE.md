# Implementation Guide — Google Drive / Cloud File Storage System

A hands-on guide to actually writing this under time pressure: what order, what to say while
typing, what mistakes to avoid, and what a legitimately simpler 20-30 minute version looks like.

---

## Which classes to write first

1. `Role` (enum) — zero dependencies, needed by almost everything else.
2. `AccessEntry`, `FileVersion`, `TrashRecord`, `User` — plain immutable value objects, no logic,
   get them out of the way fast.
3. `DriveNode` (abstract) → `Folder` → `File` — the Composite model. Get this compiling and
   sanity-checked (even with a throwaway `main` building a tiny tree) before writing any service.
4. `HashingStrategy` + `Sha256HashingStrategy`, `BlobStore` interface + `InMemoryBlobStore` — the
   dedup core. Write its concurrency test immediately after, while the invariant is fresh in your
   head.
5. `QuotaManager` — same treatment: implement, then immediately write the concurrent-reservation
   test.
6. `PermissionResolutionStrategy` + `InheritedAclPermissionStrategy` + `PermissionService`.
7. `NodeRepository` + `InMemoryNodeRepository`, `DriveNodeFactory`.
8. `TrashService`, `SearchService`, `NotificationService` (optional/last if time allows).
9. `DriveService` — the facade, last, because every collaborator it orchestrates should already
   exist and be independently tested by the time you write it.

## Minimal compilable implementation outline

If you only have time to get *something* compiling and runnable, this is the smallest useful
skeleton (roughly what the 20-30 minute version below builds):

```java
enum Role { NONE, VIEWER, EDITOR, OWNER; boolean isAtLeast(Role r) { return ordinal() >= r.ordinal(); } }

abstract class DriveNode {
    final String id, name; final String ownerId; Folder parent;
    final List<AccessEntry> acl = new ArrayList<>();
    // ... constructor, abstract isFolder()
}

final class Folder extends DriveNode {
    final Map<String, DriveNode> children = new LinkedHashMap<>();
    // addChild, removeChildByName, listChildren
}

final class File extends DriveNode {
    final List<FileVersion> versions = new ArrayList<>();
    synchronized void addVersion(FileVersion v) { versions.add(v); }
    synchronized FileVersion current() { return versions.isEmpty() ? null : versions.get(versions.size()-1); }
}

record FileVersion(int versionNumber, String contentHash, long sizeBytes, String authorUserId, Instant createdAt) {}

final class QuotaManager {
    private final Map<String, AtomicLong> used = new ConcurrentHashMap<>();
    private final long quota;
    void reserve(String userId, long bytes) {
        AtomicLong u = used.computeIfAbsent(userId, x -> new AtomicLong());
        while (true) {
            long cur = u.get();
            if (cur + bytes > quota) throw new RuntimeException("quota exceeded");
            if (u.compareAndSet(cur, cur + bytes)) return;
        }
    }
}

final class InMemoryBlobStore {
    private final Map<String, Blob> blobs = new ConcurrentHashMap<>();
    Blob storeAndRetain(byte[] content) {
        String hash = sha256(content);
        return blobs.compute(hash, (k, v) -> v == null ? new Blob(k, content, 1) : v.retain());
    }
    void release(String hash) {
        blobs.compute(hash, (k, v) -> v.release() <= 0 ? null : v);
    }
}
```

This skeleton alone is enough to demonstrate Composite + Memento + the two headline concurrency
guarantees — permission inheritance, trash, search, and the full facade can all be described
verbally if time runs out before they're coded.

## Suggested implementation sequence (narrated, step by step)

1. **Model layer first, say why:** "I'm writing the tree shape before any logic, so everything
   after this has something concrete to operate on."
2. **One concurrency-critical piece at a time, test immediately:** write `QuotaManager.reserve`,
   say the race out loud, write the CAS loop, then *immediately* write the concurrency test for it
   before moving on — don't defer concurrency tests to "later," they're part of proving the
   component works, not an afterthought.
3. **Same for `BlobStore`:** implement, narrate the ref-count race, use `compute()`, test
   immediately.
4. **Permission logic as pure functions over the tree:** no I/O, no locking needed here — write
   `resolveEffectiveRole` and its unit tests quickly, this is the cheapest part of the whole
   design to get fully correct and tested.
5. **Facade last, thin:** `DriveService` should mostly be "resolve node, check permission, delegate
   to a collaborator, maybe publish an event" — if you find yourself writing non-trivial logic
   directly inside `DriveService` beyond orchestration and locking, stop and ask whether it belongs
   in one of the collaborators instead.

## Important methods (the ones worth getting exactly right)

- `QuotaManager.reserve` — the CAS retry loop; this is the single most important method in the
  whole system to get correct, and the one most likely to be probed deeply.
- `InMemoryBlobStore.storeAndRetain` / `release` — the `compute()`-based atomic ref counting.
- `InheritedAclPermissionStrategy.resolveEffectiveRole` — walk to parent until owner match, ACL
  match, or root; get the "explicit entry wins over inheritance" order right.
- `DriveService.move`'s `isAncestorOf` cycle check — must run before any mutation.
- `TrashService.purgePermanently` — the one place blob refs and quota are actually released; get
  the per-version loop right (one release per version, not one release for the whole file).

## Common coding mistakes (and why they're wrong)

- **Writing `if (used + bytes > quota) { used += bytes; }` for quota** — the textbook
  check-then-act race; always use a CAS loop or an equivalent atomic conditional update.
- **Using `AtomicInteger` directly as a `Blob`'s ref count field, mutated from outside a
  `compute()` callback** — reintroduces the exact use-after-free race `compute()` exists to
  prevent; the whole point is that the check-and-mutate happens as one atomic step per key, not
  that the counter type itself is atomic.
- **Letting `File.versions` be a plain public `List` field** — breaks the append-only invariant;
  always expose only `addVersion` plus defensive-copy read accessors.
- **Checking permission by comparing `node.ownerId.equals(userId)` scattered across multiple
  places instead of centralizing it in one strategy** — guarantees the rule drifts inconsistently
  the moment someone adds a second check site and forgets one.
- **Forgetting the cycle check in `move`** — a folder moved into its own descendant creates an
  unreachable, unbounded loop structure; always check before mutating, never try to detect it
  after the fact.
- **Releasing a blob reference once per *file* instead of once per *version* on purge** — a file
  with 5 versions, all pointing at 5 different (or some shared) blobs, needs exactly 5 release
  calls, not 1; under-releasing leaks a blob forever, over-releasing frees a blob while another
  file still references it.
- **Checking a name collision once and never re-checking after acquiring a write lock following
  unlocked work** — a classic check-then-act-across-a-lock-upgrade bug; if you do any unlocked work
  between a read-locked check and a write-locked commit, re-check under the write lock.

## Edge cases worth calling out while coding (even unprompted)

- Moving/renaming to collide with an existing sibling name.
- Downloading/sharing an item that's in the trash (should still work — it's a soft delete).
- Restoring an item whose original parent was itself deleted.
- Two users uploading byte-identical content (dedup correctness + independent quota charging).
- The very first upload for a brand-new user (ensure quota/root-folder bootstrapping works before
  any files exist).
- A file with zero versions (shouldn't be reachable via the public API, but defend against it in
  `getCurrentVersion`/`downloadLatest` with a clear error rather than a `NullPointerException`).

## How to narrate the code while writing it

Say the *invariant* before the code, not after:
> "This map can be hit by two threads reserving quota for the same user at the same time, so
> before I write the check, I want to make sure the check and the reservation happen as one atomic
> step." *(then write the CAS loop)*

Not:
> *(writes code)* "...and this is thread-safe because of compareAndSet."

Narrating the "why" first signals you reasoned about the problem before reaching for a specific
tool, which is what's actually being evaluated — not just that the resulting code happens to be
correct.

## What can be safely omitted under time pressure

- Search (describe the inverted-index approach verbally).
- Notifications/Observer (mention as an extension point only).
- Full move/rename with the lock-upgrade re-check subtlety (a simpler single-write-lock version
  that doesn't optimize for not-blocking-during-I/O is a legitimate simplification to state
  explicitly: "I'd hold the write lock for the whole operation for simplicity; here's how I'd split
  it if upload latency mattered").
- Tiered/per-user trash retention (one global constant is fine).
- The `DriveNodeFactory` as a separate class — inlining its two or three lines of validation
  directly into `DriveService` is a reasonable simplification if time is tight; just say you'd
  extract it the moment a second call site needed the same validation.

## A simplified version implementable in 20-30 minutes

Scope it down to exactly this, and say so explicitly up front:

> "Given the time, I'm going to implement: Composite file/folder model, a single owner-only
> permission check (no sharing/inheritance), the quota CAS loop, and the blob dedup/ref-counting
> — those are the parts with real concurrency content. I'll describe trash, search, and multi-user
> sharing verbally rather than coding them."

Concretely:
1. `Role`, `DriveNode`/`Folder`/`File`/`FileVersion` (5-7 min).
2. `QuotaManager.reserve`/`release` with the CAS loop, written carefully and correctly — this is
   non-negotiable even in the short version (5 min).
3. `InMemoryBlobStore.storeAndRetain`/`release` with `compute()` — also non-negotiable (5 min).
4. A minimal `DriveService` with just `createFile`/`uploadNewVersion`/`downloadLatest`, enforcing
   quota and a simple "is this the owner" check (no inheritance) (8 min).
5. One concurrency test for the quota CAS loop, written live if time allows, or described
   precisely ("50 threads, assert exact accepted count") if not (2-5 min).

This simplified scope deliberately keeps both of the problem's headline concurrency guarantees
intact and defers everything else — which matches exactly what section 13 of the playbook
identifies as "never drop" versus "drop first" under time pressure.
