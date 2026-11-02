# Interview Playbook — Contact Management System

A practical, timed guide for actually running this problem in an interview
room: what to say, in what order, and when to cut scope.

---

## 1. Opening Framing (first 2 minutes)

Say this, almost verbatim:

> "I want to design a contact management system - think the Contacts app on
> a phone, or the contact module in a CRM. Before I start modeling, let me
> confirm scope and a couple of assumptions so we're solving the same
> problem: I'll treat this as single-tenant, in-memory, but built so the
> storage and indexing layers are swappable later. I'll call out explicitly
> anything I'm choosing not to build - like persistence or distributed
> coordination - rather than silently skipping them."

This signals: you scope deliberately, you don't treat "didn't mention it"
as "doesn't matter."

---

## 2. Requirements Gathering Script

Ask these, in this order (each maps to README section 3):
1. "Is this single-user or shared across a team?"
2. "Does a contact need at least one phone or email, or can it be name-only?"
3. "Should duplicate detection auto-merge, or only suggest?"
4. "Is undo a full-snapshot revert, or per-field?"
5. "Does name search need to match any token (first OR last name), or just
   the start of the full name?"
6. "Should phone/email matching normalize formatting, or require exact
   strings?"
7. "What happens to memberships when a group is deleted - cascade or
   block?"

If the interviewer says "use your judgment" (common), state your
assumption out loud and move on - don't stall here. ("I'll assume at least
one contact point is required, since an unreachable contact is low value -
I'll note this as an assumption.")

---

## 3. Entity Identification Walkthrough

Narrate the nouns-to-entities mapping explicitly:
- "Contact" -> the aggregate root; has identity, has a lifecycle (create/
  edit/delete/merge/undo).
- "Phone number", "email", "address" -> value objects; no identity of
  their own, fully owned by their `Contact`.
- "Group" -> a second, independent entity (its own id, its own lifecycle),
  referenced by `Contact`, not owned by it - explain the many-to-many
  reasoning out loud (a `Group` can have many contacts; a `Contact` can be
  in many groups).

Then draw (verbally or on the whiteboard) the composition vs. association
distinction - interviewers specifically listen for whether you can
articulate *why* phones are "part of" a contact while groups are not.

---

## 4. Requirements -> Class Responsibility Mapping

| Requirement | Class(es) responsible |
|---|---|
| CRUD a contact | `ContactService` + `Contact.Builder` |
| Multiple phones/emails/addresses | `Contact`'s field lists + `PhoneNumber`/`EmailAddress`/`PostalAddress` |
| Groups/labels | `Group`, `GroupService`, `GroupRepository` |
| Prefix name search | `NameIndex` (`TrieNameIndex`/`SortedArrayNameIndex`), `SearchService` |
| Exact phone/email lookup | `HashLookupIndex`, `IndexManager`, `SearchService` |
| Duplicate detection/merge | `DuplicateDetectionService`, `DuplicateMatchStrategy`, `ContactMerger` |
| Import/export | `ContactCodec`/`VCardContactCodec`, `ImportExportService` |
| Favorites | `Contact.favorite` field + `IndexManager`'s favorite set + `SearchService` |
| Undo | `HistoryService` (Memento), `ContactService.undo` |
| Thread safety | Immutable `Contact` + `InMemoryContactRepository.compareAndSet` + `ContactService`'s retry loop |

---

## 5. Which Classes to Draw First on the Whiteboard

Order matters for pacing - draw in this sequence, narrating as you go:
1. `Contact` (with its value-object fields) - the center of gravity.
2. `Group` - second entity, show the association arrow.
3. `ContactRepository` interface + `InMemoryContactRepository` - "storage
   is an abstraction from day one."
4. `ContactService` - the orchestrator; this is where most interview time
   should go.
5. `NameIndex` interface + both implementations - this is your DSA
   centerpiece; draw it early enough that you have time to discuss it.
6. `IndexManager` as the Observer tying repository to indexes.
7. Everything else (`GroupService`, `SearchService`,
   `DuplicateDetectionService`, `ImportExportService`,
   `ContactManagementSystem`) - sketch quickly, these are mechanical once
   the core is right.

---

## 6. Incremental Build Order (what to code first, second, third)

1. `PhoneNumber`/`EmailAddress`/`PostalAddress` value objects + enums -
   trivial, builds confidence, unblocks everything else.
2. `Contact` + `Contact.Builder` - the one class everything depends on;
   get its invariants (name non-blank, phone dedup) right early.
3. `ContactRepository` interface + `InMemoryContactRepository`, including
   `compareAndSet` - this is the riskiest piece; do it before you're
   rushed.
4. `ContactService.create`/`get`/`update`/`delete` (no merge/undo yet) -
   prove the CRUD loop works end-to-end.
5. `NameIndex` + one implementation (Trie) + `HashLookupIndex` +
   `IndexManager` - unlocks search.
6. `SearchService` - thin, fast to write once indexes exist.
7. `HistoryService` + `ContactService.undo` - now that update works, undo
   is a small addition.
8. `GroupRepository`/`GroupService` - mechanical, follows the same pattern
   as contacts.
