# Sequence Diagrams

## 1. Successful wallet-to-wallet transfer (first attempt)

```mermaid
sequenceDiagram
    actor Client
    participant WS as WalletServiceImpl
    participant Idem as IdempotencyStore
    participant AR as AccountRepository
    participant SrcAcc as Account (source)
    participant DstAcc as Account (destination)
    participant TR as TransactionRepository
    participant LR as LedgerRepository

    Client->>WS: transfer(request{src, dst, amount, idemKey})
    WS->>WS: validate(amount>0, src != dst, idemKey non-blank)
    WS->>Idem: computeIfAbsent(idemKey, computation)
    activate Idem
    Note over Idem: key not seen before -> runs computation
    Idem->>WS: invoke computation()
    WS->>AR: getOrThrow(src), getOrThrow(dst)
    AR-->>WS: SrcAcc, DstAcc
    WS->>WS: pick lockFirst/lockSecond by ascending accountId
    WS->>SrcAcc: lock()
    WS->>DstAcc: lock()
    WS->>SrcAcc: applyDebit(amount, enforceLimit=true)
    SrcAcc-->>WS: ok (balance -= amount, version++)
    WS->>DstAcc: applyCredit(amount)
    DstAcc-->>WS: ok (balance += amount, version++)
    WS->>TR: save(Transaction COMPLETED)
    WS->>LR: append(DEBIT entry on src)
    WS->>LR: append(CREDIT entry on dst)
    WS->>DstAcc: unlock()
    WS->>SrcAcc: unlock()
    WS-->>Idem: return Transaction
    deactivate Idem
    Idem-->>WS: Transaction (cached under idemKey)
    WS-->>Client: TransferResult{transaction, replayed=false}
```

## 2. Duplicate request with the same idempotency key (client retry after timeout)

```mermaid
sequenceDiagram
    actor Client
    participant WS as WalletServiceImpl
    participant Idem as IdempotencyStore

    Note over Client,WS: Client never saw the first response (network timeout) and retries.
    Client->>WS: transfer(request{..., idemKey=K})  (2nd time)
    WS->>Idem: computeIfAbsent(K, computation)
    Note over Idem: key K already cached from the first call -\ncomputation is NOT invoked again,\nno lock is ever acquired this time.
    Idem-->>WS: cached Transaction (same transactionId as before)
    WS-->>Client: TransferResult{transaction, replayed=true}
    Note over Client: Balances are provably unchanged by this call.
```

## 3. Concurrent opposite-direction transfers (deadlock-avoidance in action)

```mermaid
sequenceDiagram
    participant T1 as Thread 1 (A -> B)
    participant T2 as Thread 2 (B -> A)
    participant A as Account A (id "acc_aaa...")
    participant B as Account B (id "acc_bbb...")

    Note over T1,T2: Both threads compute lockFirst/lockSecond by ascending accountId.\nSuppose "acc_aaa..." < "acc_bbb...", so BOTH threads agree: lock A before B.
    T1->>A: lock() [acquires]
    T2->>A: lock() [BLOCKS - already held by T1]
    T1->>B: lock() [acquires, no contention yet]
    T1->>A: applyDebit
    T1->>B: applyCredit
    T1->>B: unlock()
    T1->>A: unlock()
    Note over T2: Now unblocks
    T2->>A: lock() [acquires]
    T2->>B: lock() [acquires]
    T2->>B: applyDebit
    T2->>A: applyCredit
    T2->>B: unlock()
    T2->>A: unlock()
    Note over T1,T2: No deadlock possible: neither thread ever holds B while waiting for A,\nbecause both always request A strictly before B.
```

## 4. Reversal of a completed transfer

```mermaid
sequenceDiagram
    actor Client
    participant WS as WalletServiceImpl
    participant TR as TransactionRepository
    participant SrcAcc as Account (originally debited)
    participant DstAcc as Account (originally credited)
    participant LR as LedgerRepository

    Client->>WS: reverse(originalTxnId, reversalIdemKey)
    WS->>TR: findById(originalTxnId)
    TR-->>WS: original Transaction (status=COMPLETED, type=TRANSFER)
    WS->>WS: postTwoEntryMovement(REVERSAL, debit=DstAcc, credit=SrcAcc, amount)
    Note over WS: Direction is flipped: the account that was credited is now debited, and vice versa.
    WS->>DstAcc: lock(), applyDebit(amount, enforceLimit=false)
    WS->>SrcAcc: lock(), applyCredit(amount)
    WS->>TR: save(reversal Transaction, reversalOf=originalTxnId)
    WS->>LR: append(DEBIT on DstAcc), append(CREDIT on SrcAcc)
    WS->>SrcAcc: releaseDailyLimitReservation(amount)
    WS->>TR: save(original.withStatus(REVERSED))
    Note over TR: original row is never mutated in place - a NEW immutable Transaction\nobject with status=REVERSED replaces it; the reversal is its own\nfirst-class Transaction, fully auditable.
    WS-->>Client: TransferResult{reversal, replayed=false}
```
