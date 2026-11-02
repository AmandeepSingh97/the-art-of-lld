# Sequence Diagram — Card Purchase & Insufficient-Change Refund

## Card purchase (no coin-accumulation phase, no change needed)

```mermaid
sequenceDiagram
    actor Customer
    participant VM as VendingMachine
    participant S as currentState (IdleState)
    participant Slot as Inventory/Slot
    participant PS as CardPaymentStrategy
    participant GW as CardPaymentGateway
    participant Log as TransactionLog

    Customer->>VM: payByCard("A2")
    VM->>VM: machineLock.lock()
    VM->>S: payByCard(ctx, "A2")
    S->>Slot: getSlot("A2"); isEmpty()? no
    S->>PS: settle(price=$2.00, ctx)
    PS->>GW: charge($2.00)
    GW-->>PS: "AUTH-1" (approved)
    PS-->>S: PaymentOutcome(change={}, auth="AUTH-1")
    S->>Slot: dispenseOne() -> Product
    S->>VM: transitionTo(DispensingState)
    S->>VM: dispenseAndLog(product, {}, "CARD:AUTH-1")
    VM->>Log: record(Transaction)
    S->>VM: transitionTo(IdleState)
    VM->>VM: machineLock.unlock()
    VM-->>Customer: product dispensed, no change
```

## Insufficient-change abort and refund (cash flow)

```mermaid
sequenceDiagram
    actor Customer
    participant VM as VendingMachine
    participant S as currentState (HasMoneyState)
    participant PS as CashPaymentStrategy
    participant CB as CashBox

    Customer->>VM: selectProductForCash("A1")
    VM->>S: selectProductForCash(ctx, "A1")
    S->>PS: settle(price, ctx)
    PS->>CB: deposit(insertedDenominations)
    PS->>CB: withdrawForChange(changeOwed, changeMaker)
    CB-->>PS: throws InsufficientChangeException
    PS->>CB: reverseDeposit(insertedDenominations)
    PS-->>S: throws InsufficientChangeException
    S->>VM: refundInsertedCash()
    S->>VM: transitionTo(IdleState)
    S-->>VM: rethrow InsufficientChangeException
    VM-->>Customer: transaction aborted, exact cash returned
```