9. `ContactMerger` + `ContactService.mergeContacts`/`undoMerge`.
10. `DuplicateMatchStrategy` + `DuplicateDetectionService`.
11. `ContactCodec`/`VCardContactCodec` + `ImportExportService`.
12. `ContactManagementSystem` facade - wire everything, write the demo.

---

## 7. Time-boxed Walkthrough: 30-minute version

- **0-5 min**: Requirements gathering (section 2 above) + explicit
  in-scope/out-of-scope statement.
- **5-12 min**: Draw `Contact`, value objects, `Group`; state invariants
  out loud (name non-blank, >=1 contact point, phone dedup).
- **12-20 min**: Draw `ContactRepository`/`InMemoryContactRepository` and
  explain the CAS approach at a high level (don't code it yet - narrate
  "I'd implement compareAndSet via computeIfPresent with reference-identity
  comparison, here's why `.equals` would be wrong" without writing every
  line).
- **20-27 min**: Draw `NameIndex` + Trie vs. sorted-array trade-off
  (the DSA centerpiece - this is almost certainly worth explicit credit).
- **27-30 min**: Summarize what's deferred (merge, undo, import/export,
  concurrency deep-dive) and why, given time - "If we had more time I'd
  implement duplicate merge via indexed candidate generation to avoid
  O(n^2), and undo via Memento, both of which fall out naturally from
  Contact's immutability."

## Time-boxed Walkthrough: 45-minute version

- **0-5 min**: Requirements + scope.
- **5-10 min**: Entities/value objects + invariants.
- **10-20 min**: Repository + CAS, actually write `compareAndSet` and the
  `ContactService.update` retry loop in code - this is the correctness
  centerpiece and deserves real code, not just narration.
- **20-30 min**: `NameIndex` - implement enough of `TrieNameIndex` to show
  the insert/search logic, then verbally compare to `SortedArrayNameIndex`
  and `HashLookupIndex` with the complexity table from README section 10.
- **30-37 min**: `DuplicateDetectionService` - explain indexed blocking
  (candidate generation) vs. naive O(n^2), sketch `ContactMerger`'s union
  logic.
- **37-43 min**: `HistoryService`/undo as Memento - emphasize that
  `Contact`'s immutability makes the pattern nearly free.
- **43-45 min**: Name what's cut (import/export, groups) and why, plus one
  production-evolution sentence (persistence/distribution).

## Time-boxed Walkthrough: 60-minute version

