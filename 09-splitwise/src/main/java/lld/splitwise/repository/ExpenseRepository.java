package lld.splitwise.repository;

import java.util.List;
import java.util.Optional;

import lld.splitwise.model.Expense;

public interface ExpenseRepository {
    Expense save(Expense expense);
    Optional<Expense> findById(String expenseId);
    List<Expense> findByGroupId(String groupId);

    /** Looks up a previously-created expense by idempotency key, if one was recorded. Returns empty if none exists. */
    Optional<Expense> findByIdempotencyKey(String idempotencyKey);

    /** Atomically associates an idempotency key with an expense id, iff the key is not already taken. Returns false if it was already taken. */
    boolean registerIdempotencyKey(String idempotencyKey, String expenseId);
}
