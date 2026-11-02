# Staff-Level Deep Dive — Contact Management System

Twenty-four problem-specific questions a staff-level panel would plausibly
ask about *this exact implementation*, with reasoning-based answers that
reference real classes/methods. Generic "what is a hash map" questions are
deliberately excluded - see FOLLOW_UP_QUESTIONS.md for the broader CS-
fundamentals set.

---

### 1. Why is `Contact.equals()` id-based, and what would break if it were field-based?

If `Contact.equals()` compared every field, then `InMemoryContactRepository
.compareAndSet`'s reference-identity check (`current == expected`) would
still be correct (it never relied on `.equals()` in the first place), but
every other place `Contact` is placed in a `Set`/used as a `Map` key (e.g.
inside test assertions, or a hypothetical future "recently viewed contacts"
`LinkedHashSet`) would treat two different versions of the same real-world
contact as *different* objects the moment any field changed - subtly wrong
for any code that means "the same contact, possibly a newer version." Id-
based equality is the correct entity semantics: a `Contact` is the same
contact across its edit history. The price is that callers must not assume
`.equals()` implies "identical data" - documented explicitly in `Contact`'s
javadoc.

### 2. Walk me through exactly why `ConcurrentHashMap.replace(key, old, new)` would be a real, exploitable bug here - not just a style preference.

`replace(key, old, new)` succeeds iff `map.get(key).equals(old)`. Because
`Contact.equals()` is id-based, *any* two snapshots of the same contact are
`.equals()`. Concretely: thread A reads `v1`, computes `v2` (adds a phone).
Thread B, concurrently, reads `v1` too, computes `v3` (adds a different
phone). Thread A calls `replace(id, v1, v2)` - succeeds, map now holds
`v2`. Thread B calls `replace(id, v1, v3)` - `map.get(id)` is `v2`, but
`v2.equals(v1)` is `true` (same id!), so this *also* succeeds, overwriting
`v2` with `v3` and **silently discarding thread A's phone addition** - a
classic lost update, and the system would report success to both callers
despite one update vanishing. `InMemoryContactRepository.compareAndSet`
instead uses `computeIfPresent` with `current == expected` (reference
identity), so thread B's second call correctly fails because the object
reference changed, not just because some field changed.

### 3. Why is the "must have a phone or email" rule not just a constructor check on `Contact`?

Because it's a *business policy*, not a *structural* invariant. A
structural invariant ("the name field is not blank") is something no valid
instance of the type could violate under any reasonable business policy.
"Must be reachable" is policy that a different deployment (e.g. an import
staging area that intentionally allows incomplete placeholder records
before a human fills them in) might legitimately relax. Putting it in
`ContactService.validateBusinessInvariants` keeps `Contact` reusable across
policies while still guaranteeing it's enforced at the one real persistence
boundary (`create`/`update`). It also means tests can construct
"incomplete" `Contact` instances directly via the builder for fixture setup
without fighting the business rule.

### 4. Your Trie does eager aggregation at every node. Quantify the memory cost vs. a leaf-only Trie for a concrete example.

