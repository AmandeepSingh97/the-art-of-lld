package lld.splitwise;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import lld.splitwise.ledger.BalanceSheetService;
import lld.splitwise.model.Expense;
import lld.splitwise.model.ExpenseShare;
import lld.splitwise.model.Money;
import lld.splitwise.model.Settlement;
import lld.splitwise.model.SplitType;

class BalanceSheetServiceTest {

    @Test
    void applyingAnExpenseCreditsThePayerAndDebitsOtherParticipants() {
        BalanceSheetService ledger = new BalanceSheetService();
        Expense expense = new Expense(
                "e1", "g1", "Dinner", Money.ofMajorUnits("30.00"), "alice",
                SplitType.EQUAL,
                List.of(
                        new ExpenseShare("alice", Money.ofMajorUnits("10.00")),
                        new ExpenseShare("bob", Money.ofMajorUnits("10.00")),
                        new ExpenseShare("carol", Money.ofMajorUnits("10.00"))),
                java.time.Instant.now());

        ledger.applyExpense(expense);

        assertEquals(Money.ofMajorUnits("-10.00"), ledger.getBalance("g1", "alice", "bob"));
        assertEquals(Money.ofMajorUnits("10.00"), ledger.getBalance("g1", "bob", "alice"));
        assertEquals(Money.ofMajorUnits("10.00"), ledger.getBalance("g1", "carol", "alice"));
    }

    @Test
    void payersOwnShareDoesNotCreateASelfDebt() {
        BalanceSheetService ledger = new BalanceSheetService();
        Expense expense = new Expense(
                "e1", "g1", "Solo lunch", Money.ofMajorUnits("10.00"), "alice",
                SplitType.EQUAL,
                List.of(new ExpenseShare("alice", Money.ofMajorUnits("10.00"))),
                java.time.Instant.now());

        ledger.applyExpense(expense);

        Map<String, Money> balances = ledger.getBalancesForUser("g1", "alice");
        assertEquals(0, balances.size());
    }

    @Test
    void settlementReducesExistingDebt() {
        BalanceSheetService ledger = new BalanceSheetService();
        Expense expense = new Expense(
                "e1", "g1", "Dinner", Money.ofMajorUnits("20.00"), "alice",
                SplitType.EQUAL,
                List.of(
                        new ExpenseShare("alice", Money.ofMajorUnits("10.00")),
                        new ExpenseShare("bob", Money.ofMajorUnits("10.00"))),
                java.time.Instant.now());
        ledger.applyExpense(expense);
        assertEquals(Money.ofMajorUnits("10.00"), ledger.getBalance("g1", "bob", "alice"));

        Settlement settlement = new Settlement("s1", "g1", "bob", "alice", Money.ofMajorUnits("10.00"), java.time.Instant.now());
        ledger.applySettlement(settlement);

        assertEquals(Money.ZERO, ledger.getBalance("g1", "bob", "alice"));
        assertEquals(Money.ZERO, ledger.getBalance("g1", "alice", "bob"));
    }

    @Test
    void netBalancePerUserSumsToZeroAcrossTheWholeGroup() {
        BalanceSheetService ledger = new BalanceSheetService();
        Expense e1 = new Expense("e1", "g1", "Dinner", Money.ofMajorUnits("30.00"), "alice", SplitType.EQUAL,
                List.of(new ExpenseShare("alice", Money.ofMajorUnits("10.00")),
                        new ExpenseShare("bob", Money.ofMajorUnits("10.00")),
                        new ExpenseShare("carol", Money.ofMajorUnits("10.00"))),
                java.time.Instant.now());
        Expense e2 = new Expense("e2", "g1", "Cab", Money.ofMajorUnits("15.00"), "bob", SplitType.EQUAL,
                List.of(new ExpenseShare("alice", Money.ofMajorUnits("5.00")),
                        new ExpenseShare("bob", Money.ofMajorUnits("5.00")),
                        new ExpenseShare("carol", Money.ofMajorUnits("5.00"))),
                java.time.Instant.now());
        ledger.applyExpense(e1);
        ledger.applyExpense(e2);

        Map<String, Long> net = ledger.computeNetBalancePerUser("g1");
        long sum = net.values().stream().mapToLong(Long::longValue).sum();
        assertEquals(0L, sum);
    }

    @Test
    void differentGroupsHaveIndependentLedgers() {
        BalanceSheetService ledger = new BalanceSheetService();
        Expense expense = new Expense(
                "e1", "g1", "Dinner", Money.ofMajorUnits("20.00"), "alice", SplitType.EQUAL,
                List.of(new ExpenseShare("alice", Money.ofMajorUnits("10.00")),
                        new ExpenseShare("bob", Money.ofMajorUnits("10.00"))),
                java.time.Instant.now());
        ledger.applyExpense(expense);

        assertEquals(Money.ZERO, ledger.getBalance("g2", "bob", "alice"));
        assertEquals(Money.ofMajorUnits("10.00"), ledger.getBalance("g1", "bob", "alice"));
    }
}
