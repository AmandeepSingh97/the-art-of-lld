package lld.splitwise.app;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lld.splitwise.ledger.BalanceSheetService;
import lld.splitwise.model.Group;
import lld.splitwise.model.Money;
import lld.splitwise.model.Settlement;
import lld.splitwise.model.SplitType;
import lld.splitwise.model.User;
import lld.splitwise.repository.InMemoryExpenseRepository;
import lld.splitwise.repository.InMemoryGroupRepository;
import lld.splitwise.repository.InMemorySettlementRepository;
import lld.splitwise.repository.InMemoryUserRepository;
import lld.splitwise.service.ExpenseService;
import lld.splitwise.service.GroupService;
import lld.splitwise.service.SettlementService;
import lld.splitwise.service.UserService;
import lld.splitwise.split.SplitInput;

/**
 * Runnable demonstration exercising every major flow: user/group setup,
 * all three split strategies, balance queries, debt simplification, and
 * settle-up. Run with {@code java lld.splitwise.app.SplitwiseDemo} after
 * compiling.
 */
public final class SplitwiseDemo {

    public static void main(String[] args) {
        UserService userService = new UserService(new InMemoryUserRepository());
        GroupService groupService = new GroupService(new InMemoryGroupRepository(), userService);
        BalanceSheetService balanceSheetService = new BalanceSheetService();
        ExpenseService expenseService = new ExpenseService(
                new InMemoryExpenseRepository(), userService, groupService, balanceSheetService);
        SettlementService settlementService = new SettlementService(
                balanceSheetService, new InMemorySettlementRepository());

        User alice = userService.register("Alice", "alice@example.com");
        User bob = userService.register("Bob", "bob@example.com");
        User carol = userService.register("Carol", "carol@example.com");
        User dave = userService.register("Dave", "dave@example.com");

        Group trip = groupService.createGroup(
                "Goa Trip", alice.id(), Set.of(bob.id(), carol.id(), dave.id()));
        System.out.println("Created group: " + trip);

        // 1) EQUAL split: Alice pays $100 for dinner, split equally 4 ways.
        expenseService.addExpense(
                trip.id(), "Dinner", Money.ofMajorUnits("100.00"), alice.id(), SplitType.EQUAL,
                new SplitInput.EqualSplitInput(List.of(alice.id(), bob.id(), carol.id(), dave.id())),
                null);

        // 2) EXACT split: Bob pays $90 for groceries, with explicit per-person amounts.
        expenseService.addExpense(
                trip.id(), "Groceries", Money.ofMajorUnits("90.00"), bob.id(), SplitType.EXACT,
                new SplitInput.ExactSplitInput(Map.of(
                        alice.id(), Money.ofMajorUnits("30.00"),
                        bob.id(), Money.ofMajorUnits("20.00"),
                        carol.id(), Money.ofMajorUnits("25.00"),
                        dave.id(), Money.ofMajorUnits("15.00"))),
                null);

        // 3) PERCENT split: Carol pays $50 for a cab, split by percentage.
        expenseService.addExpense(
                trip.id(), "Cab", Money.ofMajorUnits("50.00"), carol.id(), SplitType.PERCENT,
                new SplitInput.PercentSplitInput(Map.of(
                        alice.id(), new BigDecimal("25"),
                        bob.id(), new BigDecimal("25"),
                        carol.id(), new BigDecimal("25"),
                        dave.id(), new BigDecimal("25"))),
                null);

        System.out.println("\n--- Balances after 3 expenses ---");
        for (User user : List.of(alice, bob, carol, dave)) {
            Map<String, Money> balances = balanceSheetService.getBalancesForUser(trip.id(), user.id());
            System.out.println(user.name() + ": " + describe(balances, Map.of(
                    alice.id(), "Alice", bob.id(), "Bob", carol.id(), "Carol", dave.id(), "Dave")));
        }

        System.out.println("\n--- Suggested simplified settlements ---");
        List<Settlement> suggestions = settlementService.suggestSimplifiedSettlements(trip.id());
        Map<String, String> names = Map.of(
                alice.id(), "Alice", bob.id(), "Bob", carol.id(), "Carol", dave.id(), "Dave");
        for (Settlement s : suggestions) {
            System.out.println(names.get(s.fromUserId()) + " pays " + names.get(s.toUserId()) + " " + s.amount());
        }

        System.out.println("\n--- Recording actual settle-up payments ---");
        for (Settlement s : suggestions) {
            settlementService.recordSettlement(trip.id(), s.fromUserId(), s.toUserId(), s.amount());
            System.out.println("Recorded: " + names.get(s.fromUserId()) + " -> " + names.get(s.toUserId()) + " " + s.amount());
        }

        System.out.println("\n--- Pairwise balances after settling up ---");
        System.out.println("(Note: simplified settlements zero out NET balances, but since they are");
        System.out.println(" computed from the net graph rather than the original pairwise graph, they");
        System.out.println(" can introduce NEW pairwise edges between users with no direct expense");
        System.out.println(" history. Each user's total net balance is still exactly zero below.)");
        for (User user : List.of(alice, bob, carol, dave)) {
            Map<String, Money> balances = balanceSheetService.getBalancesForUser(trip.id(), user.id());
            System.out.println(user.name() + ": " + (balances.isEmpty() ? "settled up" : describe(balances, names)));
        }

        System.out.println("\n--- Net balance per user after settling up (must all be zero) ---");
        Map<String, Long> netBalances = balanceSheetService.computeNetBalancePerUser(trip.id());
        for (User user : List.of(alice, bob, carol, dave)) {
            long net = netBalances.getOrDefault(user.id(), 0L);
            System.out.println(user.name() + ": " + Money.ofMinorUnits(net));
        }
    }

    private static String describe(Map<String, Money> balances, Map<String, String> names) {
        if (balances.isEmpty()) {
            return "settled up";
        }
        StringBuilder sb = new StringBuilder();
        balances.forEach((userId, amount) -> {
            if (sb.length() > 0) sb.append(", ");
            if (amount.isPositive()) {
                sb.append("owes ").append(names.get(userId)).append(" ").append(amount);
            } else if (amount.isNegative()) {
                sb.append(names.get(userId)).append(" owes them ").append(amount.abs());
            }
        });
        return sb.toString();
    }
}
