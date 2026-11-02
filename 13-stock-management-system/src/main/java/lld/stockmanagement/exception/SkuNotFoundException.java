package lld.stockmanagement.exception;

/** Thrown when an operation references a SKU code that does not exist in the catalog. */
public class SkuNotFoundException extends RuntimeException {
    public SkuNotFoundException(String skuCode) {
        super("No SKU registered with code: " + skuCode);
    }
}
