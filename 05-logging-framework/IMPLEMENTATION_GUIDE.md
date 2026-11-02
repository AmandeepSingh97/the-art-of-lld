# Implementation Guide — Logging Framework (Log4j-style)

This guide is the "how do I actually get this onto the whiteboard/IDE in 45
minutes" companion to `README.md`'s design rationale and
`INTERVIEW_PLAYBOOK.md`'s interview-flow narrative. It is intentionally
procedural: ordered steps, concrete code shapes, and a running checklist of
what to say out loud while typing.

---

## 1. Suggested Class-Writing Order (and Why This Order)

Write classes in dependency order — each step should compile and be
independently sanity-checkable before moving to the next, so you never have
a long stretch of uncompilable code and can demo progress incrementally.

| Step | Class(es) | Why this position |
|---|---|---|
| 1 | `LogLevel` | Zero dependencies; everything else needs it. Also where you plant the ordinal-vs-CoR discussion early. |
| 2 | `LogEvent` | Depends only on `LogLevel`. Establishes the data shape everything flows through. |
| 3 | `Appender` (interface), `Layout` (interface) | Define the two seams before writing anything concrete against them — signals design-first thinking to the interviewer. |
| 4 | `SimpleLayout` | Smallest possible concrete `Layout` — unblocks a runnable end-to-end path immediately. |
| 5 | `ConsoleAppender` | Smallest possible concrete `Appender` — no locking, no I/O edge cases yet. |
| 6 | `Logger` (basic: name, level, single appender list, `log()`) | First point where you have something demoable: construct a `Logger`, attach a `ConsoleAppender`, call `.info(...)`, see output. **Demo checkpoint #1.** |
| 7 | `LoggerContext` (hierarchy resolution, `getEffectiveLevel()`, additivity) | Adds the "log4j-style" hierarchical requirement on top of a working flat logger. |
| 8 | `Filter` (interface) + `LevelRangeFilter`, `AbstractAppender` (template method pulling threshold+filter+layout together) | Refactor `ConsoleAppender` to extend `AbstractAppender` — shows you can evolve a design under new requirements without a rewrite. |
| 9 | `FileAppender` (with `ReentrantLock`) | First real concurrency-sensitive class. **Say out loud why a lock is needed here** (shared `BufferedWriter`, concurrent appenders from many threads). |
| 10 | `RollingFileAppender` (extends `FileAppender`, overrides one hook) | Demonstrates Template Method extension without touching the lock. |
| 11 | `BackpressurePolicy` (enum) + `AsyncAppender` | Saved for last deliberately — it's the most complex class and the one most likely to eat your remaining time if attempted too early. **Demo checkpoint #2.** |
| 12 | `LoggingConfiguration` + `Builder` | Only once the pieces it wires together already exist — wiring code is easiest to write last. |
| 13 | `LoggerFactory` | Thinnest possible facade; a 2-minute addition if time remains. |

If you only get through step 9 or 10 in a 45-minute session, that is a
**complete, defensible, demoable system** — hierarchical loggers + one
thread-safe appender is already a correct answer to "build a logging
framework," and you should say so explicitly rather than apologizing for
not reaching async logging.

---

## 2. Minimal Compilable Skeleton (Write This First, Verbatim-ish)

Before writing full implementations, sketch the skeleton below in ~3
minutes to lock the shape of the design and get early interviewer buy-in.
This mirrors the actual source layout under `src/main/java/lld/logging/`.

```java
package lld.logging.core;

public enum LogLevel {
    TRACE(10), DEBUG(20), INFO(30), WARN(40), ERROR(50), FATAL(60), OFF(Integer.MAX_VALUE);

    private final int severity;
    LogLevel(int severity) { this.severity = severity; }
    public int severity() { return severity; }
    public boolean isAsSevereAs(LogLevel other) { return this.severity >= other.severity; }
}
```

