# Sequence Diagrams

## 1. Synchronous log call (ConsoleAppender)

```mermaid
sequenceDiagram
    participant App as Application thread
    participant L as Logger("com.app.Foo")
    participant Ctx as LoggerContext (hierarchy)
    participant A as ConsoleAppender
    participant Lay as Layout

    App->>L: info("order placed")
    L->>L: isEnabledFor(INFO)? (ordinal check vs getEffectiveLevel())
    alt enabled
        L->>L: new LogEvent(name, INFO, msg, null)
        loop walk self then ancestors while additive
            L->>A: append(event)
            A->>A: threshold/filter check
            A->>Lay: format(event)
            Lay-->>A: formatted string
            A->>A: out.print(formatted) (PrintStream internally synchronized)
        end
    else disabled
        L-->>App: return immediately (no allocation, no I/O)
    end
```

## 2. Async log call (AsyncAppender wrapping RollingFileAppender)

```mermaid
sequenceDiagram
    participant App as Application thread (producer)
    participant L as Logger("com.app.payment")
    participant Async as AsyncAppender
    participant Q as BlockingQueue<LogEvent>
    participant W as Background writer thread (consumer)
    participant RF as RollingFileAppender

    App->>L: debug("processing payment 42")
    L->>L: isEnabledFor(DEBUG)? yes
    L->>Async: append(event)
    Async->>Async: running? backpressurePolicy?
    Async->>Q: put/offer(event)
    Note over App,Q: App thread returns here -- does NOT wait for the write to disk.

    par background consumption (independent of producer)
        W->>Q: take() (blocks until an event or sentinel arrives)
        Q-->>W: event
        W->>RF: append(event)
        RF->>RF: lock.lock()
        RF->>RF: write line, update bytesWrittenSinceOpen
        RF->>RF: afterWriteLocked(): size >= maxBytes? roll over if so
        RF->>RF: lock.unlock()
    end
```

## 3. Graceful shutdown (LoggerContext.shutdown())

```mermaid
sequenceDiagram
    participant Main as Shutdown hook / main()
    participant Ctx as LoggerContext
    participant Async as AsyncAppender
    participant Q as BlockingQueue<LogEvent>
    participant W as Background writer thread
    participant RF as RollingFileAppender

    Main->>Ctx: shutdown()
    Ctx->>Ctx: collect distinct appenders reachable from all loggers
    Ctx->>Async: close()
    Async->>Async: running.compareAndSet(true, false)
    Async->>Q: put(SHUTDOWN_SENTINEL) (guaranteed delivery, blocks if full)
    Note over Async,Q: Any events already queued are still ahead of the sentinel (FIFO) -- they WILL be processed before shutdown completes.
    Async->>W: (implicitly) wait via join(timeoutMillis)
    loop drain remaining real events
        W->>Q: take()
        Q-->>W: event
        W->>RF: append(event)
    end
    W->>Q: take()
    Q-->>W: SHUTDOWN_SENTINEL
    W->>W: break out of consume loop (thread terminates)
    Async->>Async: consumerThread.join(timeoutMillis) returns
    Async->>RF: close() (flush + close file handle)
    Ctx-->>Main: shutdown() returns
```

## 4. Hierarchical configuration (Builder -> LoggerContext.configure)

```mermaid
sequenceDiagram
    participant Client as Configuring code
    participant B as LoggingConfiguration.Builder
    participant Cfg as LoggingConfiguration
    participant Ctx as LoggerContext
    participant L as Logger("com.app.payment")

    Client->>B: builder().rootLevel(INFO).addRootAppender(console)
    Client->>B: forLogger("com.app.payment").level(DEBUG).additive(false).addAppender(asyncFile).and()
    Client->>B: build()
    B-->>Cfg: new LoggingConfiguration(...)
    Client->>Ctx: configure(configuration)
    Ctx->>Ctx: root.setLevel(INFO); root.addAppender(console)
    loop for each LoggerConfig
        Ctx->>Ctx: getLogger(name) (creates hierarchy chain if absent)
        Ctx->>L: setLevel(DEBUG)
        Ctx->>L: setAdditive(false)
        Ctx->>L: addAppender(asyncFile)
    end
```
