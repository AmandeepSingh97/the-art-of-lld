package lld.digitalwallet.exception;

/** Thrown when a request references a transaction id that does not exist (e.g. reversing an unknown transaction). */
public final class TransactionNotFoundException extends WalletException {
    public TransactionNotFoundException(String transactionId) {
        super("Transaction not found: " + transactionId);
    }
}