For the name "Smith" (5 characters) inserted once: eager aggregation stores
the contact's id in the id-set of all 5 nodes along the path (S, Sm, Smi,
Smit, Smith) - 5 set-entries total for one name. A leaf-only variant stores
it at exactly 1 node (the terminal node for "Smith") - 1 set-entry. For n
contacts whose names average length L, eager aggregation costs O(n*L) id-
set entries total vs. O(n) for leaf-only - an L-times memory multiplier
(commonly 5-10x for typical names). The payoff: eager aggregation answers
"all ids under prefix P" in O(P) (just read one node's set), while leaf-
only needs O(P) to find the node plus a full subtree DFS (O(nodes in
subtree)) to union every leaf's set - the eager version turns a potentially
expensive subtree walk into a single map lookup.

### 5. Could `SortedArrayNameIndex` be made to support O(log n) insert too? What's actually stopping it?

The binary search *location* step is O(log n) either way - that's not the
bottleneck. The bottleneck is that inserting into (or deleting from) a
plain array/`ArrayList` at an arbitrary index requires shifting every
subsequent element, which is O(n) regardless of how fast you *found* the
index. To get O(log n) insert as well, you'd need a different underlying
structure entirely - a balanced BST (e.g. a skip list, or a `TreeMap`-of-
name-to-idset) - at which point you've essentially reinvented a different
trade-off point (closer to the Trie's behavior but keyed on whole strings
rather than per-character nodes). This repo keeps the plain sorted-array
implementation specifically *because* its simplicity is the point of the
comparison - introducing a balanced tree here would blur the three-way
contrast the problem asks for.

### 6. Why does `DuplicateDetectionService` use index lookups as "blocking" instead of just querying the repository directly?

Querying the repository directly for "find me every contact" is O(n) just
to enumerate candidates, before any matching even starts - the indexes
already *are* a decomposition of contacts by their matchable attributes
(phone, email, name token), so reusing them for duplicate detection is
free reuse of infrastructure that would otherwise require either
maintaining a second index purely for this feature, or accepting the O(n)
scan. The "blocking" terminology comes from record-linkage literature:
partition records into small buckets by a cheap key so expensive pairwise
comparison only happens within a bucket, not across the whole dataset.

### 7. What happens to duplicate-detection recall if a contact's name has a typo that index-token matching can't catch?

`DuplicateDetectionService`'s indexed candidate generation only finds
candidates sharing an *exact* normalized phone/email or an exact name
*token* (not a fuzzy/edit-distance match) - so two genuinely duplicate
contacts whose only shared signal is a misspelled name variant ("Jon Smith"
vs "John Smith") with no shared phone/email would be **missed entirely**
(a recall gap, not a precision problem). This is explicitly named in
README section 2 ("fuzzy/phonetic name matching... out of scope") and is
exactly why `DuplicateMatchStrategy` is a pluggable interface - a future
`FuzzyNameMatchStrategy` could be added, but it would need a different
*candidate-generation* step too (e.g. a phonetic/Soundex-keyed index),
since the current blocking step itself would never surface that candidate
pair in the first place. This is worth stating proactively: the strategy
pattern alone doesn't fix a blocking-step recall gap.

### 8. Why is `mergeContacts` guarded by a coarse lock instead of two ordered per-contact locks?

Two ordered per-contact locks (e.g. always lock the smaller UUID first)
would indeed avoid the global bottleneck and avoid deadlock between two
merges on disjoint pairs - but it adds real complexity: you need a lock
registry keyed by contact id (with its own lifecycle - when do you evict
an unused per-contact lock object without racing a thread about to acquire
it?), and you must prove no other code path ever acquires those same
per-contact locks in a different order. Given merges are expected to be
infrequent relative to single-field edits in this problem's realistic
workload, the coarse lock's simplicity (trivially deadlock-free, see
README section 11) outweighs its cost. This is explicitly named as a
trade-off that would flip for a bulk automated de-duplication workload
(README section 16).

### 9. You said a concurrent single-field update can race a merge and fail with `OptimisticLockException` rather than being silently lost - prove that claim.

`ContactService.update(keepId, ...)` reads `current`, builds `updated`,
calls `repository.compareAndSet(keepId, current, updated)`. Concurrently,
`mergeContacts` (holding `structuralLock`) reads its own snapshot of
`keepId`, computes `merged`, and calls
`repository.compareAndSet(keepId, keepSnapshot, merged)`. Whichever CAS
call executes second against the *same* stored reference will find the map
no longer holds the reference it expected (the first call already swapped
it), so its `compareAndSet` returns `false`. `ContactService.update`'s
retry loop would then re-read the new value and retry (eventually either
succeeding by replaying its own mutation on top of the merge's result, or
exhausting `MAX_CAS_RETRIES` and throwing `OptimisticLockException` only
under truly pathological contention) - so the realistic single-collision
case is "retry and succeed," not data loss; "silent loss" would only mean
a thread's write vanished with no error and no re-application, which CAS
specifically prevents by construction.

### 10. Why does `undo()` use unconditional `save()` instead of CAS - isn't that inconsistent with every other mutation?

