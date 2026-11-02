package lld.digitalwallet.exception;

/** Thrown when a transaction cannot be reversed in its current state (already reversed, or failed and
 *  therefore has nothing to undo). Keeps the reversal state machine explicit rather than silently no-op'ing. */
public final class IllegalTransactionStateException extends WalletException {
    public IllegalTransactionStateException(String message) {
        super(message);
    }
}
