package lld.stockmanagement.exception;

/** Thrown when attempting to register a SKU code that is already registered. */
public class DuplicateSkuException extends RuntimeException {
    public DuplicateSkuException(String skuCode) {
        super("SKU already registered: " + skuCode);
    }
}