It's a deliberate, named exception (README section 11), not an oversight.
Every other mutation represents "I'm editing based on what I last saw" -
CAS correctly rejects it if that basis is stale. `undo()` represents "I
want to go back to this specific prior state, full stop" - a user-
initiated override where the entire point is to discard whatever the
current state is, even if something changed since. Using CAS for undo
would mean an undo could spuriously fail just because something else
touched the contact in the meantime, which contradicts what "undo" means
to a user clicking the button. The trade-off: if another legitimate edit
happened between the undo's triggering event and its execution, that edit
is overwritten by the undo - accepted, documented, matches how undo behaves
in virtually every real editing tool (last action taken wins).

### 11. Is `HistoryService`'s per-contact history actually bounded correctly under concurrency? Walk through the race.

The trim loop is `while (deque.size() > MAX_HISTORY_PER_CONTACT)
deque.pollFirst()`. If two threads call `record()` for the *same* contact
near-simultaneously, both may observe `size() == MAX+1` before either
polls, and each independently trims one entry - net effect, the deque ends
up one entry *under* the cap rather than exactly at it; conversely, a
window exists where a read of `size()` is stale by the time `pollFirst()`
runs, potentially leaving it transiently one *over*. Neither case is a
correctness bug for undo's actual contract (undo only ever needs the *most
recent* entry, which is never incorrectly evicted by this race - only
*older* entries' exact retention count is approximate). This is explicitly
accepted in the class's javadoc, since lock-free `ConcurrentLinkedDeque` is
worth the approximate-bound trade for a feature where exact capacity isn't
user-visible.

### 12. Why is phone normalization lazy (computed on demand) while email normalization is eager? Isn't that an inconsistency?

It's a deliberate asymmetry, not an inconsistency: `PhoneNumber` keeps
`rawValue` as the single source of truth and *derives* `normalized()` on
each call, because the raw, human-entered format ("(555) 123-4567" vs.
"555.123.4567") is itself meaningful *display* information a UI would want
to preserve verbatim - normalizing it away eagerly and keeping only the
normalized form would lose that. `EmailAddress`, by contrast, normalizes
(trim + lowercase) *eagerly into its one stored field* because email
normalization is lossless and canonical - there's no legitimate reason a
product would want to display "John.Smith@Example.COM" differently from
"john.smith@example.com"; collapsing them immediately avoids ever
accidentally treating two normalized-equal emails as different data. The
inconsistency would actually be applying the *same* policy to both, given
they have different display-fidelity requirements.

### 13. `IndexManager.onChange` reindexes a contact's *entire* field set even for a single-field update. What's the actual cost, and when would it matter?

For a contact with P phones, E emails, T name tokens, and membership in G
groups, a single `onChange(UPDATED)` call does roughly
`O(P + E + T + G)` remove operations (against the old snapshot) plus the
same count of add operations (against the new snapshot) - bounded strictly
by *that one contact's* field count, never by the address book's total
size. For a typical contact (a handful of each), this is a small constant
- it would only matter in a pathological case (a "contact" with hundreds of
phone numbers, which would itself be an unusual data model) or under
extremely high update-rate workloads where even small constant overheads
compound. The fix named in README section 14 (a proper old-vs-new diff
instead of blanket deindex-then-reindex) is explicitly deferred as not
worth the complexity until profiling shows it matters.

### 14. Why does `GroupService`, not `ContactService`, own cascading delete of group memberships - what would go wrong the other way around?

If `ContactService` owned group-membership cascading cleanup, it would
need a dependency on `GroupRepository` (to know a group was deleted) or
`GroupService` would need to notify it - either way, `ContactService`
(the most heavily used, most foundational service) would gain a dependency
on group lifecycle, when the dependency naturally flows the other way
(groups reference which contacts are members, not vice versa, in terms of
*lifecycle* responsibility). Keeping cascading delete in `GroupService`
(which already depends on `ContactService` + `IndexManager` for exactly
this reason) keeps `ContactService` acyclic and reusable even in a
hypothetical deployment with no groups feature at all.

### 15. Your Facade (`ContactManagementSystem`) exposes sub-services via getters rather than delegating every method itself. Isn't that a leaky abstraction?