```java
package lld.logging.core;

import java.util.Objects;

public final class LogEvent {
    private final String loggerName;
    private final LogLevel level;
    private final String message;
    private final Throwable throwable; // nullable
    private final long timestampMillis;
    private final String threadName;

    public LogEvent(String loggerName, LogLevel level, String message, Throwable throwable) {
        this.loggerName = Objects.requireNonNull(loggerName);
        this.level = Objects.requireNonNull(level);
        this.message = message;
        this.throwable = throwable;
        this.timestampMillis = System.currentTimeMillis();
        this.threadName = Thread.currentThread().getName();
    }
    // getters omitted for brevity in this skeleton — write them, or use a record
    // if your interviewer is fine with records for a type this simple.
}
```

```java
package lld.logging.appender;

import lld.logging.core.LogEvent;

public interface Appender extends AutoCloseable {
    String getName();
    void append(LogEvent event);
    void flush();
    @Override void close();
}
```

```java
package lld.logging.layout;

import lld.logging.core.LogEvent;

@FunctionalInterface
public interface Layout {
    String format(LogEvent event);
}
```

```java
package lld.logging.core;

import lld.logging.appender.Appender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public final class Logger {
    private final String name;
    private final Logger parent; // null for root
    private final AtomicReference<LogLevel> level = new AtomicReference<>();
    private final List<Appender> appenders = new CopyOnWriteArrayList<>();
    private volatile boolean additive = true;

    Logger(String name, Logger parent) { this.name = name; this.parent = parent; }

    public LogLevel getEffectiveLevel() {
        for (Logger l = this; l != null; l = l.parent) {
            LogLevel lv = l.level.get();
            if (lv != null) return lv;
        }
        return LogLevel.INFO; // root default fallback
    }

    public void log(LogLevel lvl, String msg, Throwable t) {
        if (!lvl.isAsSevereAs(getEffectiveLevel())) return;
        LogEvent event = new LogEvent(name, lvl, msg, t);
        callAppenders(event);
    }

    private void callAppenders(LogEvent event) {
        for (Logger l = this; l != null; l = l.parent) {
            for (Appender a : l.appenders) a.append(event);
            if (!l.additive) break;
        }
    }

    public void info(String msg) { log(LogLevel.INFO, msg, null); }
    public void addAppender(Appender a) { appenders.add(a); }
    public void setLevel(LogLevel lv) { level.set(lv); }
    public void setAdditive(boolean additive) { this.additive = additive; }
}
```

This skeleton alone — once you add a trivial `LoggerContext.getLogger(name)`
that resolves/creates parent chains in a `ConcurrentHashMap`, and a
`ConsoleAppender` implementing `Appender` by wrapping `System.out` — is
already runnable end-to-end. **Get to this runnable point before adding any
polish** (javadoc, extra convenience methods, builder ergonomics).

---

## 3. Implementation Sequence Detail Per Class

### `LoggerContext.getLogger(String name)`
Say out loud: "I need to resolve the full parent chain, not just create one
flat entry, because `a.b.c`'s effective level depends on `a.b` and `a`
existing too." Implementation shape:
```java
public Logger getLogger(String name) {
    if (name.isEmpty()) return root;
    return registry.computeIfAbsent(name, n -> {
        int lastDot = n.lastIndexOf('.');
        Logger parent = (lastDot < 0) ? root : getLogger(n.substring(0, lastDot));
        return new Logger(n, parent);
    });
}
```
**Common mistake to narrate avoiding:** using `getOrDefault`/manual
check-then-act (`if (!map.containsKey(name)) map.put(...)`) instead of
`computeIfAbsent` — the former is a classic TOCTOU race under concurrent
callers; `computeIfAbsent` is atomic per key.

