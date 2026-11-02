# Interview Playbook — In-Memory File Management System

## 1. How to open the discussion

Start by restating the problem in your own words and naming the two things you know will matter
most: "I'll build an in-memory tree of files and directories supporting the classic shell verbs —
mkdir, cd, pwd, ls, cp, mv, rm. I expect the two hardest parts to be getting path resolution and
name-collision/cycle handling right for cp/mv, and having a real opinion on thread-safety if this
tree is shared across sessions. Before I design, let me ask a few things that change the shape of
the solution."

## 2. First 5 clarifying questions to ask

1. "Is this purely in-memory, or do I need to think about persistence?" — changes whether I need
   a storage layer at all.
2. "Is the tree accessed by a single thread, or do multiple sessions/threads share one tree
   concurrently?" — the single biggest driver of design complexity.
3. "Should cp/mv match POSIX behavior exactly (e.g. what happens when the destination is an
   existing directory), or can I define reasonable rules and state them?"
4. "Are symbolic or hard links in scope?" — if yes, the tree becomes a graph and cycle detection
   gets much harder.
5. "Do I need permissions/multi-user ownership, or is that out of scope for this round?"

## 3. Requirements to establish

Say explicitly, out loud: "I'll implement mkdir (with -p), cd/pwd, ls, touch, cat, write
(with append), cp (with -r for directories), mv, and rm (with -r and -f). Non-functionally, I
need to handle: name collisions on cp/mv, cycle prevention on mv (can't move a directory into its
own descendant), and — if multi-threaded is in scope — a locking strategy I can justify, not just
assert."

## 4. How to identify core entities

Walk from the real-world nouns to classes: "A file system is fundamentally a tree: nodes that are
either a File (leaf, has content) or a Directory (composite, has children). That's a textbook
Composite pattern — I'll have an abstract FileSystemEntry base with File and Directory
subclasses. Then I need something that knows 'where am I right now' per caller — that's a
Session holding a current-working-directory pointer. And I need a facade that owns locking and
path resolution — call it FileSystem."

## 5. How to explain class relationships

"FileSystemEntry is the Composite base — File is the leaf, Directory is the composite holding a
map of name → child. A Directory composes its children: if you delete a directory, its children
go with it, so that's ownership, not just a reference. FileSystem composes exactly one root
Directory and associates with zero or more Sessions — a Session doesn't own the FileSystem, it
just points into it. I keep Directory's children map package-private so the only class allowed to
mutate the tree is FileSystem — that's what lets me make the locking policy a property of the
code, not just a comment."

## 6. How to draw the architecture

Draw three boxes left to right: **Command layer** (Shell + one class per verb) → **Core domain**
(FileSystem facade, Session, PathResolver, Directory/File) → nothing below (no DB, no disk).
Say: "The command layer depends on FileSystem; FileSystem never knows about Shell or commands.
That one-way dependency means I can reuse the core tree from a test harness, a GUI, or a gRPC
service without dragging in shell-parsing code."

## 7. Which interfaces to create first

1. `FileSystemEntry` (abstract) — name, parent, id, `isDirectory()`, `size()`.
2. `Command` — `execute(FileSystem, Session, String[])`. Get this early even before all commands
   exist, because it shapes how verbs will plug in.

Say: "I'm defining the shape of the tree node and the shape of a verb before I write any
concrete logic — that way File, Directory, and every Command slot into an agreed contract."

## 8. Which classes to implement first

Recommended order, and why:
1. `FileSystemEntry`, `File`, `Directory` — the data model, nothing works without it.
2. `PathResolver` — pure, stateless, easiest to get right and test in isolation before wiring it
   into anything stateful.
3. `FileSystem` with `mkdir`/`cd`/`pwd`/`ls`/`touch`/`cat`/`write` — the non-recursive surface.
4. `FileSystem.rm` (non-recursive case first, then `-r`).
5. `FileSystem.cp` then `FileSystem.mv` — these are the hardest (collision handling, cycle
   prevention), so do them after everything else works and is tested.
6. `Command`/`CommandResult`/`Shell` + one Command class per verb — thin wrappers once the core
   is solid.

## 9. Which design patterns to introduce and when

- Mention **Composite** the moment you draw `FileSystemEntry`/`File`/`Directory` — it should feel
  inevitable, not bolted on.
- Mention **Command** when you get to "how do I add a new verb without a giant switch" — usually
  right after the core `FileSystem` methods exist, when the interviewer asks "how would a REPL
  drive this?"
- Mention **Facade** explicitly when discussing locking: "FileSystem is a facade — that's not just
  a style choice, it's what lets me guarantee every mutation goes through the lock."
- Explicitly say what you're *not* adding: "I considered Visitor for tree-walking but with only
  one real recursive operation shape (copy/size), it's premature — I'd add it the moment a second
  distinct traversal (like `find`) shows up."

## 10. How to discuss concurrency

Lead with the question itself: "What's the actual race we need to handle? The classic one is:
thread A does `rm -r` on a directory while thread B does `cp -r` into it." Then present your
chosen strategy, briefly naming the alternatives first so it's clear you considered them:
"I considered full per-node locking — too easy to get the lock ordering wrong for mv/cp across
two arbitrary subtrees. I considered a copy-on-write persistent tree — more machinery than this
needs, though it's the right answer at larger scale. I chose one coarse read/write lock for tree
*structure*, plus an independent lock per file for *content* — so a `write` to one file never
blocks a `mkdir` elsewhere, but any two structural operations (mkdir/mv/cp/rm) are fully
serialized with each other." Then explain *why that's correct* for the race: "Because both `rm`
and `cp` hold that one lock for their entire recursive duration, not per-node, there's no
interleaving where B sees a half-deleted directory — whichever one calls lock() first runs
completely before the other starts."