It's a deliberate choice documented in README section 7: a "fat" facade
that re-declares every one of `ContactService`/`GroupService`/
`SearchService`/etc.'s methods would be dozens of pure pass-through
methods with zero added logic - pure ceremony that would need updating
every time any sub-service's API changed, for no behavioral benefit. The
getter-based facade is honest about what it actually does (wire the object
graph) rather than pretending to be a unified API surface it isn't. The
trade-off: callers see the sub-service boundaries (arguably *more*
informative, since it's clear which concern each call belongs to) rather
than one flat namespace - this is treated as a feature, not a leak, for an
LLD answer whose whole point is demonstrating separated responsibilities.

### 16. Is there a scenario where `TrieNameIndex`'s read/write lock becomes a correctness problem, not just a performance one?

No - `ReentrantReadWriteLock` guarantees that once a writer holds the write
lock, no reader can observe a partially-updated trie (readers block until
the writer releases), and multiple readers can safely run concurrently
against each other since none of them mutate anything. It's purely a
*performance* ceiling (all writes fully serialize against all reads and
each other) - not a correctness risk. The correctness risk would only
appear if a method forgot to acquire the lock before touching the
underlying `Map`/node structure - worth explicitly checking (and this
implementation does) that every public mutating/reading method on both
`TrieNameIndex` and `SortedArrayNameIndex` acquires the appropriate lock
before touching shared state.

### 17. If this had to scale to 100 million contacts with sub-100ms prefix search, what's the first thing you'd change, and why that first?

The first thing is **not** the algorithm - it's that an in-memory Trie/
sorted-array can no longer live entirely in one process's heap at that
scale; the first real change is externalizing the name index to a durable,
horizontally-scalable search service (e.g. Elasticsearch/OpenSearch with
an edge-ngram or prefix-query analyzer), keeping `NameIndex` as the
interface contract but backing it with a remote-search-backed
implementation. Changing the in-memory algorithm choice (Trie vs. sorted
array) is a second-order optimization that doesn't address the fundamental
"doesn't fit in one process" constraint - naming the right first move,
not just a plausible one, is what distinguishes a staff-level answer here.

### 18. Why is there no `redo()` if `undo()` already exists - what would it take to add, and what's the risk?

`HistoryService` only maintains a single backward-looking stack
(`Deque<Contact>` per contact, most-recent-first via `addFirst`/
`pollFirst`), with no forward stack to hold "undone" states for potential
redo. Adding redo would mean: on `undo()`, push the *current* (about-to-be-
replaced) state onto a second, parallel redo-stack instead of discarding
it; on any *new* edit (not an undo/redo), clear the redo-stack (standard
editor semantics - a fresh edit invalidates the redo history). The risk is
entirely about interaction correctness, not data structures: get the
"clear redo stack on new edit" rule wrong, and a user could redo into a
state that contradicts an edit they made in between - a confusing, hard-
to-test-exhaustively UX bug class, which is exactly why it's named as an
unbuilt stretch feature rather than rushed in.

### 19. `ContactMerger.merge` is a static/pure function - what's the testing benefit of that separation from `ContactService.mergeContacts`?

`ContactMerger.merge(keep, duplicate)` takes two `Contact` values and
returns a third, with no repository, no locking, no side effects -
making its field-union/conflict-resolution logic (which phone list wins
when both have a "MOBILE" type with different numbers, how notes are
concatenated, how group sets union) directly unit-testable with plain
object construction and assertion, no `InMemoryContactRepository` or
threading setup required. `ContactService.mergeContacts` is then tested
separately for its orchestration concerns (locking, CAS, the duplicate's
deletion, undo-ability) using fakes/real repositories as appropriate - the
two test suites can fail independently and point precisely at "the merge
*rules* are wrong" vs. "the merge *coordination* is wrong," which a single
combined method+test would conflate.

### 20. Why does `VCardContactCodec` skip malformed lines instead of failing the whole import - isn't silent data loss worse than a loud failure?

It's framed as *partial success*, not *silent* loss: the codec's contract
(README section 13) is that one unparseable *line* doesn't sink an entire
*record*, and one unparseable record (in `importAll`) doesn't sink an
entire *batch* - this mirrors how real-world vCard exports often contain
vendor-specific quirks or minor corruption, and a strict all-or-nothing
import would make the whole feature far less useful in practice (reject an
entire 500-contact export because one line had a stray character). The
"silent" characterization would be fair if there were no way to know what
happened - in a production system (named in section 17) this would emit a
structured per-line/per-record warning rather than literally saying
nothing; for the interview-scope implementation, the policy decision
(skip, don't fail) is the one being defended here, and it's a legitimate,
named trade-off, not an oversight.

### 21. Could two concurrent `create()` calls for what's actually the same real-world person both succeed, creating a duplicate the user didn't ask for?

Yes - `create()` has no identity-collision concept at creation time
(each call gets a fresh UUID), so two concurrent "add this new contact"
calls carrying the same phone number would both succeed and both be
persisted, relying entirely on `DuplicateDetectionService` to *later*
surface them as a suggested merge rather than preventing the duplicate at
creation. This is a conscious scope line (README's clarifying question 3:
suggest-only, not auto-merge/auto-reject) - preventing it outright would
require either a synchronous uniqueness check against the phone/email hash
index at creation time (a real option, but introduces its own race: two
concurrent creates could both pass a "does this phone already exist" check
before either commits, so it would need a transactional/locking guarantee
beyond what a single index lookup gives) or accepting the duplicate and
relying on post-hoc detection, which is what's implemented.

### 22. How would you unit test that `IndexManager` never leaves stale entries behind after a contact is deleted?

Delete a contact via `ContactService.delete`, then assert, against the
*same* `IndexManager` instance the service is wired to, that: (a)
`searchByNamePrefix` for every token of the deleted contact's name no
longer includes its id, (b) `findByNormalizedPhone`/`findByNormalizedEmail`
for each of its contact points no longer includes its id, and (c) it's
absent from the favorites set and every group's member set it previously
belonged to. This is precisely what
`IndexManagerTest`/`GroupServiceTest`'s cascading-delete-style assertions
exercise - the key testing principle is asserting *absence* after delete,
not just asserting *presence* after create (a test suite that never checks
the delete path could pass even if deletion silently leaked stale index
entries).

### 23. Your `InMemoryGroupRepository` isn't exercised by the same CAS-correctness negative-control test as contacts. Is that a real gap?

It's a legitimate question to probe, and the honest answer is: it's a
smaller risk, not a non-issue. `GroupService`'s mutations (`createGroup`,
`renameGroup`, `deleteGroup`) are lower-frequency, typically human-
triggered operations with much lower realistic contention than per-contact
edits, and `Group` is a simpler record with no multi-field concurrent-edit
scenario analogous to "two phones added to the same contact
simultaneously." That said, if `GroupRepository` exposed its own
`compareAndSet`, the same `.equals()`-based-vs-reference-identity question
would apply identically, and it would deserve the same negative-control
test - this implementation currently relies on `InMemoryGroupRepository`
using simple `save`/`delete` without a CAS protocol at all (lower
concurrency guarantees than `ContactRepository`, by design, given the
lower contention profile), which is itself worth stating explicitly rather
than assuming parity with `ContactRepository` that doesn't actually exist.

### 24. If you had to remove exactly one design pattern from this system to simplify it under time pressure, which would you cut, and what would you lose?

Facade (`ContactManagementSystem`) - it's the one pattern whose removal
costs the least design fidelity: callers would simply construct and hold
references to each service directly (`ContactService`, `GroupService`,
etc.) instead of through one wiring object. You'd lose a small amount of
convenience (one constructor call vs. several) but none of the actual
design substance - the Memento/Observer/Strategy/Builder choices and the
concurrency/indexing architecture are completely unaffected, because the
Facade was explicitly built to contain *zero* business logic (section 7).
This is a meaningfully different answer from "which pattern is least
important *conceptually*" (which would more likely be Builder, replaceable
by constructors at the cost of readability) - the question asked what's
cheapest to *remove under time pressure*, and Facade uniquely costs
nothing but convenience.
