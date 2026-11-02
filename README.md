# The Art of LLD — Staff-Level Low-Level Design Interview Preparation

This repository is a self-contained bank of **17 Staff-engineer-level Low-Level
Design (LLD) problems**, each solved end-to-end in idiomatic Java 17+: full
requirement gathering, domain modelling, design-pattern justification,
concurrency analysis, a working implementation, and a JUnit 5 test suite that
actually exercises the concurrency claims (not just happy-path CRUD).

Every directory compiles and its tests pass today. This is not a collection of
pseudocode sketches — it's runnable, reviewed code you can read, modify, and
re-derive from memory under interview time pressure.

Free to use for interview preparation — see [LICENSE](LICENSE). Contributions,
corrections, and additional problems are welcome via pull request.

## Getting started

```bash
git clone https://github.com/AmandeepSingh97/the-art-of-lld.git
cd the-art-of-lld
./scripts/setup-test-deps.sh   # one-time: downloads the JUnit 5 console launcher into tools/
```

New here? Start with **[PREPARATION_ROADMAP.md](PREPARATION_ROADMAP.md)** for a
recommended, dependency-ordered path through all 17 problems rather than
working through them in numeric order.

## What's in each problem directory

Every `NN-problem-name/` directory follows the same shape:

| File/Dir | Purpose |
|---|---|
| `README.md` | The full LLD write-up: problem understanding, requirements, clarifying questions, domain model, invariants, architecture, **design patterns** (with rejected alternatives), API design, core implementation walkthrough, data structures & complexity, **concurrency & thread safety**, trade-offs, and production-evolution notes. |
| `INTERVIEW_PLAYBOOK.md` | How to drive this problem live in a 45–60 minute interview: what to say first, what to whiteboard, how to pace. |
| `STAFF_LEVEL_DEEP_DIVE.md` | The harder follow-up questions a Staff-bar interviewer asks, answered at the depth expected of that level. |
| `FOLLOW_UP_QUESTIONS.md` | A rapid-fire Q&A bank for self-testing after you've read the README. |
| `IMPLEMENTATION_GUIDE.md` | A from-scratch, timed build order — what to write first, second, third — for practicing the "build it live" version of the interview. |
| `diagrams/` | Mermaid class/sequence/state diagrams. |
| `src/main/java/...` | The reference implementation. |
| `tests/.../*.java` | JUnit 5 tests, including dedicated concurrency tests launching real threads. |

## The 17 problems

| # | Problem | One-line description |
|---|---|---|
| 01 | [Parking Lot](01-parking-lot/README.md) | Multi-floor, multi-size garage with lock-free CAS spot allocation and pluggable pricing/allocation strategies. |
| 02 | [TTL Cache](02-ttl-cache/README.md) | Generic thread-safe in-process cache with per-entry TTL, pluggable LRU/LFU eviction, a background reaper, and eviction listeners. |
| 03 | [Vending Machine](03-vending-machine/README.md) | Single physical machine with a State-pattern lifecycle, cash/card payment strategies, and change-making. |
| 04 | [BookMyShow](04-bookmyshow/README.md) | Movie-ticket booking platform whose centerpiece is preventing two users from double-booking the same seat via lock-free CAS. |
| 05 | [Logging Framework](05-logging-framework/README.md) | Log4j-style framework: Strategy appenders/layouts/filters, Decorator async dispatch, Template Method gating logic. |
| 06 | [LFU Cache](06-lfu-cache/README.md) | O(1) get/put LFU eviction via frequency-bucketed doubly linked lists, with Decorator-composed thread-safety and stats. |
| 07 | [File Management System](07-file-management-system/README.md) | In-memory Unix-like filesystem (`cp`/`mv`/`mkdir`/`ls`/`rm`) using Composite, with a two-tier read-write-lock + per-file-lock concurrency model. |
| 08 | [Food Delivery System](08-food-delivery-system/README.md) | Zomato/Swiggy-style order lifecycle with State-pattern orders, Observer order tracking, Strategy partner assignment, and Decorator-composed pricing/discounts. |
| 09 | [Splitwise](09-splitwise/README.md) | Expense-sharing system with pluggable equal/exact/percent split strategies and a ledger protected by a per-group read-write lock. |
| 10 | [Meeting Scheduler](10-meeting-scheduler/README.md) | Calendar/room booking with recurrence expansion and deadlock-free multi-resource locking via globally sorted lock acquisition. |
| 11 | [Snake and Ladder](11-snake-and-ladder/README.md) | Classic board game with pluggable dice/win-condition strategies and an explicit single-writer concurrency contract. |
| 12 | [Contact Management System](12-contact-management-system/README.md) | Address book with Trie-based prefix search, Memento-based undo, and optimistic (CAS-style) concurrent updates on immutable contacts. |
| 13 | [Stock Management System](13-stock-management-system/README.md) | Warehouse inventory that implements and compares **three** concurrency-control strategies (optimistic/pessimistic/CAS) behind one interface. |
| 14 | [Digital Wallet](14-digital-wallet/README.md) | Paytm/Venmo-style wallet with double-entry ledger transfers, idempotent APIs, and consistent per-account lock ordering to avoid deadlock. |
| 15 | [Elevator System](15-elevator-system/README.md) | Multi-car building controller with a State-pattern car lifecycle and a SCAN/LOOK nearest-car scheduling strategy. |
| 16 | [Slack / Workplace Chat](16-slack-chat/README.md) | Real-time chat with Observer/pub-sub delivery, fan-out-on-write vs. fan-out-on-read strategies, and per-key token-bucket rate limiting. |
| 17 | [Google Drive](17-google-drive/README.md) | Multi-user cloud storage with Composite file trees, Memento version history, content-hash dedup, and CAS-based per-user quota enforcement. |

