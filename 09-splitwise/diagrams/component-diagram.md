# Component Diagram

```mermaid
graph TB
    subgraph "Client Layer"
        Demo["SplitwiseDemo (Main)"]
    end

    subgraph "Service Layer (Orchestration)"
        US[UserService]
        GS[GroupService]
        ES[ExpenseService]
        SS[SettlementService]
    end

    subgraph "Domain Logic"
        SSF[SplitStrategyFactory]
        EqS[EqualSplitStrategy]
        ExS[ExactSplitStrategy]
        PcS[PercentSplitStrategy]
        DSimp[DebtSimplifier]
    end

    subgraph "Ledger (Shared Mutable State)"
        BSS[BalanceSheetService]
        BS1[BalanceSheet - group1]
        BS2[BalanceSheet - group2]
        BSD["BalanceSheet - DIRECT"]
    end

    subgraph "Repository Layer (Persistence Abstraction)"
        UR[(UserRepository)]
        GR[(GroupRepository)]
        ER[(ExpenseRepository)]
        SR[(SettlementRepository)]
    end

    subgraph "Domain Model"
        User
        Group
        Expense
        Settlement
        Money
    end

    Demo --> US
    Demo --> GS
    Demo --> ES
    Demo --> SS

    US --> UR
    GS --> GR
    GS --> US

    ES --> UR
    ES --> GR
    ES --> ER
    ES --> SSF
    ES --> BSS
    SSF --> EqS
    SSF --> ExS
    SSF --> PcS

    SS --> BSS
    SS --> SR
    SS --> DSimp

    BSS --> BS1
    BSS --> BS2
    BSS --> BSD

    ER -.-> Expense
    UR -.-> User
    GR -.-> Group
    SR -.-> Settlement
    Expense --> Money
    Settlement --> Money
```

**Key boundaries:**
- **Service layer** is the only caller of repositories and the ledger; it owns transactional orchestration (validate → compute → persist → apply to ledger).
- **Split strategies** are pure functions of `(Money, SplitInput) -> List<ExpenseShare>` with no I/O, no locking, trivially unit-testable in isolation.
- **BalanceSheetService** owns all shared mutable state and all locking; every other component treats it as an opaque, thread-safe black box.
- **Repositories** are swappable (in-memory today; JDBC/NoSQL-backed tomorrow) behind interfaces — no service class depends on a concrete repository implementation.
