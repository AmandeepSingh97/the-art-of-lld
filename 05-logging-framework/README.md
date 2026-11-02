# Logging Framework (Log4j-style) — Staff-Level LLD

Package: `lld.logging` · Source: `src/main/java/lld/logging` · Tests: `tests/lld/logging`

---

## 1. Problem Understanding

Design and implement an in-process logging framework in the style of
log4j/logback/java.util.logging: application code obtains a `Logger` by
name, calls `logger.info("...")` style methods, and the framework decides
*whether* the message is significant enough to record and *where* it ends up
(console, file, rolling file, ...), in what *format*, and whether that
happens synchronously on the caller's thread or asynchronously in the
background.

**Real-world use case.** Every non-trivial backend service needs structured,
leveled, multi-destination logging: human-readable console output during
local development, rolling files on production hosts (bounded disk usage),
and increasingly JSON lines shipped to a log aggregator. The same event
often needs to go to more than one place, filtered differently per
destination (e.g. only WARN+ to a paging integration, everything to a local
rolling file). Throughput matters: a payment service might log thousands of
events/sec, and logging must never become the bottleneck or a source of
cascading failure for the service it instruments.

**What the interviewer is evaluating.**
- Can you correctly implement **hierarchical** configuration (log4j's
  defining feature) rather than a flat list of loggers?
- Do you understand **why** ordinal level comparison is usually preferred
  over a literal Chain-of-Responsibility object graph for this specific
  decision, and can you articulate the trade-off rather than just picking
  one by habit?
- Can you build a **correct, non-naive async pipeline**: bounded queue,
  single consumer, explicit backpressure policy, and a shutdown path that
  actually guarantees queued events are flushed rather than silently lost?
- Do you reach for the **Strategy** pattern for appenders/layouts without
  over-applying patterns (e.g. not wrapping a two-line comparison in a
  Chain-of-Responsibility just to "use a pattern")?
- Do you justify every lock/concurrent-collection choice instead of
  sprinkling `synchronized` defensively?

**In scope.**
- Named, hierarchical loggers with level inheritance and additivity.
- Ordinal log levels (TRACE..FATAL, OFF).
- Appenders: Console, File, RollingFile (size-based), Async (decorator over
  any other appender).
- Layouts: Simple, Pattern (log4j-style conversion pattern, subset), JSON.
- Filters: a generic predicate hook plus a level-range filter.
- Configuration via a Builder.
- Thread-safety of every shared-mutable-state component.
- Async logging: bounded queue, pluggable backpressure policy, graceful
  shutdown/flush.

**Out of scope** (see "Production Evolution", §17, for how each would be
added without re-architecting):
- Distributed/centralized log aggregation (ELK/Datadog shipping).
- MDC/diagnostic context propagation across thread pools.
- Time-based or count-based (as opposed to size-based) file rollover and
  retention/compression policies.
- Structured/parameterized logging with lazy argument evaluation
  (`logger.debug("x={}", x)`) — mentioned as a near-trivial extension, not
  implemented, to keep the core focused.
- Dynamic reconfiguration from an external file format (XML/YAML/properties)
  with hot-reload/file-watching.
- Remote/network appenders (syslog, Kafka sink).

---

## 2. Requirement Gathering

### Functional — essential
1. `Logger` instances are retrieved by name (dot-separated, class-name-like)
   via a factory; the same name always returns the same instance.
2. A logger has an **effective level**: its own explicit level, or (if
   unset) the nearest ancestor's level, walking up to a ROOT logger that
   always has a level.
3. `logger.log(level, message, throwable)` (and `trace/debug/info/warn/
   error/fatal` convenience methods) only produce output if `level` is at
   least as severe as the effective level.
4. One or more `Appender`s can be attached to any logger; by default
   ("additive") a log event also reaches every ancestor's appenders.
5. Appenders: Console, File (buffered, flushable), Rolling File (rolls over
   past a size threshold).
6. Layouts control the textual rendering of an event, decoupled from the
   appender.
7. An `AsyncAppender` wraps any other appender, decoupling the producer
   (application thread) from the I/O cost of the wrapped appender.
8. Configuration is assembled via a builder and applied atomically to a
   logger hierarchy.

### Functional — optional / nice-to-have (implemented, not required to pass the "minimum bar")
- JSON layout for log-shipper consumption.
- A general-purpose `Filter` hook beyond plain level thresholds (level-range
  filter implemented as the representative example).
- Per-appender level threshold independent of the logger's own level.
- Dropped-event counter exposed from `AsyncAppender` for observability.

### Non-functional
| Attribute | Requirement | How addressed |
|---|---|---|
| Performance | A disabled log call (below threshold) must be "nearly free" | Single ordinal int comparison before any allocation; no string formatting of disabled messages |
| Scalability | Must support many concurrent application threads logging to a few shared appenders without the appenders becoming the bottleneck | Async decorator decouples caller from I/O latency; bounded queue caps memory; file writer uses one lock scoped only to the I/O critical section |
| Availability | A failure in one appender (e.g. disk full) must not crash the application or stop other appenders from working | Per-appender exception isolation in `AsyncAppender.consumeLoop`; `AppenderException` unchecked so callers aren't forced into checked-exception ceremony for a cross-cutting concern |
| Consistency | Ordering guarantees must be explicit, not assumed | README spells out exactly what is and isn't guaranteed (per-thread FIFO yes, global cross-thread total order no) |
| Extensibility | New appender/layout/filter types addable without modifying existing code | Strategy interfaces (`Appender`, `Layout`, `Filter`) — OCP |
| Reliability | Events accepted before an intentional shutdown must not be lost | `AsyncAppender.close()` drains the queue via a poison-pill sentinel before returning |
| Thread-safety | Every type reachable from more than one thread must have a stated, justified synchronization strategy | §11 covers each one individually |

### Interview-necessary vs. production-extension split
**Interview-necessary** (must be correct and discussable in a 45–60 min
round): hierarchy + level inheritance + additivity, Strategy for appenders/
layouts, thread-safe FileAppender, AsyncAppender's queue/backpressure/
shutdown semantics, builder-based configuration.