- **0-5 min**: Requirements + scope + explicit interview-necessary vs.
  production split (README's table).
- **5-12 min**: Entities, value objects, invariants, composition vs.
  association (`Contact`-owns-phones vs. `Contact`-references-`Group`).
- **12-25 min**: Repository + CAS fully coded; narrate the
  `.equals()`-vs-reference-identity subtlety explicitly and why a naive
  `Map#replace` would be a real, subtle bug - this is a strong
  differentiator at staff level.
- **25-38 min**: Full `NameIndex` trade-off: implement `TrieNameIndex`'s
  `add`/`searchByPrefix`, then `SortedArrayNameIndex`'s binary-search
  variant, side by side, with the complexity table. Explicitly ask "do you
  want me to implement both, or is a verbal comparison enough?" to manage
  time collaboratively.
- **38-46 min**: `DuplicateDetectionService` + `ContactMerger` +
  `mergeContacts`'s `structuralLock` - explain why a coarse lock is
  acceptable here and name its one honest gap (concurrent single-field
  update racing a merge).
- **46-52 min**: `HistoryService` (Memento) + `GroupService` cascading
  delete.
- **52-58 min**: `ImportExportService`/`VCardContactCodec` - sketch the
  encode/decode shape, skip full parsing detail.
- **58-60 min**: Production evolution - persistence, distributed locking,
  durable search index - one sentence each, explicitly framed as "next,
  not now."

---

## 8. Common Mistakes to Avoid

- Jumping straight to code before stating assumptions - loses "requirements
  gathering" credit even if the code ends up correct.
- Treating phone/email search and name-prefix search as the same problem -
  they need genuinely different data structures (hash vs. trie/sorted
  array); conflating them is the single most common DSA mistake on this
  problem.
- Reaching for `synchronized` everywhere "to be safe" instead of explaining
  *which* shared state needs protection and why - sprays locks without
  reasoning about what's actually unsafe.
- Building a full RFC-6350 vCard parser instead of a pragmatic subset -
  burns 15 minutes on string-parsing instead of architecture.
- Forgetting to ask whether auto-merge or suggest-only is wanted before
  building merge, since it changes how aggressive the match strategy needs
  to be.
- Making `Contact` mutable "because it's simpler," then discovering midway
  that undo/concurrency need something like Memento/CAS anyway - costs a
  redesign mid-interview. Decide immutability up front and reap all three
  benefits (thread-safety, Memento, CAS) at once.

---

## 9. What to Say When Running Low on Time

> "I'm going to stop implementing [X] in code and just describe the
> approach, so I can make sure we get to [the DSA trade-off / concurrency
> story], which I think is the higher-signal part of this problem. The
> short version for [X] is: [one or two sentences]."

Concretely: cut `VCardContactCodec`'s exact line-parsing details first,
then `GroupService`'s cascading-delete code (describe it instead), then -
only as an absolute last resort - the second `NameIndex` implementation
(keep at least the chosen one and the comparison table, since that's the
problem's named DSA requirement).

---

## 10. What NOT to Build (and how to say so out loud)

Say explicitly, don't just silently omit:
> "I'm not going to implement a certified RFC 6350 vCard parser, real
> telephony-grade phone validation, persistent storage, or distributed
> locking - I'll describe each of those as a named extension at the end,
> but building any of them now would eat time better spent on the core
> data structures and concurrency model this problem is actually testing."

---

## 11. Trade-off Narration Templates

Use this three-part template every time you make a consequential choice:
**"I chose X over Y because Z, and the cost of X is W."**

Examples ready to reuse verbatim:
- "I chose a Trie over a sorted array for name-prefix search because
  queries are O(prefix length) instead of O(log n + k), and this is a
  read-heavy workload - the cost is higher memory per entry from eager
  aggregation."
- "I chose optimistic concurrency (CAS retry) over per-contact locks
  because most edits don't actually collide, so paying lock-acquisition
  cost on every edit would be wasted work - the cost is a bounded retry
  loop that can still fail loudly under sustained contention."
- "I chose one coarse lock for merge instead of per-pair fine-grained
  locking because it's trivially deadlock-free to reason about - the cost
  is reduced merge throughput under heavy concurrent merging, which I
  don't expect to be the common case."

---

## 12. Handling Interviewer Pushback

- **"Why not just use a database from the start?"** - "For an in-process
  design like this, a database adds transaction/schema overhead without
  changing any of the core data-structure or concurrency decisions, which
  are the parts I think this problem is testing. I've kept storage behind
  an interface specifically so this is a non-invasive swap later - happy
  to sketch the schema if useful." (Then reference README section 12.)
- **"Isn't the Trie wasteful on memory?"** - "Yes, this specific
  implementation eagerly aggregates ids at every node for O(prefix-length)
  query time - I'd switch to a leaf-only variant or the sorted-array
  implementation if memory pressure, not query latency, were the binding
  constraint; both are already behind the same interface."
- **"What if two people edit the same contact at the exact same time?"**
  - "Neither edit is lost - the second one's CAS fails against the first
  one's new state and retries, replaying its own change on top. I can
  walk through the exact mechanics if useful." (Then be ready to actually
  do so - this is the single most likely follow-up.)
- **"Why immutable entities - doesn't that waste memory on every edit?"**
  - "It costs one extra `Contact` allocation per edit, but in exchange we
  get thread-safety, a free Memento for undo, and safe CAS, all from one
  decision - for an address book's edit frequency, that trade is clearly
  worth it."

---

## 13. Code-first vs Diagram-first Decision Guide

- If the interviewer explicitly asks for "a class diagram" or says
  "no need to write code" - stay in diagram/narration mode the whole time,
  go deeper on trade-offs verbally (you'll have more time for sections 10
  and 11's content).
- If the interviewer says "show me the code" early - go straight to
  `Contact`/`Contact.Builder`, then `InMemoryContactRepository.compareAndSet`,
  since those two pieces carry the most design weight per line of code.
- Default (no explicit preference stated): diagram first for ~10 minutes
  to establish shared vocabulary, then code the 2-3 highest-signal pieces
  (CAS, one `NameIndex` implementation), narrating the rest.

---

## 14. "What I Would Say to the Interviewer" — Closing Summary

> "To summarize: I modeled `Contact` as an immutable aggregate root owning
> typed phone/email/address value objects, with `Group` as a separate,
> independently-addressable entity referenced by id. That immutability
> decision does triple duty - it gives me thread-safety for free, makes
> undo a Memento pattern with no extra snapshot type needed, and enables
> lock-free optimistic-concurrency updates via compare-and-set. For search,
> I used a Trie for name-prefix queries - O(prefix length) instead of
> O(log n) - because this is a read-heavy workload, while keeping a
> sorted-array alternative behind the same interface for a lower-memory
> deployment. Phone/email lookup is a straightforward O(1) hash index -
> there's no interesting trade-off there, unlike prefix search. Duplicate
> detection uses those same indexes as a blocking step to avoid an O(n^2)
> scan, feeding a small candidate set into a pluggable matching strategy.
> I deliberately did not build persistence, distributed coordination, or a
> fully standards-compliant vCard parser - I can go deeper into any of
> those, or into the concurrency correctness story, if that's more useful
> to you right now."

---

## 15. Post-Interview Self-Check

Ask yourself, after the session:
- Did I state at least 3 explicit assumptions before coding?
- Did I compare at least two data structures with real complexity numbers
  for prefix search, not just one?
- Did I name at least one thing I deliberately did NOT build, and why?
- Did I explain one concurrency correctness subtlety with a concrete
  failure scenario (not just "I'd use a lock")?
- Did I leave time for the interviewer's follow-up questions rather than
  using the entire slot narrating?
