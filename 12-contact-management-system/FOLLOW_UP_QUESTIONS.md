# Follow-Up Questions — Contact Management System

Twenty-eight questions across the ten required categories, each answered
with reference to this implementation. Grouped by category; most
categories have 2-4 questions, totalling 28 overall.

---

## 1. Object-Oriented Design (OOD)

**Q1.1. Why is `PhoneNumber` modeled as a value object (record) rather than an entity?**
It has no independent identity worth tracking - two `PhoneNumber`s with the
same raw value and type are interchangeable for every purpose this system
cares about (there's no "phone number #47 specifically, regardless of its
digits" concept anywhere in the domain). Modeling it as a record gives
value-based `equals`/`hashCode` for free, which the de-duplication logic in
`Contact.Builder.build()` depends on directly.

**Q1.2. Why does `Contact` hold `groupIds: Set<UUID>` instead of `List<Group>` objects directly?**
Holding `Group` objects directly would create a staleness problem: if a
`Group` were renamed, every `Contact` holding a stale copy of the old
`Group` object would show outdated data unless every single `Contact` were
proactively updated. Holding ids and resolving them against
`GroupRepository` on read means there is exactly one source of truth for a
group's current state, and renames are instantaneous for every contact
without touching `Contact` records at all.

**Q1.3. Is `Contact` violating the Single Responsibility Principle by owning phones, emails, addresses, groups, favorites, and notes all at once?**
No - these aren't separate *responsibilities* in the SRP sense (separate
reasons to change); they're all facets of one responsibility ("represent a
contact's identifying/reachability data"), and all change for the same
reason (the domain concept "contact" evolving). SRP would be violated if
`Contact` also contained, say, rendering logic or persistence SQL - it
doesn't; those live in `ContactService`/`InMemoryContactRepository`. The
meaningful SRP boundary here is "data shape" (`Contact`) vs. "behavior
coordination" (`ContactService`) vs. "storage" (`ContactRepository`), which
this design does respect.

---

## 2. SOLID Principles

**Q2.1. Where is the Open/Closed Principle demonstrated concretely in this code, not just asserted?**
Adding a new duplicate-matching rule (e.g. a hypothetical phonetic-match
strategy) requires writing one new class implementing
`DuplicateMatchStrategy` and passing it into
`DuplicateDetectionService`'s constructor - zero existing classes are
modified. Same for a new `NameIndex` algorithm or a new `ContactCodec`
format. `NameIndexContractTest` is itself proof of this: it runs identical
test scenarios against two different `NameIndex` implementations through
one shared contract, with no test-specific branching per implementation.

**Q2.2. Where does Dependency Inversion actually matter in this design, beyond "it's good practice"?**
`ContactService` depends on the `ContactRepository` *interface*, not
`InMemoryContactRepository` directly - this is what makes the CAS-
correctness unit tests possible without touching real concurrency timing:
a test can construct a real `InMemoryContactRepository` (not a mock, since
the correctness being tested is inherent to that specific implementation)
while `ContactService` itself never needed to know which concrete
repository it would get. It is also precisely what makes section 12's
"swap to a real database later" claim true rather than aspirational - the
seam already exists.

**Q2.3. Does the Liskov Substitution Principle hold between `TrieNameIndex` and `SortedArrayNameIndex`?**
Yes, and it's actively tested, not just assumed: `NameIndexContractTest`
parameterizes the exact same behavioral assertions (insert, prefix search,
case-insensitivity, removal, empty results) across both implementations via
`@MethodSource`. Any caller holding a `NameIndex` reference gets
identical, substitutable behavior regardless of which concrete type was
injected - the only difference is internal performance characteristics
(section 10), never observable behavior.

**Q2.4. Is there an Interface Segregation violation anywhere - e.g. does any class implement a method it doesn't need?**
No forced no-op implementations exist in this codebase. `ContactRepository`
and `GroupRepository` are deliberately *not* the same interface (despite
structural similarity) specifically because `GroupRepository` doesn't need
`compareAndSet` (see Staff-Level Deep Dive Q23) - merging them into one
generic `Repository<T>` interface would force `GroupRepository` to either
implement a CAS method it doesn't use meaningfully, or throw
`UnsupportedOperationException`, both of which are Interface Segregation
smells this design avoids by keeping them separate.

---

## 3. Design Patterns

