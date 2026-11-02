package lld.digitalwallet.model;

/** Terminal/non-terminal state of a {@link Transaction}. */
public enum TransactionStatus {
    /** Accepted and durably recorded; balances already reflect it. In this in-memory design we only ever
     *  observe COMPLETED or FAILED because the critical section is synchronous - no in-flight PENDING state
     *  leaks to callers. A distributed/async implementation would have a real PENDING state (see
     *  STAFF_LEVEL_DEEP_DIVE.md on sagas). */
    COMPLETED,
    FAILED,
    /** The transaction was itself undone by a {@link TransactionType#REVERSAL} transaction. */
    REVERSED
}
