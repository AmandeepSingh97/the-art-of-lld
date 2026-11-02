package lld.digitalwallet.exception;

/** Thrown for structurally invalid requests: zero/negative amounts, same source and destination account,
 *  blank idempotency keys, etc. Caught at the API boundary and mapped to HTTP 400 in a real deployment. */
public final class InvalidRequestException extends WalletException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
