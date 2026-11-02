package lld.splitwise.exception;

/** Thrown when a settle-up request is invalid, e.g. settling more than is actually owed. */
public class InvalidSettlementException extends RuntimeException {
    public InvalidSettlementException(String message) {
        super(message);
    }
}
