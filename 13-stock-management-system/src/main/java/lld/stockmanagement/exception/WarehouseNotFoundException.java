package lld.stockmanagement.exception;

/** Thrown when an operation references a warehouse code that does not exist. */
public class WarehouseNotFoundException extends RuntimeException {
    public WarehouseNotFoundException(String warehouseCode) {
        super("No warehouse registered with code: " + warehouseCode);
    }
}