### `FileAppender` locking
Say out loud: "Multiple threads can call `append()` on the same appender
instance concurrently, and `BufferedWriter` is not thread-safe, so I need a
lock around the write." Implementation shape:
```java
private final ReentrantLock lock = new ReentrantLock();
protected final void doAppend(String formatted, LogEvent event) {
    lock.lock();
    try {
        writer.write(formatted);
        writer.newLine();
        if (immediateFlush) writer.flush();
        afterWriteLocked(event); // hook for RollingFileAppender
    } catch (IOException e) {
        throw new AppenderException("failed to write log line", e);
    } finally {
        lock.unlock();
    }
}
protected void afterWriteLocked(LogEvent event) { /* no-op by default */ }
```
**Common mistake to narrate avoiding:** using `synchronized` on `this`
instead of a dedicated lock field — fine functionally, but a named
`ReentrantLock` field documents intent better and gives you `tryLock`/
fairness options later if asked, which is worth one sentence of
justification if an interviewer asks "why not just `synchronized`?"

### `RollingFileAppender`
Say out loud: "I don't want to re-implement locking — I want to extend the
*decision point* only." Implementation shape:
```java
@Override
protected void afterWriteLocked(LogEvent event) {
    if (bytesWrittenSinceOpenLocked() >= maxBytes) {
        rollOverLocked();
    }
}
private void rollOverLocked() {
    closeWriterLocked();
    try {
        Path rolled = currentPath.resolveSibling(currentPath.getFileName() + "." + timestampSuffix());
        Files.move(currentPath, rolled);
    } catch (IOException e) {
        throw new AppenderException("rollover failed", e);
    } finally {
        reopenLocked(); // always leave a working writer, even if move failed
    }
}
```
**Edge case to call out proactively:** close the writer *before* renaming
(Windows cannot rename a file with an open handle), and always reopen in a
`finally` so a failed rename doesn't leave the appender permanently broken.

### `AsyncAppender`
This is the centerpiece of the concurrency discussion — budget the most
narration time here. Build it in this internal order:
1. Constructor: bounded `ArrayBlockingQueue<LogEvent>`, store `delegate`,
   `backpressurePolicy`, start the consumer thread immediately (don't lazily
   start it — simpler lifecycle, and the queue is cheap to hold before the
   first event arrives).
2. `append()`: switch on `backpressurePolicy` — `BLOCK` → `queue.put`
   (interruptible, catch `InterruptedException`, restore interrupt status,
   return); `DROP_NEW` → `queue.offer` and increment dropped-count on
   `false`; `DISCARD_OLDEST` → `poll()` once then `offer()`, incrementing
   dropped-count if the `poll()` actually removed something.
3. Consumer loop (`run()`): `while (running.get() || !queue.isEmpty())`,
   `take()` an event, check identity against the sentinel to break, else
   `try { delegate.append(event); } catch (RuntimeException e) { /* swallow, log to stderr, keep looping */ }`.
4. `close()`: `if (!running.compareAndSet(true, false)) return;` (idempotency
   guard first, before anything else), `queue.put(SENTINEL)` (blocking, not
   offer — guarantees FIFO-after-last-real-event), bounded `join(timeoutMillis)`,
   if still alive after join, `interrupt()` and count `queue.size()` as
   dropped, finally `delegate.close()`.

**Say out loud explicitly, don't wait to be asked:** "The sentinel is
inserted with a *blocking* `put`, not `offer`, specifically so it can never
jump ahead of real events that are still waiting in a full queue — that's
what gives me the 'drain everything queued before this point, then stop'
guarantee."

**Common mistake to narrate avoiding:** calling `consumerThread.stop()`
(deprecated, unsafe, can leave the delegate in a corrupted mid-write state)
or simply setting a flag without draining the queue (loses already-accepted
events on shutdown, which most interviewers will immediately flag as
unacceptable for a logging framework specifically, since "we lost the very
log messages that would explain why something went wrong during shutdown"
is a particularly embarrassing failure mode).

---

## 4. What to Omit Under Time Pressure (and What NOT to Omit)

**Safe to omit / simplify if running low on time**, in priority order
(cut from the top first):
1. `JsonLayout` and `PatternLayout` — one working `Layout` (`SimpleLayout`)
   is enough to prove the seam exists; say "I'd add a `PatternLayout` for
   configurable output format the same way I added `JsonLayout` — same
   interface, no changes needed elsewhere."
