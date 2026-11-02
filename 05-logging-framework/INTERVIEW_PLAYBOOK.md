# Interview Playbook — Logging Framework (Log4j-style)

## 1. How to open the discussion

Start by restating the problem in your own words and immediately naming the
thing that makes this problem interesting, so the interviewer knows you see
past "print statements with timestamps":

> "So we're building something like log4j: named loggers with level
> filtering, pluggable destinations, and — the part that usually
> distinguishes a strong answer here — making logging fast enough that it
> never becomes the bottleneck for whatever it's instrumenting, which means
> I'll want to talk about async dispatch and exactly what guarantees that
> gives up. Before I dive in, let me ask a few things that'll shape the
> design."

Then move straight into clarifying questions — don't start drawing boxes
yet.

## 2. First 5 clarifying questions to ask

1. "Is this purely in-process, or does it need to eventually ship logs
   somewhere remote (Kafka, a log aggregator)?" — determines whether you
   need retry/circuit-breaking machinery or can stay filesystem-only.
2. "Do we need a true parent/child logger hierarchy like log4j package
   namespacing, or is a flat per-class logger with one global level
   enough?" — this is the single biggest scope decision; hierarchy is the
   "senior" answer and is almost always what's wanted if they said
   "log4j-style".
3. "Should logging be synchronous by default, with async as an opt-in
   wrapper, or does everything need to be async from the start?" — shapes
   whether you present `Appender` as the base abstraction with
   `AsyncAppender` as a decorator (recommended) vs. baking async into every
   appender.
4. "If the async queue fills up, is it ever acceptable to drop log
   messages?" — surfaces the backpressure-policy discussion early, which is
   usually the single most differentiating conversation in this problem.
5. "Do we need to support multiple simultaneous output formats/destinations
   for the same event (console + file + JSON), or just one at a time?" —
   determines whether Strategy (one appender, swappable) is enough or you
   need a fan-out list of appenders (spoiler: you want the list either way,
   but confirming shows you're thinking about composition, not just a
   single `if/else`).

## 3. Requirements to establish

Say out loud, don't just silently assume:
- Functional: named hierarchical loggers, level-gated logging, multiple
  appender types, pluggable formatting, async option, builder-based config.
- Non-functional: a disabled log call must be near-free; concurrent writers
  to a shared appender must not corrupt output; an intentional shutdown
  must not lose already-accepted events; one broken appender must not take
  down the others.

## 4. How to identify core entities

Walk from the verbs in the problem statement to nouns:
- "a message has a severity" -> `LogLevel`
- "something happened, with context" -> `LogEvent` (and it should be
  immutable — say why: it crosses a thread boundary in the async case)
- "something named that you log through" -> `Logger`
- "loggers are organized by package-like name" -> hierarchy needs an owner
  -> `LoggerContext`
- "where it goes" -> `Appender` (Strategy, plural — a logger can have more
  than one)
- "how it looks" -> `Layout` (Strategy, separate from Appender on purpose —
  format and destination are independent axes)
- "configure once at startup" -> `LoggingConfiguration` (Builder)

## 5. How to explain class relationships

Say the ownership direction explicitly, because it's easy to get backwards:
"A `Logger` has a `parent` reference (association, not composition — many
children share one living parent). `LoggerContext` owns the whole
hierarchy's lifecycle — it's the thing that creates loggers and links
parents. Appenders are *not* owned by any single logger, because the same
file appender is commonly attached to several loggers at once — that's why
closing them is `LoggerContext`'s job, de-duplicated, not each logger's."

## 6. How to draw the architecture

Suggested order on the whiteboard:
1. Draw `Logger` in the middle with a `parent` arrow pointing to another
   `Logger` box labeled "...up to ROOT".
2. Draw a dashed box to the side: `Appender` interface, with 3-4 boxes under
   it (Console/File/RollingFile/Async) to show Strategy.
3. Draw `Layout` as a second, independent dashed interface box — explicitly
   say "this is orthogonal to Appender, any appender can use any layout".
4. Draw the async pipeline as its own small diagram: producer thread -> box
   labeled "bounded queue" -> single consumer thread -> delegate appender.
   This is usually the part worth the most whiteboard real estate.
5. Only then add `LoggerContext` as the thing owning everything and
   `LoggingConfiguration` as the builder feeding it.

## 7. Which interfaces to create first

