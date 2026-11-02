# Component / Package Diagram

```mermaid
flowchart TB
    subgraph API["API layer (not shown as code - conceptual callers)"]
        Client["Client (mobile/web/batch)"]
    end

    subgraph Service["service package"]
        WS["WalletService (interface)"]
        WSI["WalletServiceImpl"]
    end

    subgraph Idem["idempotency package"]
        IS["IdempotencyStore (interface)"]
        IMIS["InMemoryIdempotencyStore"]
    end

    subgraph Repo["repository package"]
        AR["AccountRepository"]
        TR["TransactionRepository"]
        LR["LedgerRepository"]
        IAR["InMemoryAccountRepository"]
        ITR["InMemoryTransactionRepository"]
        ILR["InMemoryLedgerRepository"]
    end

    subgraph Model["model package"]
        Account["Account"]
        Money["Money"]
        Transaction["Transaction"]
        LedgerEntry["LedgerEntry"]
    end

    subgraph Dto["dto package"]
        TReq["TransferRequest"]
        TRes["TransferResult"]
    end

    subgraph Exc["exception package"]
        WalletException["WalletException hierarchy"]
    end

    Client --> WS
    WS --> WSI
    WSI --> IS
    IS --> IMIS
    WSI --> AR
    WSI --> TR
    WSI --> LR
    AR --> IAR
    TR --> ITR
    LR --> ILR
    WSI --> Account
    WSI --> Transaction
    WSI --> LedgerEntry
    WSI --> Dto
    WSI --> Exc
    Account --> Money
    Transaction --> Money
    LedgerEntry --> Money
```

**Why this layering:** `WalletService` is the only public surface a caller (REST
controller, gRPC handler, CLI, batch job) depends on. `WalletServiceImpl` is the
sole place that knows about locking order, idempotency, and ledger writes - every
other package is either a pure data holder (`model`, `dto`) or a swappable
storage/idempotency backend (`repository`, `idempotency`) reachable only through
an interface, so an in-memory demo and a future JDBC-backed production
implementation share the exact same service code.