2. `RollingFileAppender`'s actual rollover (keep plain `FileAppender`,
   describe rollover verbally as "extend via one overridden hook, no lock
   changes needed").
3. `LoggingConfiguration`/`Builder` — wire a demo by hand
   (`ctx.getLogger("a.b").addAppender(...)`) and describe the builder
   verbally as "a one-shot validated assembly step before `configure()`
   swaps it into the live hierarchy."
4. `LevelRangeFilter`/`Filter` chain — mention it as an open extension point
   without implementing it.
5. Exhaustive backpressure policies — implement `BLOCK` only, describe
   `DROP_NEW`/`DISCARD_OLDEST` verbally as "same `switch`, different arm."

**Never omit, even under severe time pressure** (these are the parts that
actually distinguish a staff-level answer from a mid-level one):
- The hierarchical `getEffectiveLevel()` walk (it's *the* log4j-defining
  feature named in the problem statement).
- Some form of lock around shared mutable writer state in `FileAppender` —
  an unsynchronized shared `BufferedWriter` is a correctness bug, not a
  simplification, and will be caught immediately by any competent
  interviewer asking "what if two threads call this at once?"
- At minimum a *verbal* (if not coded) explanation of async logging's
  ordering guarantee and backpressure trade-off — this is explicitly called
  out in the problem's design-scope guidance as a required discussion
  point even if time runs out before full implementation.
- Graceful shutdown semantics for whatever you *did* implement — even a
  simple appender should have a `close()` that at least flushes, and you
  should say why that matters (data loss on process exit otherwise).

---

## 5. How to Narrate While Writing (Interviewer-Facing Script Fragments)

Use these as literal things to say at the moment you write the
corresponding code — the goal is to make your reasoning audible, not
silent, since a correct-but-silently-written solution scores worse than a
narrated one at the staff level.

- *Writing `LogLevel` as ordinal ints, not Chain of Responsibility:* "I'm
  choosing a plain ordinal comparison here instead of Chain of
  Responsibility — levels are a small, closed, totally-ordered set checked
  on literally every single log call, so I want the cheapest possible
  comparison on that hot path. I'd reach for CoR for something like the
  filter chain instead, where the set of filters is genuinely open-ended
  and user-extensible."

- *Writing `Logger`'s parent-walk in `getEffectiveLevel()`:* "This is the
  log4j-defining behavior — a logger with no explicit level inherits from
  its nearest ancestor that has one, which is why `a.b.c` can be silent by
  default but turned on surgically without touching every other logger."

- *Writing the `ReentrantLock` in `FileAppender`:* "Multiple application
  threads can log through the same appender concurrently — this lock is
  the one place in the whole design where I have actual lock-based mutual
  exclusion, everywhere else I'm leaning on lock-free concurrent
  collections instead."

- *Writing `AsyncAppender`'s queue:* "I'm bounding this queue deliberately
  — an unbounded queue under sustained overload just converts backpressure
  into an OutOfMemoryError later instead of handling it explicitly now."

- *Writing the sentinel-based shutdown:* "I want every event accepted
  before `close()` was called to be guaranteed written before the consumer
  thread exits, but I don't want new events accepted after `close()` starts
  — a sentinel value that goes through the same FIFO queue gives me exactly
  that ordering guarantee for free, instead of needing separate
  coordination."

- *If asked "what would you add with more time?":* name 2-3 from §4's
  omitted list specifically, not a vague "more tests" — e.g. "`PatternLayout`
  for configurable format strings, and a circuit-breaker decorator around a
  hypothetical remote appender, following the exact same Decorator shape as
  `AsyncAppender`."

---

## 6. Common Coding Mistakes to Actively Avoid

