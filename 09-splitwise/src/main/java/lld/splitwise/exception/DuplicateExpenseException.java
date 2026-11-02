package lld.splitwise.exception;

/**
 * Thrown when an expense submission reuses an idempotency key that was
 * already used to successfully create a different expense. See
 * {@link lld.splitwise.service.ExpenseService} for the idempotency contract.
 */
public class DuplicateExpenseException extends RuntimeException {
    public DuplicateExpenseException(String idempotencyKey) {
        super("Expense already submitted with idempotency key: " + idempotencyKey);
    }
}