**Q3.1. Could the Observer pattern here be replaced with explicit polling (e.g. `IndexManager` periodically scans the repository for changes)? What would be lost?**
Polling would trade an immediate, event-driven index update for a
staleness window bounded by the poll interval - a prefix search could miss
a contact added microseconds ago until the next poll cycle runs. It would
also need to diff "what changed since last poll" against potentially the
entire repository, reintroducing an O(n) cost on every poll tick regardless
of how few contacts actually changed - the Observer pattern gets this for
free by only ever processing the one changed contact per event.

**Q3.2. Why Strategy and not a simple `enum` with a `switch` for duplicate-matching rules?**
An enum+switch approach would require editing one central `switch`
statement every time a new matching rule is added or an existing rule's
logic changes - a direct Open/Closed violation, and it also can't express
a *composite* of rules cleanly (ANY/ALL combination of several strategies)
without the switch statement itself growing combinatorially. Strategy
objects compose naturally (`CompositeDuplicateMatchStrategy` wraps a
`List<DuplicateMatchStrategy>`), which an enum-based design can't do
without becoming its own mini-strategy-pattern internally anyway.

**Q3.3. The Builder pattern is also usable for *querying* (e.g. a `SearchCriteriaBuilder`). Why wasn't it used for search in this design?**
Because none of this system's search operations have enough optional,
combinable parameters to justify it - `searchByNamePrefix(prefix, limit)`,
`searchByPhone(raw)`, etc. are each 1-2 parameters, well within normal
method-signature readability. A query builder earns its complexity when
you have many optional filters that combine in varied ways (e.g. "name
starts with X AND in group Y AND favorite only") - if `SearchService` grew
that kind of combinatorial filtering requirement, a builder would become
justified at that point, not before.

---

## 4. Data Structures

**Q4.1. Why `LinkedHashSet`-style ordered de-duplication for phones/emails instead of a plain `HashSet`?**
A plain `HashSet` would de-duplicate correctly but with undefined iteration
order, meaning a contact's phone list could print in a different order
than the user entered it (or than a previous export produced) - bad for a
human-facing field where entry order usually corresponds to the user's own
sense of "primary" vs. "secondary" number. Preserving insertion order
costs nothing extra algorithmically (same O(1) average membership check)
and materially improves UX/export stability.

**Q4.2. Why does `HashLookupIndex` map a normalized key to a `Set<UUID>` rather than a single `UUID`?**
Because uniqueness of phone/email across contacts isn't a system
invariant - two different people's contact records could coincidentally
share a work phone extension, or more realistically, duplicate (not-yet-
merged) contacts for the *same* real person would, by definition, share a
phone/email. Mapping to a set, not a single id, is also precisely what
makes `HashLookupIndex` directly reusable as `DuplicateDetectionService`'s
candidate-generation source - a single-id mapping couldn't represent "these
N contacts all claim this phone number" at all.

**Q4.3. What's the Big-O of `Contact.toBuilder()`, and why does that matter for the CAS retry loop's performance?**
`toBuilder()` is O(F) where F is the number of fields/collection elements
on that one contact (copying phone/email/address lists) - independent of
total address-book size. Since `ContactService.update`'s retry loop calls
`toBuilder()` once per attempt, and each attempt is bounded by
`MAX_CAS_RETRIES`, the entire retry loop's cost is O(F * retries), which
in the common (no-contention) case is just O(F) for a single attempt -
cheap and bounded regardless of how many contacts exist overall.

---

## 5. Java Internals

**Q5.1. Why are `Contact`'s collection fields stored as `List.copyOf(...)`/`Set.copyOf(...)` rather than just wrapping with `Collections.unmodifiableList`?**
`Collections.unmodifiableList(list)` returns a *view* over the original
mutable list - if the caller who passed that list in still holds a
reference to it and mutates it afterward, the "immutable" view would
reflect that external mutation, silently breaking `Contact`'s immutability
guarantee. `List.copyOf(...)` creates a genuinely independent, immutable
copy at construction time, so no external reference can ever affect the
`Contact` instance after it's built - the correct choice whenever true
immutability (not just a read-only *view*) is the guarantee being made.

**Q5.2. Why use Java records for `PhoneNumber`/`EmailAddress`/etc. instead of regular classes with manually-written `equals`/`hashCode`?**
Records give compiler-generated, correct-by-construction value-based
`equals`/`hashCode`/`toString` based on every component, eliminating an
entire class of bugs (forgetting to update `hashCode` after adding a field,
inconsistent `equals`/`hashCode` contracts) that hand-written value objects
are prone to. They're also inherently final and their components are
implicitly final, reinforcing the immutability story at the language level
rather than relying on convention.

**Q5.3. `ConcurrentHashMap.computeIfPresent`'s remapping function must not have side effects or block - does `compareAndSet`'s lambda violate this?**
It's a narrow case worth checking carefully: the lambda inside
`compareAndSet` only reads a captured local (`expected`), compares by
reference, and sets a plain `AtomicBoolean` flag (a cheap, non-blocking,
uncontended local write, not a lock or I/O operation) - it does not call
back into the map itself (no nested `ConcurrentHashMap` operations on the
same map, which actually *is* disallowed and would throw
`IllegalStateException` for recursive update attempts) and does no
blocking I/O. This satisfies `ConcurrentHashMap`'s documented constraint
that remapping functions should be quick and non-blocking and must not
attempt to update any other mappings of this map.

**Q5.4. Why does `ContactNormalizer` use `String` processing rather than `java.text.Normalizer` for Unicode normalization?**
This implementation's normalization is deliberately narrow in scope -
digits-only extraction for phones, trim+lowercase for emails - neither of
which requires full Unicode canonical-equivalence normalization (relevant
for things like combining diacritical marks, e.g. "é" represented as one
codepoint vs. "e" + combining accent). A production system handling
international names/emails with such characters would likely want
`java.text.Normalizer.normalize(s, Form.NFC)` as a preprocessing step
before this implementation's simpler logic - named here as a real gap for
true internationalization, not silently assumed away.

---

## 6. Multithreading

**Q6.1. Could you replace `ReentrantLock structuralLock` with a plain `synchronized` block? What, if anything, would be lost?**
Functionally, for exactly this use case (one lock, no fairness/timeout/
interruptibility requirements), a `synchronized` block would behave
equivalently in terms of mutual exclusion. The reasons `ReentrantLock` was
chosen anyway: it supports `tryLock(timeout)` if a future requirement
needed "give up waiting for the merge lock after N ms" (not currently
used, but a trivially available extension point without changing the
lock type), and it makes lock acquisition/release explicit in code
(`lock()`/`unlock()` in a `try/finally`) which is arguably clearer to read
for a single, deliberately-coarse lock whose scope spans multiple
statements, versus a `synchronized` block's implicit scoping.

**Q6.2. Your concurrency test uses a `CountDownLatch` to synchronize thread start. Why does that matter for the test's validity?**
Without a shared start signal, threads created in a loop would start at
slightly different times (thread creation/scheduling isn't instantaneous),
meaning by the time the last thread starts, earlier threads may have
already completed - collapsing what's supposed to be a genuine concurrent
race into an accidentally-sequential execution that would pass even if the
underlying CAS logic were broken. The `CountDownLatch` ensures all threads
block at a shared starting line and are released simultaneously, maximizing
the actual probability of real concurrent contention on the same CAS
operation - this is what makes
`concurrentUpdatesToTheSameContactAreNotLost` a meaningful test rather than
a flaky illusion of one.

**Q6.3. Is there a risk of a `volatile`-visibility bug anywhere in this design - i.e. a field that needs `volatile` but doesn't have it?**
No plain (non-concurrent-collection, non-`final`) mutable shared field
exists anywhere in this design - every genuinely shared mutable location is
either a `ConcurrentHashMap`/`ConcurrentLinkedDeque`/`AtomicBoolean` (which
internally guarantee the necessary happens-before relationships for safe
publication across threads) or a `final` field on an otherwise-immutable
object (which needs no visibility guarantee beyond safe construction,
itself guaranteed by the JVM's final-field semantics as long as the
reference doesn't escape during construction - which `Contact.Builder`'s
`build()` method doesn't do). This is worth explicitly checking field-by-
field when asked, not simply asserting "we used concurrent collections so
we're fine."

**Q6.4. What would happen if `MAX_CAS_RETRIES` were set to 1 instead of 100 - purely a performance change, or could it affect correctness?**
Purely a liveness/performance change, not a correctness one:
`OptimisticLockException` would be thrown far more readily under even mild
contention (two threads racing once would already exhaust a budget of 1),
making the system *less available* under concurrent load, but it would
never cause a lost update or any data corruption - CAS's correctness
guarantee (a swap only succeeds if the reference truly hasn't changed)
holds regardless of how many retries are permitted. The retry count is a
tuning knob for how much "retry and hope for less contention" effort the
system invests before giving up and surfacing the conflict to the caller.

---

## 7. Database / Persistence

**Q7.1. If moving to a real database, would you keep `ContactService.update`'s retry-loop *shape*, or redesign it around transactions?**
Keep the shape, change the mechanism (README section 12): the exact same
"read current state + version, build new state, conditionally write only
if version unchanged, retry on conflict" loop maps directly onto an
optimistic-locking `UPDATE ... WHERE id = ? AND version = ?` statement
checked against affected-row-count, instead of
`ConcurrentHashMap.computeIfPresent`'s reference check. This is a
deliberate design win worth calling out: the in-memory implementation was
built to mirror exactly the concurrency pattern a real database-backed
version would use, not a throwaway abstraction that would need rethinking
later.

**Q7.2. Would `mergeContacts`'s two-write operation (update keep, delete duplicate) need to change at all with a real transactional database, versus the current coarse lock?**
Yes, meaningfully: with a real database, the two writes would be wrapped
in a single ACID transaction (`BEGIN; UPDATE ...; DELETE ...; COMMIT;`),
which gets atomicity "for free" from the database rather than needing an
application-level `ReentrantLock` to simulate it - the database's own
isolation level (at least `REPEATABLE READ`, ideally `SERIALIZABLE` for
the strictest guarantee against a concurrent read of a half-merged state)
replaces `structuralLock` entirely. This is a case where moving to
persistence *simplifies* a problem this in-memory design had to solve by
hand.

**Q7.3. How would duplicate detection's "blocking" strategy change if phone/email indexes became real database indexes instead of in-memory hash maps?**
The algorithmic shape stays identical - look up candidates by normalized
phone/email/name-token first, then run the matching strategy only against
that small candidate set - but the lookup itself becomes a SQL query
(`SELECT contact_id FROM contact_phones WHERE normalized_value = ?`)
hitting a B-tree index instead of an in-memory `ConcurrentHashMap` get,
trading O(1) average for O(log n) (still vastly better than an O(n) table
scan, and the same qualitative improvement over "no blocking at all" that
the in-memory version provides).

---

## 8. Performance

**Q8.1. If profiling revealed `IndexManager.onChange`'s full reindex was a measurable hot path, what would the first optimization be, concretely?**
Diff the old and new `Contact` snapshots field-by-field before touching
any index: compute the set difference of old phones vs. new phones (only
remove what's gone, only add what's new), same for emails/tokens/groups,
instead of unconditionally removing every old value and adding every new
one regardless of whether it actually changed. For a typical single-field
edit (e.g. just toggling `favorite`), this would turn ~2*(P+E+T+G) index
operations into effectively zero phone/email/token operations and one
favorites-set operation - a meaningful constant-factor win without
changing any data structure.

**Q8.2. Is there a more cache-friendly alternative to `TrieNameIndex`'s node-based structure for very large address books?**
Yes - a common production alternative is a **compressed/array-based trie**
(e.g. a double-array trie, or simply encoding the trie as flat arrays of
child-indices instead of per-node `HashMap<Character,Node>` objects),
which trades implementation complexity for dramatically better CPU-cache
locality (contiguous arrays vs. scattered heap objects linked by
references) - highly relevant at a scale where the trie no longer fits in
CPU cache and pointer-chasing dominates latency. This is named as a
legitimate next step rather than built here, since implementing it
correctly is a substantial undertaking disproportionate to this problem's
interview scope.

**Q8.3. Why is `searchByNamePrefix`'s `limit` parameter applied *after* the index lookup rather than short-circuiting the index traversal itself?**
`TrieNameIndex.searchByPrefix` returns the full id-set for the matched
node in O(P) (reading one node's already-materialized set, not iterating
character-by-character to build it up) - there's no traversal step to
short-circuit in the eager-aggregation design; the "expensive" part is
already O(1) relative to result size once the node is found. Applying
`limit` at the `SearchService` layer (after resolving ids to `Contact`
objects) is where it actually matters, since resolving each id into a full
`Contact` via repository lookup is the real per-result cost worth
capping early.

---

## 9. Failure Handling

**Q9.1. What's this system's story for a caller that holds a stale `Contact` reference across a long user-think-time gap (e.g. an edit form left open for 10 minutes), then submits an edit?**
The CAS mechanism handles this correctly without any special-casing:
`ContactService.update`'s retry loop reads the *current* persisted state
fresh on every attempt and only ever compares against that fresh read, not
against whatever the caller originally had - so a 10-minute-stale `Contact`
reference simply means the caller's *mutator* (the `Consumer<Builder>`)
gets applied against the server's current state on the first attempt,
which is the correct behavior (last-based-on-current-data, not last-
based-on-what-the-caller-originally-loaded). If the caller's mutator logic
itself assumed specific prior field values that have since changed
(a genuine conflict, not just staleness), that's a UX/product decision
about optimistic-UI conflict resolution outside this system's scope -
named explicitly rather than claimed as solved.

**Q9.2. If `ImportExportService.importAll` processes a batch of 1000 vCard records and record #500 throws an unexpected `RuntimeException` (not `InvalidContactException`), what happens to records 1-499 and 501-1000?**
As implemented, `importAll` does not currently wrap each record's import
in its own try/catch - an uncaught `RuntimeException` on record 500 would
propagate up and abort the entire batch, losing records 501-1000 (though
499 already-created contacts remain persisted, since each `create()` call
already committed independently). This is a real, honestly-named gap: a
hardened version would catch per-record exceptions, collect a results
object (`successes`, `failures-with-reasons`), and guarantee the batch
always completes regardless of individual-record failures - worth stating
as a concrete "if I had more time" improvement rather than assuming the
current skip-malformed-*line* behavior already covers this case (it
doesn't - that's a parsing-level tolerance, not an exception-safety
guarantee at the batch level).

**Q9.3. How would you detect, in production, that `IndexManager`'s derived indexes have drifted out of sync with the source-of-truth repository (e.g. due to a bug)?**
A periodic consistency-check job comparing index contents against a full
repository scan (e.g. "every contact id in the repository should be
findable via at least one of its own phone/email/name-token lookups, and
no index should reference an id absent from the repository") - expensive
(O(n)) so run infrequently (e.g. nightly) rather than per-request, purely
as a drift detector/alerting mechanism, with the remediation being a full
index rebuild from the repository (cheap to do, since indexes are entirely
derived/rebuildable state, never the system of record).

---

## 10. Production Architecture

**Q10.1. If this were exposed as an HTTP API, where would rate limiting and request-level idempotency keys fit relative to the layers in this design?**
Both belong at a layer *above* everything shown here (an API gateway or a
thin controller layer translating HTTP requests into calls against
`ContactManagementSystem`'s sub-services) - rate limiting is a pure
cross-cutting concern with zero domain knowledge needed, and idempotency
keys (deduplicating retried `POST /contacts` requests) would most
naturally be implemented as a short-lived key-to-result cache at that same
boundary, checked *before* ever calling `ContactService.create`, rather
than inside the domain layer which has no concept of "this is a retry of
an earlier network request."

**Q10.2. How would you evolve this design to support audit/compliance requirements (e.g. "show me every change ever made to this contact, by whom")?**
Two changes: (1) `HistoryService`'s bounded, undo-oriented history would
need to become an unbounded, durable, *append-only* audit log (distinct
from the undo stack - undo needs only recent entries and can safely evict
old ones, while an audit trail legally cannot), likely persisted
separately from the undo mechanism rather than overloading one structure
for both purposes; (2) every mutating call would need an actor/identity
parameter threaded through `ContactService` (currently absent, since
single-tenant in-process access has no caller-identity concept) - named
explicitly as a non-trivial API-surface change, not a drop-in addition.

**Q10.3. In a multi-region deployment, how would you handle a merge operation where the two contacts being merged live in different regional shards?**
This is a cross-shard distributed transaction problem, not something
`structuralLock` (a single-process primitive) can solve at all once
sharded across regions - the realistic approaches are either (a) a two-
phase-commit-style protocol across the two shards' databases (complex,
with its own coordinator-failure modes), or (b) redesigning merge as a
**saga**: a sequence of locally-committed steps (mark duplicate as
"merging" in its home shard, copy/union its data into the keep contact's
shard, mark duplicate deleted, confirm) with an explicit compensating
action for each step if a later step fails - accepting eventual rather
than immediate cross-shard consistency during the merge window. Naming
this as a saga rather than claiming a simple lock would "just work" across
regions is itself the correct staff-level answer.

**Q10.4. What's the right boundary for a "Contacts" microservice vs. folding it into a larger "CRM" or "Identity" service - how does this design's structure inform that?**
This design's internal seams (storage behind `ContactRepository`,
indexing behind `NameIndex`/`IndexManager`, business logic behind
`ContactService`) are exactly the seams a microservice boundary should
respect even *inside* a monolith - meaning the natural extraction point,
if this grew into its own service, is the whole `ContactManagementSystem`
facade's surface (contacts + groups + search + duplicates + import/export)
as one cohesive bounded context, since those concerns all share one
aggregate root (`Contact`) and one consistency domain (a merge touches two
contacts, never a contact and something from an unrelated domain). Pulling
only *part* of it out (e.g. "just search" as a separate service) would cut
across `IndexManager`'s Observer relationship with the repository,
reintroducing exactly the dual-write consistency problem this design
currently avoids by keeping indexing in-process and synchronous.