1. `LogLevel` (enum) — zero dependencies, needed by everything.
2. `LogEvent` — depends only on `LogLevel`.
3. `Appender` — the interface, not an implementation yet.
4. `Layout` — the interface.
5. `Logger` — depends on `Appender`, `LogLevel`, `LogEvent`.

Naming these first, before any concrete class, signals you're designing the
contracts before the implementation — exactly what a staff-level interview
wants to see.

## 8. Which classes to implement first

1. `LogLevel` + `LogEvent` (trivial, unblocks everything).
2. `Logger` with a **hardcoded** single level, no hierarchy yet, and a
   single `ConsoleAppender` — get an end-to-end "it logs something" working
   fast.
3. Add hierarchy (`LoggerContext`, parent walk, `getEffectiveLevel`).
4. Add `FileAppender` + thread-safety (lock).
5. Add `AsyncAppender` last — it's the most complex piece and benefits from
   having a known-correct synchronous appender to delegate to while you
   build/test it.

## 9. Which design patterns to introduce and when

Introduce patterns **when the problem naturally demands them**, narrating
the "why", not before:
- Strategy for `Appender`/`Layout`: introduce as soon as you say "we need
  more than one kind of destination" — don't pre-declare it before that
  need is on the table.
- Decorator for `AsyncAppender`: introduce only once a synchronous
  `Appender` already exists and works — frame it explicitly as "I don't
  want N async subclasses, I want one wrapper that works over any
  appender."
- Builder for configuration: introduce once you're about to wire several
  loggers with different levels/appenders — "I don't want to expose mutable
  setters on the live hierarchy, I want to assemble a configuration object
  and apply it atomically."
- Explicitly **reject** Chain-of-Responsibility for level filtering if
  asked/if it comes up — this is a great moment to show judgment: "I
  thought about CoR here since that's how some textbooks model levels, but
  levels are a small closed set checked on every call, so an ordinal int
  comparison is strictly better — CoR earns its keep for open-ended
  predicate chains, which is exactly why I'd use it for `Filter` instead."

## 10. How to discuss concurrency

Structure it as three separate questions, answered in this order:
1. "What's shared, and by whom?" — walk the state inventory (logger's
   level/appenders, the file writer, the queue).
2. "What's my synchronization primitive for each, and why that one and not
   a stronger/weaker one?" — e.g. CopyOnWriteArrayList because reads vastly
   outnumber writes; explicit `ReentrantLock` because write+rollover must
   be one atomic unit, not because "locks are always used for files".
3. "What ordering guarantee does the async path actually give, precisely?"
   — say the true answer: per-producer-thread FIFO, not global order — and
   say *why* that's the right trade-off (any real async logger makes this
   exact trade), rather than overclaiming a guarantee you can't actually
   deliver.

## 11. How to discuss edge cases

Have these ready, don't wait to be asked:
- Two threads logging through the *same not-yet-created* logger name for
  the first time simultaneously — `computeIfAbsent` atomicity.
- The async queue is full and a producer is mid-call — walk through all
  three backpressure policies and when each is the right choice.
- A roll-over happens exactly while another thread is mid-write — explain
  why the lock spans both operations.
- Shutdown is requested while events are still in the queue — walk the
  poison-pill sentinel mechanism.
- An appender's underlying I/O throws — show you isolate that failure so it
  doesn't kill the whole logging pipeline or the app.

## 12. How to handle interviewer interruptions

- If asked "why not use a Chain of Responsibility for levels?" mid-flow:
  answer directly and briefly (ordinal is O(1) and allocation-free; CoR
  would add per-call indirection for a fixed, small set), then return
  exactly to where you were — don't let it turn into a tangent that eats
  your remaining time.
- If asked to add a requirement you hadn't planned for (e.g. "what if we
  need per-logger output format overrides?"): point at the existing
  `Layout` field on `AbstractAppender` and show it's already a one-line
  change (`appender.setLayout(...)`) — a sign your abstractions were right.
- If the interviewer pushes on "what if two threads both try to roll over
  at once" after you've already explained the lock: don't get defensive —
  re-derive it concretely with a 2-thread interleaving on the whiteboard to
  prove the lock actually prevents it, rather than re-asserting the same
  sentence louder.

## 13. How to prioritise if time is running out