1. **Forgetting `volatile`/atomic wrappers on fields read by one thread and
   written by another** — e.g. a plain non-volatile `boolean additive` or
   `LogLevel level` field would compile fine and pass a quick manual test,
   but is a genuine visibility bug under the JMM; use `volatile` for simple
   flags and `AtomicReference`/`AtomicLong` where compound operations
   (increment-and-get, compare-and-set) are needed.
2. **Using `ArrayList`/`HashMap` where concurrent collections are needed**
   — `Logger.appenders` and `LoggerContext`'s registry are both accessed
   by multiple threads; reaching for `CopyOnWriteArrayList`/
   `ConcurrentHashMap` by default for anything shared across the hierarchy
   is the safe baseline, not an optimization to add later.
3. **Checking a log level, then separately formatting the message, as two
   detached steps a careless implementation might reorder** — always
   gate *before* any string formatting/concatenation happens; the entire
   performance argument for level-gating collapses if the expensive part
   (message construction) happens unconditionally before the cheap check.
4. **Calling `offer()` for the shutdown sentinel instead of `put()`** — this
   silently breaks the "drain everything queued before shutdown" guarantee
   if the queue happens to be full at that exact moment (the sentinel could
   be rejected and shutdown would appear to hang or behave inconsistently
   depending on how the rejection is handled).
5. **Renaming a file while its writer is still open** (`RollingFileAppender`)
   — works on Linux in many cases, silently fails or throws on Windows;
   always close before rename, reopen after, in a `finally`.
6. **Forgetting an idempotency guard on `close()`** — a double-`close()`
   call (easy to trigger via both an explicit shutdown path and a
   try-with-resources block, or a JVM shutdown hook plus explicit app
   code) should never throw or double-decrement/double-release resources;
   guard with a single atomic compare-and-set at the very top of `close()`.
7. **Swallowing `InterruptedException` silently** in the `BLOCK`
   backpressure path — always restore the interrupt status
   (`Thread.currentThread().interrupt()`) after catching it, even if you
   then choose to treat the event as dropped; silently eating an interrupt
   signal is a well-known Java anti-pattern that breaks cooperative
   cancellation for whoever catches it further up the call stack.

---

## 7. A Simplified 20–30 Minute Version

If the interview slot is short, or the interviewer explicitly asks for a
smaller scope, implement exactly this subset and say so explicitly up
front: *"Given the time, I'll build hierarchical loggers with one
thread-safe synchronous appender, and describe async logging and rollover
verbally rather than coding them — let me know if you'd rather I spend the
time differently."*

Scope for the 20–30 minute version:
1. `LogLevel` (enum, ordinal severities) — 2 min.
2. `LogEvent` (as a `record` to save typing time — acceptable simplification
   to state explicitly: *"I'm using a record here for brevity; in the full
   version I'd consider a plain final class if I needed to control
   serialization format independently of field layout."*) — 2 min.
3. `Appender`/`Layout` interfaces + `SimpleLayout` + `ConsoleAppender` — 5 min.
4. `Logger` + `LoggerContext` with hierarchical `getEffectiveLevel()` and
   additive fan-out — 8 min. **This is the non-negotiable centerpiece of
   the short version** — it's the one feature that makes this a "log4j-style"
   framework rather than a generic print-wrapper.
5. `FileAppender` with the `ReentrantLock` — 5 min, explained as "this is
   where I'd spend the next 15 minutes on async logging and rollover if we
   had more time; here's specifically what I'd build" followed by a 2-3
   sentence verbal description of the `ArrayBlockingQueue` + sentinel
   shutdown design (reuse the narration script in §5).
6. Leave 3-5 minutes at the end for questions — do not use all available
   time coding; a staff-level signal is knowing when to stop and discuss
   trade-offs instead of continuing to type.

Explicitly skip in this version: `RollingFileAppender`, `AsyncAppender`,
`LoggingConfiguration`/`Builder`, `PatternLayout`/`JsonLayout`, `Filter`
chain — all described verbally only, using the same language as
`INTERVIEW_PLAYBOOK.md`'s 30-minute walkthrough section for consistency.
