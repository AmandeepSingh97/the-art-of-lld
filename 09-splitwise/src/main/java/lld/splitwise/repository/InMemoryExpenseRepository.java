package lld.splitwise.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import lld.splitwise.model.Expense;

/**
 * In-memory implementation. {@code expensesByGroup} uses
 * {@link CopyOnWriteArrayList} per group since expense history is
 * append-mostly and read far more often than written (every balance
 * recomputation-from-scratch / audit view reads it); appends are
 * infrequent relative to reads so the copy-on-write cost is acceptable.
 *
 * <p>The idempotency map uses {@link ConcurrentHashMap#putIfAbsent}, which is
 * atomic, to implement "first submission wins" semantics for
 * {@link ExpenseRepository#registerIdempotencyKey} without any external lock —
 * see {@link lld.splitwise.service.ExpenseService} for how this prevents
 * duplicate expense creation under concurrent retries.
 */
public final class InMemoryExpenseRepository implements ExpenseRepository {

    private final Map<String, Expense> expensesById = new ConcurrentHashMap<>();
    private final Map<String, List<Expense>> expensesByGroup = new ConcurrentHashMap<>();
    private final Map<String, String> expenseIdByIdempotencyKey = new ConcurrentHashMap<>();

    @Override
    public Expense save(Expense expense) {
        expensesById.put(expense.id(), expense);
        if (expense.isGroupExpense()) {
            expensesByGroup
                    .computeIfAbsent(expense.groupId(), g -> new CopyOnWriteArrayList<>())
                    .add(expense);
        }
        return expense;
    }

    @Override
    public Optional<Expense> findById(String expenseId) {
        return Optional.ofNullable(expensesById.get(expenseId));
    }

    @Override
    public List<Expense> findByGroupId(String groupId) {
        return new ArrayList<>(expensesByGroup.getOrDefault(groupId, List.of()));
    }

    @Override
    public Optional<Expense> findByIdempotencyKey(String idempotencyKey) {
        String expenseId = expenseIdByIdempotencyKey.get(idempotencyKey);
        return expenseId == null ? Optional.empty() : findById(expenseId);
    }

    @Override
    public boolean registerIdempotencyKey(String idempotencyKey, String expenseId) {
        return expenseIdByIdempotencyKey.putIfAbsent(idempotencyKey, expenseId) == null;
    }
}