Cut in this order (most to least expendable), saying out loud what you're
cutting and why:
1. JSON layout — "I'll skip this, Pattern/Simple layout prove the Strategy
   pattern equally well."
2. Filters beyond the basic level threshold — "I'll mention `Filter` as an
   extension point but not implement `LevelRangeFilter` live."
3. RollingFileAppender's rollover — "I'll implement plain `FileAppender`
   with the thread-safety story, and describe rollover as `afterWrite()`
   hook I'd add, rather than coding the rename logic live."
4. Never cut: the hierarchy/additivity logic and the `AsyncAppender`'s
   backpressure + graceful-shutdown correctness — these are the two things
   that actually distinguish a staff-level answer from a mid-level one.

## 14. Three full walkthroughs with realistic timelines

### 30-minute walkthrough
- 0-5 min: clarify scope (hierarchy yes, async yes/no, destinations).
- 5-10 min: entities + interfaces (`LogLevel`, `LogEvent`, `Logger`,
  `Appender`, `Layout`) on the whiteboard, narrated.
- 10-20 min: implement `Logger` with hierarchy + effective level +
  additivity + a `ConsoleAppender`, running end to end mentally/on paper.
- 20-27 min: describe (don't fully code) `AsyncAppender`'s queue +
  single-consumer + backpressure-policy design, and the thread-safety
  argument for `FileAppender`.
- 27-30 min: trade-offs summary + what you'd add with more time.

### 45-minute walkthrough
- 0-5 min: clarify scope.
- 5-12 min: entities/interfaces + the CoR-vs-ordinal decision stated
  explicitly (shows judgment early).
- 12-28 min: implement `Logger`+`LoggerContext` hierarchy fully, plus
  `ConsoleAppender` and `FileAppender` with the lock-based thread-safety
  argument coded, not just described.
- 28-40 min: implement `AsyncAppender` for real — queue, consumer loop, one
  backpressure policy coded (say you'd add the other two the same way),
  graceful shutdown with the poison pill.
- 40-45 min: discuss ordering guarantees precisely, then trade-offs.

### 60-minute walkthrough
- 0-5 min: clarify scope.
- 5-15 min: entities/interfaces + patterns discussion (Strategy, Decorator,
  Builder, and the explicit CoR rejection).
- 15-30 min: full hierarchy + additivity + ConsoleAppender/FileAppender with
  thread-safety.
- 30-45 min: full `AsyncAppender` with all three backpressure policies and
  graceful shutdown, narrated against a concrete 2-producer-thread
  interleaving to prove the ordering claim.
- 45-52 min: `RollingFileAppender` and the "write+rollover must be atomic"
  race, with the fix.
- 52-60 min: testing strategy (name the concurrency tests you'd write and
  what each proves), failure scenarios, and production-evolution bullet
  points (metrics, MDC, remote appenders) — show you know where the
  interview-scope line is and why.

## 15. What I would say to the interviewer

- "I'm going to make the `Logger` itself dumb about *where* things go —
  that's the appender's job — so adding a new destination later never
  touches `Logger`."
- "The reason I immutable-ized `LogEvent` isn't style — it's that this
  object has to safely cross from an application thread to a background
  writer thread, and immutability means I don't need any lock just to read
  it on the other side."
- "I thought about Chain of Responsibility for levels because that's the
  textbook pattern people reach for, but it's genuinely the wrong tool
  here — this is a fixed, tiny, totally-ordered set checked on literally
  every log call, so I want the cheapest possible comparison, not an object
  graph."
- "The queue being bounded is deliberate — unbounded means a slow disk can
  turn into unbounded memory growth, which is worse than dropping or
  blocking."
- "I want to be upfront about what ordering guarantee this actually gives:
  per-thread order is preserved, but if two different threads log at
  basically the same instant, I'm not promising which one lands first in
  the file — and no mainstream async logger promises more than that
  either."
- "Shutdown is the part people get wrong most often — it's easy to write
  something that *looks* like it flushes but actually just kills the
  thread. I'm using a sentinel value pushed onto the same FIFO queue so I
  get a correctness guarantee 'for free' from the queue's own ordering,
  instead of trying to coordinate a separate signal."
- "If I had another day on this, the next thing I'd build is structured/
  parameterized logging so argument construction itself is skipped when
  disabled — right now only the I/O is skipped, the caller still pays for
  string concatenation if they wrote it inline."
