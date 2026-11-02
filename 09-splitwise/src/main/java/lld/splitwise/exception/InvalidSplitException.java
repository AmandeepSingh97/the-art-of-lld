package lld.splitwise.exception;

/** Thrown when split input is structurally invalid (bad sum, negative amount, duplicates, etc.). */
public class InvalidSplitException extends RuntimeException {
    public InvalidSplitException(String message) {
        super(message);
    }
}
