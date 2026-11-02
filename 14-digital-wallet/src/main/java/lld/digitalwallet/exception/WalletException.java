package lld.digitalwallet.exception;

/** Base unchecked exception for all wallet-domain failures. Unchecked because callers (API layer) are
 *  expected to catch the specific subtype they care about and translate it to a transport-level error
 *  (HTTP 4xx/5xx); forcing checked exceptions through a service interface used by multiple transports
 *  (REST, gRPC, batch) adds boilerplate without adding safety here. */
public abstract class WalletException extends RuntimeException {
    protected WalletException(String message) {
        super(message);
    }

    protected WalletException(String message, Throwable cause) {
        super(message, cause);
    }
}