**Production extension** (mention, don't necessarily implement): MDC
propagation, structured/parameterized logging, time-based rollover +
retention/compression, dynamic reconfig from file + hot reload, metrics
(dropped-event rate, queue depth) wired into a real metrics system, network
appenders with their own retry/circuit-breaking.

---

## 3. Clarifying Questions

*(Also serves as the record of assumptions made where no interviewer was
actually present — reasonable staff-level defaults, documented here per the
master spec.)*

1. **"Is this single-process/in-JVM, or does it need to ship logs to a
   remote aggregator?"** — Assumed **in-process only**. This changes
   everything: a network appender needs retry/backoff/circuit-breaking and
   cannot use a simple `BlockingQueue` + single thread without also handling
   partial-network-failure; that's a different (much larger) problem.
2. **"Do appenders need to support multiple loggers sharing one instance
   (e.g. one file appender attached to both ROOT and a specific subsystem
   logger)?"** — Assumed **yes** (this is normal log4j usage and directly
   motivates making `FileAppender` itself thread-safe, not just relying on
   `AsyncAppender`'s single-consumer property).
3. **"Should log level filtering be re-evaluated for every appender
   independently, or only once at the logger?"** — Assumed **both layers
   exist**: the logger does the cheap top-level gate (avoids any work at
   all for disabled levels), and each appender *additionally* has its own
   threshold (so e.g. a single DEBUG-enabled logger can still send only
   WARN+ to one appender and everything to another).
4. **"What should happen when the async queue is full — is data loss ever
   acceptable?"** — Assumed **configurable**, not a single fixed answer:
   `BackpressurePolicy` is deliberately pluggable (BLOCK / DROP_NEW /
   DISCARD_OLDEST) because the right answer is a product/ops decision
   (audit logs: never drop; high-frequency debug telemetry: drop is fine).
5. **"Does file rollover need date-based naming, size caps on total
   retained logs, or compression?"** — Assumed **size-based rollover only,
   no retention cap, no compression** for the interview-scope
   implementation; called out explicitly in §17 as a direct extension of
   `RollingFileAppender`'s existing rollover hook.
6. **"Is strict global ordering across all threads required?"** — Assumed
   **no** (and this is explicitly *not* a correctness bug): only per-thread
   program order needs to be preserved end-to-end; this matches how real
   async loggers (log4j AsyncAppender/AsyncLogger, Logback's `AsyncAppender`)
   behave, and demanding a stronger guarantee would require a sequence
   number + reordering buffer that no production logging framework actually
   pays for.
7. **"Should `Logger` support parameterized/lazy messages
   (`logger.debug("x={}", x)`) to avoid string-concatenation cost on
   disabled calls?"** — Assumed **out of scope but worth mentioning**: the
   level check already happens before any message construction in *this*
   design as long as callers pass a String built by the caller — the
   remaining risk is only if the caller does expensive concatenation before
   calling `log(...)`; a parameterized-message overload would close that
   gap and is flagged as a one-method extension, not implemented to keep
   the surface area focused on the concurrency/hierarchy core the problem
   is about.
8. **"Do we need to support changing configuration at runtime (hot
   reload)?"** — Assumed **programmatic reconfiguration is supported**
   (`LoggerContext.configure(...)` can be called again), but **file-based
   hot reload/watching is out of scope** (that's an I/O-polling concern
   orthogonal to the logging data model).

---

## 4. Domain Modelling

| Type | Kind | Responsibility |
|---|---|---|
| `LogLevel` | Enum (value object) | Total order of severities; the single comparison every log call is gated on |
| `LogEvent` | Immutable value object | Everything about one log call, captured once, safe to hand across threads |
| `Logger` | Entity (identity = name) | Owns its own level/additivity/appenders; walks its own parent chain to resolve effective level and to fan out events |
| `LoggerContext` | Domain service / repository | Owns the hierarchy: creates/looks-up `Logger`s by name, links parent/child, applies configuration, orchestrates shutdown |
| `LoggerFactory` | Thin static facade | Ergonic `getLogger(...)` over one default `LoggerContext`, without forcing that context on tests |
| `Appender` (+ impls) | Strategy | "where does a formatted event go" — Console / File / RollingFile / Async(decorator) |
| `Layout` (+ impls) | Strategy | "how does an event render as text" — Simple / Pattern / Json |
| `Filter` (+ `LevelRangeFilter`) | Strategy (predicate) | Open-ended accept/reject hook per appender, beyond the level threshold |
| `LoggingConfiguration` / `LoggerConfig` | Builder product / value object | Declarative description of a complete setup, applied atomically |
| `BackpressurePolicy` | Enum (strategy selector) | What `AsyncAppender` does when its queue is full |
| `AppenderException` | Unchecked exception | Signals an unrecoverable appender I/O failure |

**Composition / association / inheritance.**
- `LoggerContext` *composes* `Logger`s (owns their lifecycle; a `Logger`
  cannot meaningfully exist detached from the context that created and
  parent-linked it, enforced by the package-private constructor).
- `Logger` *has a parent* (association, not composition — the parent
  outlives/is-shared-by many children and is not owned exclusively by one
  child).
- `Logger` *has many* `Appender`s (association; appenders are frequently
  shared across multiple loggers, so a logger does not own an appender's
  lifecycle — `LoggerContext.shutdown()` closes each distinct appender
  exactly once specifically because ownership is shared, not per-logger).
- `AbstractAppender` <code>implements</code> `Appender` and is *extended by*
  `ConsoleAppender`/`FileAppender` (inheritance, template method for the
  common threshold/filter/layout gating).
- `RollingFileAppender` *extends* `FileAppender` (genuine is-a: a rolling
  file appender is a file appender with one extra post-write hook; chosen
  over composition here because the two share the entire write/lock/flush
  mechanism and only the "what happens after a successful write" step
  differs — a textbook template-method-via-inheritance case, not a forced
  inheritance).
- `AsyncAppender` *implements* `Appender` but *wraps* (composes) another
  `Appender` — Decorator, not inheritance, because it adds a cross-cutting
  behavior (async dispatch) orthogonal to what kind of appender is being
  wrapped; inheritance would require one subclass per wrapped type.
- `LoggingConfiguration` *is composed of* `LoggerConfig` value objects
  (ownership: the configuration owns the list of descriptors, not the
  appenders themselves, which are supplied by and remain owned by the
  caller).

**Ownership/lifecycle.** `LoggerContext` owns the `Logger` objects it
creates (never released individually — a logger hierarchy is expected to
live for the process lifetime, exactly like log4j's). `Appender` instances
are owned by whoever constructs them and are only *referenced* by loggers;
`LoggerContext.shutdown()` is the single place responsible for closing every
distinct appender reachable from the hierarchy, exactly once, regardless of
how many loggers reference it.

---

## 5. Domain Invariants

| Invariant | Enforced by |
|---|---|
| Every `Logger` other than ROOT has a non-null parent, and the parent chain always terminates at ROOT | `LoggerContext.getLogger` always resolves/creates the parent before creating the child; `Logger.parent` is `final` |
| ROOT always has a non-null explicit level | Set in `LoggerContext`'s constructor; never allowed to be cleared (no `clearLevel` API) |
| `getEffectiveLevel()` always terminates and returns a non-null value | Guaranteed by the previous invariant: the walk cannot pass ROOT without finding a level |
| A given logger name maps to exactly one `Logger` instance for the lifetime of a `LoggerContext` | `ConcurrentHashMap.computeIfAbsent` keyed by name — atomic w.r.t. concurrent first access |
| A `LogEvent`, once constructed, never changes (timestamp/thread/message/level/logger name are fixed at creation) | All fields `final`; no setters |
| An event enqueued into an `AsyncAppender` is processed, or counted as dropped — it is never silently discarded without being reflected in `droppedEventCount` for policies other than BLOCK, and BLOCK never drops at all | `enqueueDropNew`/`enqueueDiscardOldest` increment the counter on every path that does not insert the event; `enqueueBlocking` only fails (and counts) on thread interruption, never on a full queue |
| At most one thread is ever the consumer for a given `AsyncAppender`'s queue | Single `Thread` created and started once in the constructor; never replaced |
| A `FileAppender`'s write + flush + (if rolling) rollover-decision is atomic with respect to other writers | Guarded end-to-end by one `ReentrantLock` instance per appender (§11) |
| `close()` on any `Appender` is idempotent | `ConsoleAppender`/`FileAppender`: repeated `flush()+close()` on an already-closed writer is a no-op/safe in practice for this scope; `AsyncAppender.close()` explicitly guards with `running.compareAndSet(true, false)` so a second call returns immediately |

---

## 6. Architecture and Class Diagram

Component diagram: [`diagrams/component-diagram.md`](diagrams/component-diagram.md)
Class diagram: [`diagrams/class-diagram.md`](diagrams/class-diagram.md)
Sequence diagrams (sync call, async call, graceful shutdown, configuration
application): [`diagrams/sequence-diagrams.md`](diagrams/sequence-diagrams.md)

These diagrams are kept in lock-step with `src/main/java/lld/logging`; every
class/method named in them exists verbatim in the implementation (verified
by compiling the source — see §9 and the final verification summary).

---

## 7. Design Patterns

| Pattern | Why needed | Where applied | Alternative considered | Why rejected |
|---|---|---|---|---|
| **Strategy** | Decouple "what severity/format/destination" decisions from the `Logger`, which should only know *that* it needs to emit, not *how* | `Appender`, `Layout`, `Filter` interfaces | Hard-coding a `switch` over an `AppenderType` enum inside `Logger` | Violates OCP — adding a new appender type would require modifying `Logger`; also makes unit testing an appender in isolation harder |
| **Decorator** | Add asynchronous dispatch as a cross-cutting behavior over *any* existing appender without an explosion of subclasses | `AsyncAppender` wraps a `delegate` `Appender` | Separate `AsyncFileAppender`, `AsyncConsoleAppender`, ... subclasses | Combinatorial explosion (N appender types × 2 sync/async) for behavior that is identical regardless of what's wrapped |
| **Template Method** | Appenders share identical gating logic (threshold check, filter chain, layout formatting) and differ only in *where* the formatted string goes | `AbstractAppender.append()` is final and calls the abstract `doAppend(String, LogEvent)` | Duplicate the gating logic in every appender | Guaranteed drift/bugs (e.g. one appender forgetting the filter chain) — DRY violation with real correctness risk |
| **Builder** | Configuration naturally assembles incrementally (root settings, then N per-logger overrides) and should be applied as one atomic, validated unit | `LoggingConfiguration.builder()...build()` | Telescoping constructors, or public setters directly on `LoggerContext` | Setters on `LoggerContext` would let callers observe a half-configured hierarchy mid-setup, and conflate "runtime hierarchy management" with "configuration DSL" responsibilities |
| **Facade** | Application code wants `LoggerFactory.getLogger(...)` ergonomics without plumbing a `LoggerContext` through every constructor | `LoggerFactory` | Making `LoggerContext` itself fully static/singleton | A forced static singleton would make `LoggerContext` untestable in isolation (tests would share global mutable state); the facade isolates convenience from the one place (tests) that legitimately needs a fresh instance |
| **Producer/Consumer** | Decouple application-thread latency from appender I/O latency | `AsyncAppender`'s `BlockingQueue` + single background `Thread` | A `ThreadPoolExecutor` with one event per `Runnable` submission | A thread pool does not preserve per-producer ordering across N worker threads, re-adds thread-pool queueing/rejection semantics we'd just be reinventing less precisely, and over-provisions for what is fundamentally a single-destination sequential write |
| **(Rejected) Chain of Responsibility for level filtering** | Considered because many textbook logging write-ups model levels this way | — (not used) | A chain of per-level handler objects, each deciding "handle or forward" | See `LogLevel`'s javadoc and §10: levels are a small, closed, totally-ordered set checked on every single call (hot path) — CoR's object-per-check indirection buys nothing here. We *do* use the open-ended-predicate shape CoR is good for, but as `Filter`, a genuinely extensible, composable hook layered on top of the cheap ordinal check, rather than conflating the two concerns |

No pattern here is "forced in to show off": every one above is solving a
specific, named coupling problem that the alternative column demonstrates
would otherwise exist.

---

## 8. API and Method Design

### Core public surface

```java
// lld.logging.core
public enum LogLevel { TRACE, DEBUG, INFO, WARN, ERROR, FATAL, OFF;
    public int severity();
    public boolean isAsSevereAs(LogLevel threshold);
}

public final class LogEvent {
    public LogEvent(String loggerName, LogLevel level, String message, Throwable throwable);
    public String getLoggerName();
    public LogLevel getLevel();
    public String getMessage();
    public Throwable getThrowable();
    public long getTimestampMillis();
    public String getThreadName();
}

public final class Logger {
    public String getName();
    public Logger getParent();
    public void setLevel(LogLevel level);
    public LogLevel getLevel();            // own level only, may be null
    public LogLevel getEffectiveLevel();    // resolved via hierarchy, never null
    public void addAppender(Appender appender);
    public void removeAppender(Appender appender);
    public void setAdditive(boolean additive);
    public boolean isAdditive();
    public boolean isEnabledFor(LogLevel candidate);
    public void trace/debug/info/warn/error(String message);
    public void error(String message, Throwable t);
    public void fatal(String message, Throwable t);
    public void log(LogLevel level, String message, Throwable throwable);
}

public final class LoggerContext {
    public Logger getRootLogger();
    public Logger getLogger(String name);
    public Logger getLogger(Class<?> clazz);
    public void configure(LoggingConfiguration configuration);
    public void shutdown();
}

public final class LoggerFactory {
    public static LoggerContext getDefaultContext();
    public static Logger getLogger(String name);
    public static Logger getLogger(Class<?> clazz);
}
```

```java
// lld.logging.appender
public interface Appender extends AutoCloseable {
    String getName();
    void append(LogEvent event);
    void flush();
    void close();
}

public enum BackpressurePolicy { BLOCK, DROP_NEW, DISCARD_OLDEST }

public final class AsyncAppender implements Appender {
    public AsyncAppender(String name, Appender delegate, int queueCapacity, BackpressurePolicy policy);
    public long getDroppedEventCount();
    public int getQueueSize();
    public void close(long timeoutMillis);
}
```

```java
// lld.logging.config
public final class LoggingConfiguration {
    public static Builder builder();
    // Builder.rootLevel(LogLevel), .addRootAppender(Appender),
    // .forLogger(String).level(...).additive(...).addAppender(...).and(),
    // .build()
}
```

### Exceptions and validation responsibilities
- Constructors validate eagerly and throw `NullPointerException` /
  `IllegalArgumentException` for programmer errors (null names, non-positive
  queue capacity/maxBytes, inverted filter ranges) — fail fast at
  configuration time, not at the first log call.
- `AppenderException` (unchecked, `lld.logging.exception`) signals
  unrecoverable I/O failure from an appender (e.g. cannot open/write the log
  file). Kept unchecked deliberately — logging is a cross-cutting concern
  and forcing every caller up the stack to catch a checked
  `LoggingIOException` just to call `logger.info(...)` would be exactly the
  kind of checked-exception pollution Java logging frameworks avoid in
  practice.
- `Logger.log(...)` and friends never throw for an "event was below
  threshold" condition — that is normal, expected control flow, not an
  error, so it is a silent no-op, not an exception.

---

## 9. Core Implementation

Full compiling Java code (not pseudocode) lives in
`src/main/java/lld/logging/`. The pieces most worth reading in full for this
problem's unique difficulty (hierarchy resolution, async correctness,
thread-safe file writing):

- [`core/LogLevel.java`](src/main/java/lld/logging/core/LogLevel.java) —
  ordinal levels + the CoR-vs-ordinal design note.
- [`core/Logger.java`](src/main/java/lld/logging/core/Logger.java) —
  effective-level resolution, additivity fan-out, thread-safety rationale
  per field.
- [`core/LoggerContext.java`](src/main/java/lld/logging/core/LoggerContext.java) —
  hierarchy construction via `computeIfAbsent`, configuration application,
  shutdown.
- [`appender/AsyncAppender.java`](src/main/java/lld/logging/appender/AsyncAppender.java) —
  the producer/consumer pipeline, all three backpressure policies, poison-pill
  graceful shutdown.
- [`appender/FileAppender.java`](src/main/java/lld/logging/appender/FileAppender.java)
  and [`appender/RollingFileAppender.java`](src/main/java/lld/logging/appender/RollingFileAppender.java) —
  lock-guarded write+flush+rollover as one atomic unit.
- [`config/LoggingConfiguration.java`](src/main/java/lld/logging/config/LoggingConfiguration.java) —
  nested builder.
- [`demo/LoggingDemo.java`](src/main/java/lld/logging/demo/LoggingDemo.java) —
  runnable end-to-end example (8 threads x 500 messages through a
  non-additive async rolling-file logger, plus a synchronous root console
  logger, followed by graceful shutdown). Run with:
  `java -cp build/out lld.logging.demo.LoggingDemo` after compiling.

Representative excerpt — the effective-level resolution that is the crux of
hierarchical logging (full file at the path above):

```java
public LogLevel getEffectiveLevel() {
    for (Logger l = this; l != null; l = l.parent) {
        LogLevel lvl = l.level.get();
        if (lvl != null) {
            return lvl;
        }
    }
    return LogLevel.INFO; // unreachable in practice; ROOT always has a level
}
```

---

## 10. Data Structures and Algorithms

| Structure | Used for | Why | Alternative | Complexity |
|---|---|---|---|---|
| `ConcurrentHashMap<String, Logger>` | Name -> Logger registry in `LoggerContext` | Lock-striped concurrent map with atomic `computeIfAbsent` gives exactly-once creation under concurrent first access without a global lock | `synchronized HashMap` | `get`/`computeIfAbsent`: average O(1); synchronized map would serialize all lookups even on the (overwhelmingly common) cache-hit path |
| Parent-pointer linked chain (not a tree structure with child lists) | Logger hierarchy traversal for level resolution and additive fan-out | We only ever need to walk *upward* (child -> ancestors); a logger never needs to enumerate its descendants, so storing only `parent` avoids maintaining child lists that nothing reads | Full tree with `children` lists per node | O(depth) per lookup either way; parent-only representation uses O(1) extra memory per logger instead of O(children) |
| `CopyOnWriteArrayList<Appender>` (per logger), `CopyOnWriteArrayList<Filter>` (per appender) | Read-heavy, write-rare collections iterated on every log call | Lock-free, wait-free reads; writers (configuration time) pay the copy cost, which is acceptable since reconfiguration is rare | `synchronized List` / `Collections.synchronizedList` | Read (iterate): O(n) with **no lock**, vs. O(n) *with* a lock per read for synchronizedList; write: O(n) copy either way (acceptable, rare) |
| `ArrayBlockingQueue<LogEvent>` (bounded) | Producer/consumer handoff in `AsyncAppender` | Bounded (caps memory under backpressure), built-in blocking `put`/`take`, FIFO ordering, array-backed (predictable, low per-element overhead vs. a linked queue) | `LinkedBlockingQueue` (bounded) | Both are O(1) amortized `offer`/`poll`; array-backed avoids per-node allocation/GC churn under sustained high throughput, at the cost of a fixed capacity decided up front (acceptable — the whole point of bounding it is to pick a fixed memory ceiling) |
| `AtomicReference<LogLevel>` | A logger's own (possibly-unset) level | Lock-free, single-word CAS-backed read/write is sufficient since it's a single reference, not a compound update | `volatile LogLevel` field | Functionally near-identical for a plain get/set; `AtomicReference` chosen for the (unused here but available) `compareAndSet` if a future CAS-based reconfiguration API were added — `volatile` would also have been a perfectly defensible, slightly simpler choice, noted in §16 |
| `ReentrantLock` (per `FileAppender`) | Guarding write+flush+rollover as one critical section | Needs to be an explicit lock object (not `synchronized`) because the *same* lock instance must be inherited and reused unmodified by `RollingFileAppender`'s subclass logic (`afterWriteLocked`) while remaining encapsulated | `synchronized` methods on `this` | Equivalent mutual-exclusion semantics; explicit lock chosen for clearer ownership semantics across the base/subclass boundary and to leave room for `tryLock`-based backpressure in a future extension without an API change |
| Linear-scan pattern tokenizer (`PatternLayout`) parsed once into a `List<Token>` | Expanding a log4j-style conversion pattern | Pattern is parsed exactly once, at `Layout` construction time, O(pattern length); formatting each event afterwards is a single linear walk over pre-parsed tokens, O(tokens), with **zero** re-parsing per event (the hot path) | Regex-based `Matcher.find()` per event | Equivalent asymptotic cost per event, but regex engines carry materially higher constant-factor overhead for a decision made on every single log call; a hand-rolled single-pass scanner is simpler to reason about for a small, fixed token alphabet |

---

## 11. Concurrency and Thread Safety

This section is mandatory per the spec even where a component looks
"obviously fine" — every claim below is justified, not asserted.

### 11.1 Shared mutable state inventory

| State | Shared across | Protection |
|---|---|---|
| `Logger.level` | Any thread calling `setLevel` (config) vs. any thread calling `log(...)` (readers) | `AtomicReference` — single-word, always a consistent fully-formed `LogLevel` value, no tearing possible |
| `Logger.appenders` | Config thread (add/remove) vs. N application threads (iterate on every log call) | `CopyOnWriteArrayList` — iterators snapshot the array at `iterator()` call time; no `ConcurrentModificationException`, no lock on the read path |
| `Logger.additive` | Config thread vs. readers | `volatile boolean` — single word, no compound update needed |
| `LoggerContext.loggers` map | N application threads potentially creating the *same* previously-unseen logger name concurrently (e.g. first log call to a class from two threads at once) | `ConcurrentHashMap.computeIfAbsent`, which guarantees the mapping function runs **at most once per key** even under concurrent callers — no double-construction, no race to "win" publishing a `Logger` |
| `FileAppender.writer` (`BufferedWriter`) + `bytesWrittenSinceOpen` | Any thread whose event reaches this appender (directly, or exactly one background thread if wrapped in `AsyncAppender`) | `ReentrantLock` guarding the entire write+flush(+rollover-check) sequence as one critical section — see 11.2 |
| `AsyncAppender.queue` | N producer threads + 1 consumer thread | `ArrayBlockingQueue`'s own internal lock(s); this is the *only* synchronization needed for the handoff itself |
| `AsyncAppender.running` | Any producer thread (read) vs. the thread calling `close()` (write) | `AtomicBoolean` with `compareAndSet` in `close()` to make "has anyone already closed this" an atomic, race-free check (so `close()` is safely idempotent even if called concurrently from two threads, e.g. a shutdown hook racing a manual close) |
| `AsyncAppender.droppedEventCount` | N producer threads incrementing concurrently | `AtomicLong` |

### 11.2 Why `FileAppender` needs an explicit lock, not just "rely on `AsyncAppender`'s single consumer"

A `FileAppender` can be attached **directly** to multiple loggers (common:
one shared file for a whole subsystem) and therefore invoked concurrently
from multiple application threads even *without* ever being wrapped in an
`AsyncAppender`. `BufferedWriter` is explicitly documented as not
thread-safe, and even if it were safe per-call, **write-then-decide-to-roll**
(in `RollingFileAppender`) is a compound action: without a lock spanning both
steps, thread A could write a line that pushes the file size past the
threshold, thread B could write *its* line before A's roll-over runs
(interleaving an extra line into what A then renames away), or worse, two
threads could both observe "over threshold" and both attempt a roll-over.

The fix: `FileAppender.doAppend()` acquires one `ReentrantLock` per appender
instance, and holds it across write, optional immediate flush, **and** the
subclass's `afterWriteLocked()` roll-over check — the entire sequence is one
indivisible critical section (code: `FileAppender.java`,
`RollingFileAppender.afterWriteLocked`/`rollOverLocked`).

```java
@Override
protected final void doAppend(String formattedMessage, LogEvent event) {
    lock.lock();
    try {
        writeLocked(formattedMessage);
        afterWriteLocked(); // may trigger roll-over, still under the same lock
    } finally {
        lock.unlock();
    }
}
```

**Lock granularity:** one lock per appender instance (not a global lock
across all appenders, and not a finer-grained lock around just `write()`).
Coarser would unnecessarily serialize unrelated files; finer (e.g. a
separate lock for the rollover check) would reopen exactly the race
described above. This is the correctly-sized critical section.

### 11.3 `AsyncAppender`: ordering guarantees, precisely

- **Guaranteed:** for any single application thread, events it logs are
  written to the delegate in the same relative order it logged them
  (program order is preserved end-to-end, because (a) that thread's own
  `put`/`offer` calls happen sequentially, (b) the queue is FIFO, and (c)
  exactly one consumer thread ever calls `delegate.append`, so there is no
  possibility of two dequeued-in-order events being written out of order by
  racing consumer threads).
- **Not guaranteed, and should never be claimed:** a *global* total order
  across different threads' events that reflects true wall-clock
  happens-before. If threads A and B log "at the same time", whichever
  thread's `put`/`offer` call completes first determines queue position —
  this is inherent to any multi-producer queue and is explicitly what every
  mainstream async logging framework (log4j2 AsyncAppender/AsyncLogger,
  Logback AsyncAppender) also does *not* promise. `LogEvent.timestampMillis`
  (captured on the producer thread, at construction time — see
  `LogEvent`'s javadoc) can be used downstream to reconstruct an
  approximate chronological order if needed, but millisecond resolution and
  OS scheduling jitter mean it is an approximation, not a guarantee.
- This is proven, not just claimed: `AsyncAppenderTest
  .preservesOrderingOfEventsFromASingleProducerThread` logs 200
  sequentially-numbered messages from one thread and asserts the delegate
  received them in exact order.

### 11.4 Backpressure policies — correctness characteristics

- **BLOCK**: `queue.put(event)` — the producer thread blocks until space
  exists. No event is ever dropped while the appender is running (test:
  `blockPolicyNeverDropsEventsEvenWhenQueueIsSmallAndDelegateIsSlow`
  verifies zero drops with a deliberately slow delegate and a tiny queue).
  Trade-off: couples application throughput to logging throughput under
  sustained overload — the one scenario async logging exists to avoid — so
  this should be reserved for logs that must never be lost (e.g.
  compliance/audit trails), not general app logging.
- **DROP_NEW**: `queue.offer(event)` (non-blocking); on failure, increments
  `droppedEventCount` and returns immediately. Never blocks the caller.
  Trade-off: silent loss under sustained overload unless the operator is
  watching `getDroppedEventCount()`.
- **DISCARD_OLDEST**: attempts `offer`; on failure, `poll()`s the head and
  retries `offer` once. **Documented, accepted imprecision**: under
  concurrent producers, two threads can both observe "full" and each evict
  one head element for their own insert, which can over-evict by a small,
  bounded amount under heavy contention; a perfectly precise version would
  need a second lock wrapping the whole evict+offer sequence, which would
  reintroduce exactly the producer-side contention async logging exists to
  eliminate. This trade-off is explicit in the enum's javadoc and the
  implementation's comments, not silently swept under the rug.

### 11.5 Graceful shutdown — why it is actually correct, not just "looks like it flushes"

`AsyncAppender.close()`:
1. Atomically flips `running` false (`compareAndSet`), so any *new* `append`
   calls after this point are rejected (counted as dropped) rather than
   racing to insert into a queue that's being torn down.
2. Enqueues a **sentinel** `LogEvent` using a **blocking** `put` (not
   `offer`) — this is the detail that makes shutdown actually safe: even if
   the queue happens to be momentarily full of real events, the sentinel is
   *guaranteed* to eventually be inserted (once the consumer drains enough
   space), and because the queue is FIFO, every real event enqueued before
   `close()` was called is guaranteed to be ahead of the sentinel in queue
   order, hence guaranteed to be processed by the consumer loop before the
   consumer sees the sentinel and exits.
3. `consumerThread.join(timeoutMillis)` waits (bounded — shutdown must not
   hang forever if something is stuck).
4. If the join times out, the remaining queued (unprocessed) events are
   counted as dropped and the consumer thread is interrupted — explicit,
   measurable degradation rather than an indefinite hang.
5. Only then is `delegate.close()` called — guaranteeing the delegate (e.g.
   a `FileAppender`) is not closed while the consumer might still be
   mid-write.

Proven by `AsyncAppenderTest.closeDrainsAllAlreadyQueuedEventsBeforeReturning`
(100 events enqueued, `close()` called immediately after, all 100 assert
present in the delegate afterward) and
`appendAfterCloseIsDroppedRatherThanThrowing` (post-close calls degrade
gracefully rather than throwing from application code).

### 11.6 Deadlock analysis

No method in this codebase acquires more than one lock at a time (each
`FileAppender` has exactly one `ReentrantLock`, acquired and released within
a single call, never while holding another lock), and no lock is ever held
while calling into unrelated/unknown code that could re-enter and attempt to
re-acquire a different held lock. The only cross-thread coordination
primitive beyond a single-appender lock is `ArrayBlockingQueue`'s own
internal locking, which is a single, well-tested, lock-ordering-safe JDK
primitive. There is therefore no lock-ordering cycle possible in this
codebase.

### 11.7 Visibility

Every field read from more than one thread without additional
synchronization is `volatile`, `AtomicReference`/`AtomicBoolean`/
`AtomicLong`, `final`, or a `java.util.concurrent` collection — i.e. every
visibility requirement is met by a JMM-guaranteed mechanism, not by
incidental/lucky scheduling. `LogEvent`'s fields are all `final` and set in
the constructor; the handoff from producer to consumer goes through a
`BlockingQueue`, whose `put`/`take` (or `offer`/`poll`) pairs establish a
happens-before edge per the `java.util.concurrent` memory-visibility
guarantees — so a consumer thread is guaranteed to see a fully-constructed,
correctly-published `LogEvent`, never a partially-initialized one.

### 11.8 Optimistic vs. pessimistic locking

Every synchronization decision in this codebase is pessimistic (locks /
blocking queue operations), which is the right call here: the compound
operations involved (file write + rollover decision; queue insert +
possible eviction) are not naturally expressible as a single CAS, and
contention is expected to be the common case under load (many threads
logging to few appenders) rather than the rare case optimistic concurrency
is optimized for. The one place a CAS-style primitive *is* used
(`AtomicBoolean.compareAndSet` in `AsyncAppender.close()`) is a genuinely
single-word, no-retry-loop-needed check ("has shutdown already started"),
which is exactly what CAS is good for.

---

## 12. Persistence and Database Design

This problem is **intentionally pure in-memory plus filesystem** — there is
no database.

- The **logger hierarchy and configuration** are runtime, in-memory state
  (`LoggerContext`'s `ConcurrentHashMap`): a database would add latency and
  an external dependency to what is, by nature, a hot-path, per-call
  decision (level/appender lookup) that must complete in nanoseconds. No
  realistic logging framework persists its *configuration graph* to a
  database; configuration is either code (this implementation) or a config
  file (log4j.xml-style — explicitly out of scope here, see §3 Q8).
- The **log output itself** is written to the filesystem (`FileAppender`/
  `RollingFileAppender`) — a deliberate choice, not an oversight: logs are
  an append-only, sequential-write-heavy, rarely-random-read workload for
  which a flat file with OS-level buffered sequential I/O vastly
  outperforms any RDBMS/KV store for the write path, and is exactly what
  every production logging framework does. If queryable structured log
  storage is needed downstream (search by field, aggregate, alert), that is
  explicitly a **separate system's job** (a log shipper reading these files
  into Elasticsearch/ClickHouse/BigQuery — see §17), not something this
  framework should take on; conflating "write logs fast and reliably" with
  "make logs queryable" would turn a focused library into an
  over-engineered, slow, stateful service.
- If this were ever extended to also *index* events for in-process query (an
  explicit non-goal here), an embedded append-only structure (e.g. a
  write-ahead log + periodic compaction) would be the natural next step —
  not a general-purpose relational schema, since the access pattern (append,
  rarely random-read, time-ordered) does not benefit from the overhead of
  transactions/indexes a general RDBMS provides.

---

## 13. Failure Scenarios

| Scenario | Handling |
|---|---|
| Disk full / file write fails | `FileAppender.writeLocked` wraps `IOException` in an unchecked `AppenderException`. When this appender sits behind an `AsyncAppender`, the consumer loop's `catch (RuntimeException e)` swallows it so **one bad write does not kill the background thread** (which would silently stop all future logging for that appender) — a production system would additionally emit a metric here (see §17) |
| Appender's underlying resource already closed, but `append()` called again | `ConsoleAppender`/`FileAppender` do not explicitly guard against post-close writes (acceptable for interview scope: callers are expected not to log through a closed appender); `AsyncAppender` *does* explicitly guard (`running` flag) because it has a well-defined shutdown lifecycle with potential post-shutdown stragglers from in-flight application threads |
| Duplicate/concurrent "first access" to the same not-yet-created logger name | `ConcurrentHashMap.computeIfAbsent` guarantees exactly one `Logger` instance is ever published for a given name, even if two threads race to create it |
| Invalid configuration (negative queue capacity, non-positive `maxBytes`, inverted filter range, null required argument) | Fail fast with `IllegalArgumentException`/`NullPointerException` at construction time — never silently coerced to a default, so misconfiguration surfaces immediately in tests/startup rather than at 3am under load |
| Resource exhaustion: unbounded memory growth if appenders can't keep up | `AsyncAppender`'s queue is always bounded (constructor requires `queueCapacity > 0`); there is no unbounded-queue code path in this codebase |
| Mid-shutdown crash (process killed, not a clean `close()`) | Not recoverable by this framework (acceptable — a killed process loses in-flight unflushed async events by definition); mitigated by (a) `immediateFlush = true` default on `FileAppender` so *synchronously*-logged events are durable as soon as `append()` returns, and (b) encouraging `BLOCK` policy or small queue depth for anything that must survive a crash, explicitly documented in `BackpressurePolicy`'s javadoc |
| Timeout waiting for the async consumer thread to drain during shutdown | `AsyncAppender.close(timeoutMillis)` bounds the wait; on timeout, remaining queued events are deterministically counted as dropped (not silently lost without a trace) and the thread is interrupted rather than leaking a hung non-daemon thread forever |
| Rollover racing with an in-flight write from another thread | Cannot happen by construction: the entire "write, then check size, then roll if needed" sequence runs under one lock (§11.2) |
| Corrupted/partial log line from concurrent writers | Proven absent by `FileAppenderTest.concurrentWritersFromMultipleThreadsProduceNoCorruptedOrInterleavedLines`, which asserts every one of 3,200 concurrently-written lines matches the exact expected single-writer format |
| Cleanup after a failed roll-over (e.g. the rename throws mid-operation) | `rollOverLocked()`'s `finally` block always calls `reopenLocked()`, so the appender is left with a valid, open writer at the canonical path even if the `Files.move` call fails — the appender degrades to "kept writing to the same file past the size threshold" rather than ending up with no open writer at all |

---

## 14. Performance and Scalability

**Single-process bottlenecks and fixes.**
- *Bottleneck:* a hot logger called millions of times/sec at a disabled
  level. *Fix:* the ordinal check happens before any allocation or
  formatting — `Logger.log()`'s very first line is the threshold check.
- *Bottleneck:* many threads contending on one `FileAppender`'s lock.
  *Fix:* wrap it in `AsyncAppender` so application threads never contend on
  the file lock at all — only the single background consumer thread ever
  touches it, which also eliminates the lock contention entirely for the
  wrapped case (only one thread holds it, ever).
- *Bottleneck:* `PatternLayout` re-parsing its conversion pattern on every
  call. *Fix:* parsed once at construction into an immutable token list
  (§10); formatting is a linear walk, not a re-parse.

**Multi-threaded optimizations already applied** (this problem *is*
inherently multi-threaded — see §11): `CopyOnWriteArrayList` for read-heavy
appender/filter lists, `ConcurrentHashMap` for the logger registry,
single-consumer async pipeline to eliminate contention on the slow I/O path.

**What would NOT be done for this problem:** distributing the logging
framework itself across multiple JVMs/machines (e.g. a "logging cluster").
Logging is fundamentally a per-process concern — the correct way to scale
*log analysis/storage* beyond one host is to ship files to a separate
aggregation system (Kafka/Elasticsearch/ClickHouse), which is an
**architecturally separate system**, not a distributed-systems redesign of
this LLD problem. Over-distributing an in-process logging library would add
network calls to the hottest code path in the entire application (every
single log statement) — precisely backwards for a performance-critical
component.

---

## 15. Testing Strategy

Tests live in `tests/lld/logging`, package-mirrored, JUnit 5. Representative
coverage (not exhaustive — see the files for full detail):

| Category | Example test | File |
|---|---|---|
| Unit — ordinal comparison | `isAsSevereAsIsTrueWhenMessageLevelMeetsOrExceedsThreshold` | `core/LogLevelTest.java` |
| Unit — hierarchy/inheritance | `explicitChildLevelOverridesInheritedAncestorLevel`, `grandparentLevelIsInheritedAcrossMultipleHopsWhenInBetweenLoggersHaveNoExplicitLevel` | `core/LoggerHierarchyTest.java` |
| Unit — additivity | `additiveLoggerSendsEventToOwnAndAncestorAppenders`, `nonAdditiveLoggerDoesNotSendEventToAncestorAppenders` | `core/LoggerHierarchyTest.java` |
| Boundary — level filtering at the exact threshold | `messageAtOrAboveEffectiveLevelIsDispatched` vs. `messageBelowEffectiveLevelIsNotDispatchedToAnyAppender` | `core/LoggerHierarchyTest.java` |
| Unit — layout formatting | `customPatternExpandsOnlyRequestedTokensInOrder`, `literalPercentIsEscapedAndNotTreatedAsConversion` | `layout/PatternLayoutTest.java` |
| Negative/edge — malformed pattern input | `unknownConversionCharacterIsEchoedVerbatim` | `layout/PatternLayoutTest.java` |
| Unit — filter | `rejectsInvertedRangeAtConstruction` | `filter/LevelRangeFilterTest.java` |
| Concurrency — ordering guarantee | `preservesOrderingOfEventsFromASingleProducerThread` | `appender/AsyncAppenderTest.java` |
| Concurrency — backpressure (BLOCK never drops) | `blockPolicyNeverDropsEventsEvenWhenQueueIsSmallAndDelegateIsSlow` | `appender/AsyncAppenderTest.java` |
| Concurrency/failure-injection — backpressure (DROP_NEW actually drops under overflow) | `dropNewPolicyDropsRatherThanBlocksWhenQueueIsFull` | `appender/AsyncAppenderTest.java` |
| Failure/graceful degradation | `appendAfterCloseIsDroppedRatherThanThrowing` | `appender/AsyncAppenderTest.java` |
| Integration — graceful shutdown flush | `closeDrainsAllAlreadyQueuedEventsBeforeReturning` | `appender/AsyncAppenderTest.java` |
| Concurrency — thread-safe shared file writer | `concurrentWritersFromMultipleThreadsProduceNoCorruptedOrInterleavedLines` (16 threads x 200 msgs, asserts zero corruption/loss) | `appender/FileAppenderTest.java` |
| Integration — size-based rollover | `rollsOverToANewFileOnceSizeThresholdIsExceeded` | `appender/RollingFileAppenderTest.java` |
| Integration — configuration builder end-to-end | `perLoggerConfigurationSetsLevelAdditivityAndAppenders` | `config/LoggingConfigurationTest.java` |
| Integration — shutdown closes shared appenders exactly once | `shutdownClosesEveryDistinctAppenderExactlyOnceEvenWhenSharedAcrossLoggers` | `core/LoggerContextTest.java` |

Representative JUnit 5 snippet (full file:
[`tests/lld/logging/appender/AsyncAppenderTest.java`](tests/lld/logging/appender/AsyncAppenderTest.java)):

```java
@Test
void closeDrainsAllAlreadyQueuedEventsBeforeReturning() throws InterruptedException {
    RecordingAppender delegate = new RecordingAppender();
    AsyncAppender async = new AsyncAppender("async-drain", delegate, 8, BackpressurePolicy.BLOCK);

    for (int i = 0; i < 100; i++) {
        async.append(new LogEvent("x", LogLevel.INFO, "m" + i, null));
    }
    async.close(10_000);

    assertEquals(100, delegate.size(), "graceful close() must flush every event accepted before it was called");
    assertTrue(delegate.isClosed(), "close() must close the delegate appender too");
}
```

**Verified test run** (JUnit Platform Console Standalone, executed 3x
back-to-back to rule out flakiness in the concurrency tests): **34/34 tests
passed, 0 failed, 0 skipped**, identically across all three runs. Exact
command and output reproduced in the session summary / `IMPLEMENTATION_GUIDE.md`.

---

## 16. Trade-offs and Alternatives

| Decision | Chosen | Alternative | Pros/cons | When the alternative wins |
|---|---|---|---|---|
| Level filtering mechanism | Ordinal `int` comparison | Chain-of-Responsibility handler chain | Ordinal: O(1), zero allocation, trivial to reason about. CoR: more "textbook OOP", but adds indirection/allocation for a decision made on every call | CoR would win if the "levels" were actually an open, user-extensible set with per-level custom behavior beyond pass/reject (not the case for a closed, standard severity ladder) |
| Appender-to-queue handoff | `ArrayBlockingQueue` + 1 dedicated thread | `ThreadPoolExecutor.submit(Runnable)` per event | Dedicated single-consumer thread preserves per-producer FIFO and avoids thread-pool scheduling overhead per event | A thread pool wins if events legitimately needed to be processed in parallel/out-of-order (not true here — a single destination must be written sequentially) |
| Backpressure default exposed to callers | Pluggable enum, caller picks | One fixed policy (e.g. always BLOCK) | Pluggable: matches the real-world split between audit-grade (never drop) and high-volume telemetry (drop is fine) logging needs | A single fixed policy is simpler to reason about and would be acceptable if this framework only ever served one specific use case |
| Logger appender storage | `CopyOnWriteArrayList` | `synchronized ArrayList` | COW: lock-free reads (the hot path); write (copy) cost paid only at config time | `synchronized ArrayList` wins if appenders were added/removed *very* frequently at runtime relative to log call volume (not the case here — appenders are set up once at startup) |
| `Logger.level` storage | `AtomicReference<LogLevel>` | plain `volatile LogLevel` | Functionally near-identical for single-value get/set; `AtomicReference` leaves room for a future CAS-based reconfiguration API | `volatile` is marginally simpler and would have been equally defensible — noted explicitly so this isn't mistaken for "AtomicReference was necessary", it was a forward-looking, equally valid choice |
| File rollover trigger | Size-based only | Time-based (daily) or hybrid | Size-based is simpler and directly bounds per-file disk usage, which is the more common operational concern (avoiding one file growing unbounded) | Time-based wins when log retention policy is calendar-driven (e.g. "keep each day's logs separately for compliance") rather than disk-usage-driven |
| Rollover file naming | Timestamp-suffixed, keep-all | Fixed small set of numbered backups (`app.log.1`, `app.log.2`, ...) with oldest deleted | Timestamp-suffixed is simpler to implement correctly and never silently deletes data | Numbered-with-retention-cap wins in production where unbounded disk growth from rollovers must itself be bounded — explicitly called out as the natural next increment in §17, not implemented here to keep `RollingFileAppender` focused on the single concept being tested (the roll-over trigger + atomic write-then-roll correctness) |

---

## 17. Production Evolution

Kept explicitly separate from the core interview-scope solution — these are
the next steps for a real deployment, not features the interview
implementation is missing by mistake:

- **Metrics/observability:** emit counters/gauges for `droppedEventCount`,
  queue depth (`getQueueSize()`), appender write latency, and rollover
  events to a real metrics backend (e.g. Micrometer/Prometheus); alert on
  sustained non-zero drop rate.
- **Structured/parameterized logging:** add
  `logger.debug("order {} for {}", orderId, customerId)` overloads with lazy
  argument formatting, so argument-construction cost is also skipped when
  disabled (not just the already-skipped append/I/O cost).
- **MDC (Mapped Diagnostic Context):** thread-local (and reactive-context-
  aware) correlation IDs automatically attached to every `LogEvent`,
  requiring `LogEvent` to carry an immutable snapshot of the MDC map
  captured at creation time (same immutability principle already used for
  thread name).
- **Dynamic/file-based configuration with hot reload:** parse an XML/YAML
  config into a `LoggingConfiguration` and re-`configure()` a running
  `LoggerContext` on file-change-watcher events; requires deciding what
  happens to appenders removed by a reconfigure (close old ones once no
  logger references them — a reference-counting or generation-based GC
  scheme, not implemented here since static programmatic configuration is
  in scope).
- **Network/remote appenders** (syslog, Kafka, HTTP log shipper): each needs
  its own retry/backoff/circuit-breaker policy independent of
  `BackpressurePolicy` (which only governs the local queue, not a remote
  call's failure modes) — a materially different reliability problem than
  local file I/O.
- **Rollover retention policy + compression:** cap total retained rolled
  files (delete oldest beyond N, or beyond a total-size budget), and gzip
  rolled-over files — both are natural, additive extensions of
  `RollingFileAppender.rollOverLocked()`.
- **Distributed coordination:** not applicable to a single-process logging
  library by design (see §14) — the correct "distributed" story is
  downstream log aggregation, which is out of this component's
  responsibility boundary.
- **Fault tolerance for the background writer thread:** currently a crashed
  consumer thread (uncaught `Error`, not `RuntimeException`) would silently
  stop that appender; production hardening would add a supervisor that
  detects a dead consumer thread and either restarts it (replaying/
  discarding the in-flight queue) or fails loudly via a dedicated
  "logging-is-broken" alert channel, since a silently-dead logger is far
  more dangerous operationally than a loud one.
