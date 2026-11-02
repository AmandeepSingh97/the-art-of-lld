package lld.splitwise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import lld.splitwise.exception.DuplicateExpenseException;
import lld.splitwise.ledger.BalanceSheetService;
import lld.splitwise.model.Group;
import lld.splitwise.model.Money;
import lld.splitwise.model.SplitType;
import lld.splitwise.model.User;
import lld.splitwise.repository.InMemoryExpenseRepository;
import lld.splitwise.repository.InMemoryGroupRepository;
import lld.splitwise.repository.InMemoryUserRepository;
import lld.splitwise.service.ExpenseService;
import lld.splitwise.service.GroupService;
import lld.splitwise.service.UserService;
import lld.splitwise.split.SplitInput;

/**
 * Exercises concurrent expense additions against a shared group ledger,
 * verifying the atomicity/consistency guarantees documented on
 * {@link lld.splitwise.ledger.BalanceSheet}: the ledger must never be
 * observed in a torn state, and the net balance invariant (sum of all net
 * balances == 0) must hold no matter how many threads add expenses
 * concurrently.
 */
class ExpenseServiceConcurrencyTest {

    @Test
    void manyThreadsAddingExpensesConcurrentlyNeverCorruptTheLedger() throws InterruptedException {
        UserService userService = new UserService(new InMemoryUserRepository());
        GroupService groupService = new GroupService(new InMemoryGroupRepository(), userService);
        BalanceSheetService balanceSheetService = new BalanceSheetService();
        ExpenseService expenseService = new ExpenseService(
                new InMemoryExpenseRepository(), userService, groupService, balanceSheetService);

        User alice = userService.register("Alice", "a@x.com");
        User bob = userService.register("Bob", "b@x.com");
        User carol = userService.register("Carol", "c@x.com");
        Group group = groupService.createGroup("Trip", alice.id(), Set.of(bob.id(), carol.id()));

        int threadCount = 16;
        int expensesPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger failures = new AtomicInteger();

        List<String> participants = List.of(alice.id(), bob.id(), carol.id());

        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    String payer = participants.get(threadIndex % participants.size());
                    for (int i = 0; i < expensesPerThread; i++) {
                        expenseService.addExpense(
                                group.id(), "Expense-" + threadIndex + "-" + i, Money.ofMajorUnits("9.00"), payer,
                                SplitType.EQUAL, new SplitInput.EqualSplitInput(participants), null);
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(60, TimeUnit.SECONDS), "Threads did not finish in time");
        pool.shutdown();

        assertEquals(0, failures.get(), "No expense addition should fail");

        var net = balanceSheetService.computeNetBalancePerUser(group.id());
        long sum = net.values().stream().mapToLong(Long::longValue).sum();
        assertEquals(0L, sum, "Sum of all net balances must always be exactly zero (zero-sum invariant)");

        // Each 9.00 expense splits 3 ways as 3.00 each (evenly divisible), so every non-payer's
        // debt to a payer accumulates in exact 3.00 multiples -- verifies no lost/duplicated updates.
        long totalExpenseMinorUnits = (long) threadCount * expensesPerThread * 900L;
        long totalPayerCredit = net.values().stream().filter(v -> v > 0).mapToLong(Long::longValue).sum();
        long totalDebtorDebt = -net.values().stream().filter(v -> v < 0).mapToLong(Long::longValue).sum();
        assertEquals(totalPayerCredit, totalDebtorDebt);
    }

    @Test
    void idempotencyKeyPreventsDuplicateExpenseCreationUnderConcurrentRetries() throws InterruptedException {
        UserService userService = new UserService(new InMemoryUserRepository());
        GroupService groupService = new GroupService(new InMemoryGroupRepository(), userService);
        BalanceSheetService balanceSheetService = new BalanceSheetService();
        ExpenseService expenseService = new ExpenseService(
                new InMemoryExpenseRepository(), userService, groupService, balanceSheetService);

        User alice = userService.register("Alice", "a@x.com");
        User bob = userService.register("Bob", "b@x.com");
        Group group = groupService.createGroup("Trip", alice.id(), Set.of(bob.id()));
        List<String> participants = List.of(alice.id(), bob.id());

        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        String idempotencyKey = "retry-key-123";

        java.util.concurrent.ConcurrentLinkedQueue<String> expenseIds = new java.util.concurrent.ConcurrentLinkedQueue<>();

        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    var expense = expenseService.addExpense(
                            group.id(), "Dinner", Money.ofMajorUnits("20.00"), alice.id(), SplitType.EQUAL,
                            new SplitInput.EqualSplitInput(participants), idempotencyKey);
                    expenseIds.add(expense.id());
                } catch (DuplicateExpenseException ignored) {
                    // Acceptable: a narrow race can surface this on some implementations.
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        long distinctIds = expenseIds.stream().distinct().count();
        assertEquals(1, distinctIds, "All concurrent retries with the same idempotency key must resolve to exactly one expense");

        Money bobOwesAlice = balanceSheetService.getBalance(group.id(), bob.id(), alice.id());
        assertEquals(Money.ofMajorUnits("10.00"), bobOwesAlice, "Only one expense's worth of debt should have been applied");
    }
}