## How to use this repo

Don't just read top to bottom — the problems build on each other's concepts
(concurrency primitives, state machines, strategy composition). See
**[PREPARATION_ROADMAP.md](PREPARATION_ROADMAP.md)** for a tiered, dependency-aware
reading order, a revision cadence, and mock-interview checkpoints.

For cross-cutting pattern comparisons (e.g., "when is Strategy right vs. a plain
`if/else`, across all 7 problems that use it"), see
**[DESIGN_COMPARISON.md](DESIGN_COMPARISON.md)**.

For a consolidated Java concurrency/collections refresher with code snippets and
cross-references into this repo's actual usage, see
**[JAVA_CONCEPTS_REVISION.md](JAVA_CONCEPTS_REVISION.md)**.

## Compiling and running any problem

Every problem is plain Java with no build tool (no Maven/Gradle) — just
`javac`/`java` against the `src/` and `tests/` directory trees, matching the
exact commands documented in each problem's own README (§9/§12). From the
repository root:

### 1. Run the demo
```bash
cd 01-parking-lot   # or any NN-problem-name directory
javac -d out $(find src -name '*.java')
java -cp out lld.parkinglot.app.ParkingLotDemo   # replace with that problem's Demo/Main class — see table below
```

### 2. Run the JUnit 5 test suite
Tests use JUnit 5 via the **JUnit Platform Console Standalone** jar. It is not
committed to the repo (keeps the repo binary-free); fetch it once and reuse it
across all 17 problems:
```bash
# one-time, from the repo root
./scripts/setup-test-deps.sh
# downloads to tools/junit-platform-console-standalone.jar

cd 01-parking-lot   # or any NN-problem-name directory
javac -d out -cp ../tools/junit-platform-console-standalone.jar $(find src tests -name '*.java')
java -jar ../tools/junit-platform-console-standalone.jar --class-path out --scan-class-path
```
Each problem's own README documents the exact test count and categories it
expects (unit, integration, concurrency).

### Demo entry points, by problem
| Problem | Demo/Main class (fully qualified) |
|---|---|
| 01-parking-lot | `lld.parkinglot.app.ParkingLotDemo` |
| 02-ttl-cache | `lld.ttlcache.demo.CacheDemo` |
| 03-vending-machine | `lld.vendingmachine.demo.VendingMachineDemo` |
| 04-bookmyshow | `lld.bookmyshow.demo.BookMyShowDemo` |
| 05-logging-framework | `lld.logging.demo.LoggingDemo` |
| 06-lfu-cache | `lld.lfucache.LFUCacheDemo` |
| 07-file-management-system | `lld.filesystem.Demo` |
| 08-food-delivery-system | `lld.fooddelivery.demo.FoodDeliveryDemo` |
| 09-splitwise | `lld.splitwise.app.SplitwiseDemo` |
| 10-meeting-scheduler | `lld.meetingscheduler.demo.SchedulerDemo` |
| 11-snake-and-ladder | `lld.snakeandladder.Demo` |
| 12-contact-management-system | `lld.contactmanagement.demo.Main` |
| 13-stock-management-system | `lld.stockmanagement.Main` |
| 14-digital-wallet | `lld.digitalwallet.demo.WalletDemo` |
| 15-elevator-system | `lld.elevatorsystem.demo.ElevatorSystemDemo` |
| 16-slack-chat | `lld.slackchat.Demo` |
| 17-google-drive | `lld.clouddrive.demo.DriveDemo` |

## Staff-level engineering standards (recap)

Every problem in this repo was held to the same bar, visible in every README's
structure:

- **Every design pattern is justified, not assumed.** Each README's "Design
  Patterns" section states why the pattern was needed, where it's applied, what
  alternative was considered, and why that alternative was rejected — and
  explicitly lists patterns deliberately **not** used, to preempt "why didn't
  you use X?" follow-ups.
- **Concurrency is analyzed, not asserted.** Every shared mutable field is
  enumerated with its race condition and the specific mechanism (lock,
  CAS, immutability, `volatile`, a concurrent collection) that closes it —
  and every concurrency claim is backed by a test that launches real threads.
- **Trade-offs are explicit.** Where a deliberately simpler choice was made
  (e.g., `long` cents instead of `BigDecimal`, a coarse lock instead of
  fine-grained locking), the README says so and says why, rather than hiding
  the simplification.
- **Nothing is over-engineered.** Patterns and abstractions earn their place
  by removing real duplication/coupling; every README's "deliberately not
  used" list is as important as its "used" list.
- **Tests prove the claims.** Unit tests cover domain invariants; dedicated
  concurrency tests use real multithreading (`ExecutorService`,
  `CountDownLatch`-synchronized starts) to reproduce races, not just assert
  single-threaded behavior.
