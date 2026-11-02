package lld.splitwise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import lld.splitwise.exception.InvalidSettlementException;
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

class SettlementServiceTest {

    private record Fixture(UserService userService, GroupService groupService, ExpenseService expenseService,
                            SettlementService settlementService, BalanceSheetService balanceSheetService) {
    }

    private Fixture newFixture() {
        UserService userService = new UserService(new InMemoryUserRepository());
        GroupService groupService = new GroupService(new InMemoryGroupRepository(), userService);
        BalanceSheetService balanceSheetService = new BalanceSheetService();
        ExpenseService expenseService = new ExpenseService(
                new InMemoryExpenseRepository(), userService, groupService, balanceSheetService);
        SettlementService settlementService = new SettlementService(
                balanceSheetService, new InMemorySettlementRepository());
        return new Fixture(userService, groupService, expenseService, settlementService, balanceSheetService);
    }

    @Test
    void recordingASettlementReducesTheDebt() {
        Fixture f = newFixture();
        User alice = f.userService().register("Alice", "a@x.com");
        User bob = f.userService().register("Bob", "b@x.com");
        Group group = f.groupService().createGroup("Trip", alice.id(), Set.of(bob.id()));

        f.expenseService().addExpense(group.id(), "Dinner", Money.ofMajorUnits("20.00"), alice.id(), SplitType.EQUAL,
                new SplitInput.EqualSplitInput(List.of(alice.id(), bob.id())), null);

        assertEquals(Money.ofMajorUnits("10.00"), f.balanceSheetService().getBalance(group.id(), bob.id(), alice.id()));

        Settlement settlement = f.settlementService().recordSettlement(group.id(), bob.id(), alice.id(), Money.ofMajorUnits("10.00"));
        assertEquals(Money.ofMajorUnits("10.00"), settlement.amount());
        assertEquals(Money.ZERO, f.balanceSheetService().getBalance(group.id(), bob.id(), alice.id()));
    }

    @Test
    void rejectsSettlementWhenNothingIsOwedInThatDirection() {
        Fixture f = newFixture();
        User alice = f.userService().register("Alice", "a@x.com");
        User bob = f.userService().register("Bob", "b@x.com");
        Group group = f.groupService().createGroup("Trip", alice.id(), Set.of(bob.id()));

        assertThrows(InvalidSettlementException.class,
                () -> f.settlementService().recordSettlement(group.id(), bob.id(), alice.id(), Money.ofMajorUnits("5.00")));
    }

    @Test
    void suggestedSimplificationFullySettlesTheGroupWhenApplied() {
        Fixture f = newFixture();
        User alice = f.userService().register("Alice", "a@x.com");
        User bob = f.userService().register("Bob", "b@x.com");
        User carol = f.userService().register("Carol", "c@x.com");
        Group group = f.groupService().createGroup("Trip", alice.id(), Set.of(bob.id(), carol.id()));

        f.expenseService().addExpense(group.id(), "Dinner", Money.ofMajorUnits("30.00"), alice.id(), SplitType.EQUAL,
                new SplitInput.EqualSplitInput(List.of(alice.id(), bob.id(), carol.id())), null);
        f.expenseService().addExpense(group.id(), "Cab", Money.ofMajorUnits("15.00"), bob.id(), SplitType.EQUAL,
                new SplitInput.EqualSplitInput(List.of(alice.id(), bob.id(), carol.id())), null);

        List<Settlement> suggestions = f.settlementService().suggestSimplifiedSettlements(group.id());
        for (Settlement s : suggestions) {
            f.settlementService().recordSettlement(group.id(), s.fromUserId(), s.toUserId(), s.amount());
        }

        var net = f.balanceSheetService().computeNetBalancePerUser(group.id());
        for (long v : net.values()) {
            assertEquals(0L, v);
        }
    }
}
