# Implementation Guide — Contact Management System

A practical build-order and narration guide for actually writing this
system live, under real time pressure, in a way that stays compilable at
every checkpoint.

---

## 1. Recommended Class-Writing Order

Write in this order so the code compiles and is demonstrable at every
stopping point - each step only depends on classes already written:

1. **Enums**: `PhoneType`, `EmailType`, `AddressType` - zero dependencies,
   instant.
2. **Value objects**: `PhoneNumber`, `EmailAddress`, `PostalAddress`
   (records) - depend only on step 1's enums.
3. **`ContactNormalizer`** (util) - pure static methods, no dependencies;
   needed by step 2's `normalized()` methods and later indexing.
4. **`Group`** (record) - independent of everything above.
5. **Exceptions**: `InvalidContactException`, `ContactNotFoundException`,
   `GroupNotFoundException`, `OptimisticLockException` - trivial, needed
   before `Contact.Builder.build()` can throw correctly.
6. **`Contact` + `Contact.Builder`** - the central class; depends on steps
   1-3 and 5. Get its invariants right here (name non-blank, phone dedup)
   before moving on - everything downstream assumes these hold.
7. **`ContactChangeEvent`** (record) - depends on `Contact`.
8. **`ContactChangeListener`** (functional interface) - depends on
   `ContactChangeEvent`.
9. **`ContactRepository`** (interface) + **`InMemoryContactRepository`** -
   depends on `Contact`, `ContactChangeEvent`, `ContactChangeListener`.
   Write `compareAndSet` carefully here; this is the riskiest class.
10. **`HistoryService`** - depends only on `Contact`.
11. **`ContactMerger`** - depends only on `Contact`.
12. **`ContactService`** - depends on steps 6, 9, 10, 11, and
    `GroupRepository` (step 14, can forward-declare the interface first
    if building strictly top-down).
13. **`NameIndex`** (interface) + **`TrieNameIndex`** +
    **`SortedArrayNameIndex`** - depend only on `java.util.UUID`/strings,
    no dependency on `Contact` itself (indexes store ids, not contacts).
14. **`HashLookupIndex`** - same independence as step 13.
15. **`GroupRepository`** (interface) + **`InMemoryGroupRepository`** -
    depends on `Group`.
16. **`IndexManager`** - depends on steps 13, 14, 8 (implements
    `ContactChangeListener`).
17. **`GroupService`** - depends on steps 12, 15, 16.
18. **`SearchService`** - depends on steps 9, 16.
19. **`DuplicateMatchStrategy`** (interface) + **`ExactContactPointMatchStrategy`**
    + **`NormalizedNameMatchStrategy`** + **`CompositeDuplicateMatchStrategy`** -
    depend only on `Contact`.
20. **`DuplicateDetectionService`** - depends on steps 16, 19.
21. **`ContactCodec`** (interface) + **`VCardContactCodec`** - depends on
    `Contact`, `Group`.
22. **`ImportExportService`** - depends on steps 12, 17, 21.
23. **`ContactManagementSystem`** (facade) - depends on everything;
    purely wiring, write last.
24. **`Main`** (demo) - depends on step 23 only.

---

## 2. Minimal 20-30 Minute Compilable Outline

If time only allows a fraction of the above, this subset compiles and
demonstrates the core design story (immutability + CAS + one search
index) on its own:

1. One enum (`PhoneType`) - can hardcode the others away initially.
2. `PhoneNumber`, `EmailAddress` (skip `PostalAddress` under real time
   pressure - say so explicitly: "I'd add PostalAddress identically to
   these two, just with more fields").
3. `InvalidContactException`, `ContactNotFoundException`,
   `OptimisticLockException` (skip `GroupNotFoundException` if groups are
   being cut).
4. `Contact` + `Contact.Builder` - the non-negotiable centerpiece.
5. `ContactRepository` + `InMemoryContactRepository` with
   `compareAndSet` - the second non-negotiable piece; this is where most
   of the staff-level signal lives.
6. `ContactService` with just `create`/`get`/`update`/`delete` (skip
   merge/undo/groups under real time pressure).
7. `NameIndex` + **one** implementation (`TrieNameIndex`) - enough to
   demonstrate the DSA story; narrate the second implementation and the
   comparison table verbally instead of coding it (see
   INTERVIEW_PLAYBOOK.md section 7's 30-minute walkthrough).
8. A minimal `Main` that creates two contacts, updates one, and searches
   by prefix - proves the whole minimal slice actually works end-to-end.

This minimal slice is intentionally still a complete, compilable,
demonstrable vertical: entity -> storage -> service -> index -> demo,
rather than a horizontal slice (e.g. "all the model classes but nothing
else runs") which would prove nothing works together.

---

## 3. Common Mistakes When Implementing This Design

- **Forgetting `List.copyOf`/`Set.copyOf` on `Contact`'s constructor
  fields** - silently reintroduces mutability via an aliased caller-held
  collection, invalidating every thread-safety claim built on top of it.
  Verify every collection field is defensively copied, not just wrapped.
- **Using `Map.replace(key, old, new)` instead of a reference-identity
  check** - the single most consequential bug this design is built to
  avoid (see Staff-Level Deep Dive Q2); if you reach for
  `ConcurrentHashMap.replace` directly anywhere, stop and reconsider.
- **Validating the "must have a phone or email" rule inside
  `Contact.Builder.build()`** instead of in `ContactService` - works
  functionally but conflates structural and business validation (see
  README section 5); fine under severe time pressure, but name the
  shortcut if you take it.
- **Re-indexing only the changed field** instead of the whole contact on
  update, as a premature optimization - this introduces real risk of
  missing a field (e.g. forgetting to re-index a changed email because
  only the phone-update code path was touched); start with the simpler
  "deindex everything, reindex everything" approach (what this
  implementation does) and only optimize if asked to.
- **Writing `TrieNameIndex`'s `searchByPrefix` as a full subtree DFS**
  instead of reading one pre-aggregated node set - this accidentally
  rebuilds the "leaf-only" variant's query cost while still paying the
  eager variant's memory cost, the worst of both approaches; know which
  variant you're building and be consistent.
- **Forgetting `HistoryService.forget(id)` on delete** - leaves a deleted
  contact's history reachable, so a subsequent `undo(id)` after a delete
  could appear to partially resurrect a contact depending on how `undo`
  is wired; always clear history on delete.
- **Building `mergeContacts` without any lock at all**, reasoning "CAS on
  the keep-contact is enough" - it isn't, because the *delete* of the
  duplicate is a separate operation with no atomicity relationship to the
  keep-contact's CAS; a crash or interleaving between the two leaves an
  inconsistent intermediate state unless something (the coarse lock here)
  serializes the whole merge as a unit from the caller's perspective.

---

## 4. Narration Guide While Coding

Say these out loud as you write the corresponding code - this is what
turns "working code" into "demonstrated understanding":

- While writing `Contact`'s builder: *"I'm making every field final and
  every collection a defensive copy here, because this class needs to be
  safely shared across threads with no locking at all - that's only true
  if nothing can mutate it after construction."*
- While writing `compareAndSet`: *"I'm deliberately not using
  `ConcurrentHashMap.replace` here, because that compares via `.equals()`,
  and my `Contact.equals()` is id-based - two different versions of the
  same contact would incorrectly look equal to a replace call. I need
  reference-identity comparison instead, so I'll use `computeIfPresent`
  with an explicit `==` check."*
- While writing `ContactService.update`'s loop: *"This is optimistic
  concurrency control - I read the current state, build my new state,
  then try to atomically swap. If someone else got there first, I retry
  with a fresh read rather than blocking."*
- While writing `TrieNameIndex`: *"Each node along the path accumulates
  the full id set for the token so far, not just the leaf - that's what
  gets me O(prefix-length) query time with no subtree walk, at the cost of
  more memory per node."*
- While writing `DuplicateDetectionService`: *"I'm using the existing
  indexes to generate a small candidate set first - this is called
  'blocking' in record-linkage terminology - so I never have to compare
  one contact against the entire address book."*
- While writing `mergeContacts`: *"This touches two separate aggregate
  roots, so a single-key CAS can't make it atomic by itself - I'm using
  one coarse lock across all merges, which is simple to reason about and
  provably deadlock-free, at the cost of serializing concurrent merges
  against each other."*

---

## 5. What to Omit Under Time Pressure (ranked, cut from the bottom up)

1. `PostalAddress` modeling (cut first - structurally identical to
   `PhoneNumber`/`EmailAddress`, adds no new design signal).
2. `VCardContactCodec`'s exact line-parsing details (keep the interface
   and narrate the shape; the parsing logic itself is string-manipulation
   busywork, not design signal).
3. `GroupService`'s cascading-delete implementation (narrate: "deleting a
   group would iterate its member ids via the reverse index and remove
   the membership from each" - the reverse index's existence is the real
   design point, not the iteration code).
4. The second `NameIndex` implementation's actual code (keep the
   interface + one implementation + the verbal comparison table - this is
   explicitly flagged as the *last* thing to cut, since the Trie-vs-
   sorted-array comparison is this problem's named DSA requirement; cut
   `PostalAddress`/vCard/cascading-delete well before this).
5. `HistoryService`'s exact bounded-trim logic (keep `record`/`undo`;
   narrate the cap as "I'd bound this at N entries per contact to avoid
   unbounded memory growth").

Never cut, regardless of time: `Contact`'s immutability + builder,
`InMemoryContactRepository.compareAndSet`'s reference-identity logic, and
at least one working `NameIndex` implementation with its complexity
justified out loud - these three are the load-bearing design decisions the
rest of the system's story depends on.

---

## 6. Build/Run Commands (for this repository's actual code)

Compile main sources only:
```bash
mkdir -p build/out
javac -d build/out $(find src -name '*.java')
```

Run the demo:
```bash
java -cp build/out lld.contactmanagement.demo.Main
```

Compile main + tests against the JUnit 5 console-standalone jar and run
the full suite (adjust the jar path to your environment):
```bash
javac -d build/out -cp /path/to/junit-platform-console-standalone.jar \
  $(find src tests -name '*.java')
java -jar /path/to/junit-platform-console-standalone.jar \
  --class-path build/out --scan-class-path
```
