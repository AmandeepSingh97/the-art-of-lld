# Sequence Diagram: Vehicle Exit (`unparkVehicle`)

Matches `ExitGateService.processExit` -> `ParkingLotService.unparkVehicle`.

```mermaid
sequenceDiagram
    actor Driver
    participant XG as ExitGateService
    participant PLS as ParkingLotService
    participant TR as TicketRepository
    participant PRS as PricingStrategy
    participant PS as PaymentService
    participant PP as PaymentProcessor
    participant SR as SpotRepository
    participant SPOT as ParkingSpot
    participant TIX as Ticket

    Driver->>XG: processExit(ticketId, CASH)
    XG->>PLS: unparkVehicle(ticketId, exitGate, CASH)
    PLS->>TR: findById(ticketId)
    TR-->>PLS: ticket (status=ACTIVE)
    PLS->>PRS: calculateFee(ticket, now)
    PRS-->>PLS: fee
    PLS->>PS: pay(ticketId, fee, CASH)
    PS->>PP: charge(ticketId, fee)
    PP-->>PS: Payment(status=SUCCESS)
    PS-->>PLS: payment
    PLS->>TIX: close(exitGateId, now, fee)
    PLS->>SR: freeSpot(spot)
    SR->>SPOT: vacate() [CAS OCCUPIED->AVAILABLE]
    SR->>SR: re-insert spot into available index
    PLS->>TR: releaseClaim(licensePlate)
    PLS-->>XG: ExitReceipt(ticket, payment)
    XG-->>Driver: receipt
```

**Why payment happens before the ticket is closed and the spot is freed:**
if `PaymentProcessor.charge` reports a failure, `PaymentService.pay` throws
`PaymentFailedException` *before* any of `Ticket.close`, `SpotRepository.freeSpot`,
or `TicketRepository.releaseClaim` run - so a failed payment leaves the
system in exactly its pre-exit-attempt state (ticket still ACTIVE, spot
still OCCUPIED, plate still claimed) and the driver can simply retry the
exit with a different payment method. This ordering is the core of the
"no spot is ever given away for free" invariant (README section 5).