## 11. How to discuss edge cases

Have these ready, unprompted if time allows:
- Moving a directory into its own descendant (cycle) — must be rejected.
- Moving a directory into itself, or onto the identical path — no-op is confusing; reject it
  explicitly with a clear error.
- `cp`/`mv` destination already exists — require an explicit overwrite flag, never silently
  clobber.
- `rm` on a non-empty directory without `-r` — reject.
- Removing or moving the current working directory (or an ancestor of it) out from under the
  active session — decide and state a policy (this design refuses it for the *calling* session).
- Path normalization: `..` past root, trailing slashes, `.` segments, empty segments from double
  slashes.

## 12. How to handle interviewer interruptions

If interrupted mid-explanation: stop, answer the specific question directly in one or two
sentences, then say "does that answer it, or should I go deeper before continuing?" before
resuming. If the interruption reveals a requirement you hadn't planned for (e.g. "what about
symlinks?"), don't silently redesign — say out loud: "that changes things — with symlinks the
tree becomes a graph, so my simple ancestor-walk cycle check needs a visited-set instead. I'll
note that and come back to it if we have time, unless you want me to pivot now."

## 13. How to prioritise if time is running out

Drop in this order (least to most costly to cut):
1. `-p`/`-f`/`-a` flag niceties — implement the bare command first, mention the flag exists.
2. Full concurrency write-up — get the one-sentence answer out ("one coarse structural lock, one
   lock per file") even if you don't have time to code the ConcurrencyTest-style proof.
3. `mv`/`cp` collision *overwrite* path — get the "reject on collision" path working first; the
   overwrite flag is secondary.
4. Never cut: path resolution correctness, cycle prevention on `mv`, and the Composite structure
   itself — these are the load-bearing parts of the problem.

## 14. Three full walkthroughs with realistic timelines

### 30-minute walkthrough
- 0–3 min: clarifying questions + scope.
- 3–8 min: entities + Composite diagram (FileSystemEntry/File/Directory), path resolution
  approach stated verbally.
- 8–18 min: code `FileSystemEntry`, `File`, `Directory`, `PathResolver`, and `FileSystem.mkdir` +
  `cd`/`pwd`/`ls` live.
- 18–25 min: code `rm` (non-recursive + `-r`) and a simplified `cp`/`mv` (files only, directories
  verbally described).
- 25–30 min: state the concurrency strategy verbally (two-tier lock), state cycle-prevention
  logic verbally if not coded, mention tests you'd write.

### 45-minute walkthrough
- 0–5 min: clarifying questions + scope + non-functional requirements.
- 5–12 min: full class diagram (entities + Command pattern mention), domain invariants stated.
- 12–30 min: code `FileSystemEntry`/`File`/`Directory`/`PathResolver`/`FileSystem` for
  mkdir/cd/pwd/ls/touch/cat/write/rm, fully working.
- 30–40 min: code `cp -r` and `mv` including the cycle check and collision handling.
- 40–45 min: concurrency discussion with the specific rm-vs-cp race, and 2–3 unit tests named out
  loud (collision, cycle, concurrent race).

### 60-minute walkthrough
- 0–5 min: clarifying questions + scope.
- 5–15 min: full domain model, invariants, class + sequence diagram on the whiteboard.
- 15–40 min: full implementation of mkdir/cd/pwd/ls/touch/cat/write/rm/cp/mv, including flags.
- 40–50 min: concurrency — design and justify the two-tier lock, walk through the rm-vs-cp race
  on the whiteboard step by step, discuss why no deadlock is possible.
- 50–57 min: write 2–3 real tests (collision rejection, cycle rejection, a concurrent test using
  a `CountDownLatch` to force the race).
- 57–60 min: trade-offs recap (why not per-node locking, why not COW) and production-evolution
  bullet points (persistence, metrics).

## 15. What I would say to the interviewer

- "Let me start with the tree shape, because everything else — path resolution, locking, cp vs
  mv semantics — depends on getting that right first."
- "I'm making File and Directory both extend a common FileSystemEntry so I can treat them
  uniformly wherever that's useful — like computing size, or walking up to the root for pwd."
- "I'm keeping Directory's internal map package-private on purpose — I want exactly one class,
  FileSystem, to be allowed to mutate the tree, so the locking rule isn't just something I have
  to remember to follow everywhere."
- "The two operations that are actually hard here are cp and mv — specifically, what do you do
  when the destination already exists, and how do you stop someone from moving a folder into its
  own subfolder. Let me handle those explicitly rather than hand-wave them."
- "For concurrency, my honest answer is: I'm not going to do per-node fine-grained locking here,
  because getting the lock ordering right for two-directory operations like mv is genuinely easy
  to get wrong, and I'd rather have a simple design I can prove correct than a fast one I can't."
- "If thread A is deleting a directory recursively while thread B is copying into it, the thing
  that makes this safe is that both operations hold the same lock for their whole duration — so
  one of them completes entirely before the other even starts, and whichever one runs second
  gets a clean error, not a corrupted tree."
- "I'd add persistence, permissions, and finer-grained locking as the next layer, but I don't want
  to build those now — nothing in the requirements asked for them, and adding them speculatively
  is exactly the kind of overengineering that gets flagged in review."
