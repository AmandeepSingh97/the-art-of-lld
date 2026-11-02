package lld.digitalwallet;

import lld.digitalwallet.dto.TransferRequest;
import lld.digitalwallet.dto.TransferResult;
import lld.digitalwallet.exception.AccountNotActiveException;
import lld.digitalwallet.exception.IllegalTransactionStateException;
import lld.digitalwallet.exception.InsufficientFundsException;
import lld.digitalwallet.exception.InvalidRequestException;
import lld.digitalwallet.exception.TransferLimitExceededException;
import lld.digitalwallet.idempotency.InMemoryIdempotencyStore;
import lld.digitalwallet.model.Account;
import lld.digitalwallet.model.KycTier;
import lld.digitalwallet.model.LedgerEntry;
import lld.digitalwallet.model.Money;
import lld.digitalwallet.repository.InMemoryAccountRepository;
import lld.digitalwallet.repository.InMemoryLedgerRepository;
import lld.digitalwallet.repository.InMemoryTransactionRepository;
import lld.digitalwallet.service.WalletService;
import lld.digitalwallet.service.WalletServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletServiceImplTest {

    private final Currency usd = Currency.getInstance("USD");
    private WalletService wallet;
    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() {
        wallet = new WalletServiceImpl(
                new InMemoryAccountRepository(),
                new InMemoryTransactionRepository(),
                new InMemoryLedgerRepository(),
                new InMemoryIdempotencyStore(),
                Clock.systemUTC());
        alice = wallet.openAccount("alice", usd, KycTier.TIER_1_BASIC);
        bob = wallet.openAccount("bob", usd, KycTier.TIER_1_BASIC);
        wallet.deposit(alice.accountId(), dollars("100.00"), UUID.randomUUID().toString(), "seed");
    }

    private Money dollars(String amount) {
        return Money.ofMajorUnits(new BigDecimal(amount), usd);
    }

    @Test
    void transferMovesMoneyBetweenAccountsAtomically() {
        wallet.transfer(new TransferRequest(alice.accountId(), bob.accountId(), dollars("40.00"),
                UUID.randomUUID().toString(), "rent"));

        assertEquals(dollars("60.00"), wallet.getBalance(alice.accountId()));
        assertEquals(dollars("40.00"), wallet.getBalance(bob.accountId()));
    }

    @Test
    void transferWritesTwoBalancedLedgerEntries() {
        TransferResult result = wallet.transfer(new TransferRequest(alice.accountId(), bob.accountId(),
                dollars("25.00"), UUID.randomUUID().toString(), "split"));

        List<LedgerEntry> aliceStatement = wallet.getStatement(alice.accountId(), Instant.EPOCH, Instant.now().plusSeconds(10));
        List<LedgerEntry> bobStatement = wallet.getStatement(bob.accountId(), Instant.EPOCH, Instant.now().plusSeconds(10));

        long netDebit = aliceStatement.stream()
                .filter(e -> e.transactionId().equals(result.transaction().transactionId()))
                .mapToLong(LedgerEntry::signedAmountMinorUnits).sum();
        long netCredit = bobStatement.stream()
                .filter(e -> e.transactionId().equals(result.transaction().transactionId()))
                .mapToLong(LedgerEntry::signedAmountMinorUnits).sum();

        assertEquals(-2500L, netDebit, "source leg must be a debit of exactly the transferred amount");
        assertEquals(2500L, netCredit, "destination leg must be a credit of exactly the transferred amount");
        assertEquals(0L, netDebit + netCredit, "double-entry invariant: debits and credits must net to zero");
    }

    @Test
    void duplicateIdempotencyKeyIsNotAppliedTwice() {
        String key = UUID.randomUUID().toString();
        TransferRequest request = new TransferRequest(alice.accountId(), bob.accountId(), dollars("10.00"), key, "coffee");

        TransferResult first = wallet.transfer(request);
        TransferResult second = wallet.transfer(request);

        assertFalse(first.replayedFromIdempotencyCache());
        assertTrue(second.replayedFromIdempotencyCache());
        assertEquals(first.transaction().transactionId(), second.transaction().transactionId());
        assertEquals(dollars("90.00"), wallet.getBalance(alice.accountId()));
        assertEquals(dollars("10.00"), wallet.getBalance(bob.accountId()));
    }

    @Test
    void transferBeyondBalanceIsRejectedAndLeavesBalancesUnchanged() {
        assertThrows(InsufficientFundsException.class, () -> wallet.transfer(new TransferRequest(
                alice.accountId(), bob.accountId(), dollars("1000.00"), UUID.randomUUID().toString(), "too much")));

        assertEquals(dollars("100.00"), wallet.getBalance(alice.accountId()));
        assertEquals(dollars("0.00"), wallet.getBalance(bob.accountId()));
    }

    @Test
    void transferExceedingDailyLimitIsRejected() {
        wallet.deposit(bob.accountId(), dollars("60000.00"), UUID.randomUUID().toString(), "fund for limit test");

        assertThrows(TransferLimitExceededException.class, () -> wallet.transfer(new TransferRequest(
                bob.accountId(), alice.accountId(), dollars("51000.00"), UUID.randomUUID().toString(), "over limit")));
    }

    @Test
    void debitsAreBlockedOnFrozenAccountButCreditsStillLand() {
        alice.freeze();

        assertThrows(AccountNotActiveException.class, () -> wallet.transfer(new TransferRequest(
                alice.accountId(), bob.accountId(), dollars("1.00"), UUID.randomUUID().toString(), "blocked")));

        wallet.deposit(alice.accountId(), dollars("5.00"), UUID.randomUUID().toString(), "incoming while frozen");
        assertEquals(dollars("105.00"), wallet.getBalance(alice.accountId()));
    }

    @Test
    void selfTransferIsRejected() {
        assertThrows(InvalidRequestException.class, () -> wallet.transfer(new TransferRequest(
                alice.accountId(), alice.accountId(), dollars("1.00"), UUID.randomUUID().toString(), "self")));
    }

    @Test
    void reversalUndoesACompletedTransferWithoutMutatingTheOriginal() {
        TransferResult original = wallet.transfer(new TransferRequest(alice.accountId(), bob.accountId(),
                dollars("20.00"), UUID.randomUUID().toString(), "oops"));

        TransferResult reversal = wallet.reverse(original.transaction().transactionId(), UUID.randomUUID().toString());

        assertEquals(dollars("100.00"), wallet.getBalance(alice.accountId()));
        assertEquals(dollars("0.00"), wallet.getBalance(bob.accountId()));
        assertEquals(original.transaction().transactionId(), reversal.transaction().reversalOfTransactionId().orElseThrow());
    }

    @Test
    void reversingTheSameTransactionTwiceIsRejectedOnTheSecondAttempt() {
        TransferResult original = wallet.transfer(new TransferRequest(alice.accountId(), bob.accountId(),
                dollars("15.00"), UUID.randomUUID().toString(), "once"));

        wallet.reverse(original.transaction().transactionId(), UUID.randomUUID().toString());

        assertThrows(IllegalTransactionStateException.class, () ->
                wallet.reverse(original.transaction().transactionId(), UUID.randomUUID().toString()));
    }

    @Test
    void reversingWithTheSameIdempotencyKeyTwiceIsSafelyDeduped() {
        TransferResult original = wallet.transfer(new TransferRequest(alice.accountId(), bob.accountId(),
                dollars("15.00"), UUID.randomUUID().toString(), "once"));
        String reversalKey = UUID.randomUUID().toString();

        TransferResult firstReversal = wallet.reverse(original.transaction().transactionId(), reversalKey);
        TransferResult secondReversal = wallet.reverse(original.transaction().transactionId(), reversalKey);

        assertEquals(firstReversal.transaction().transactionId(), secondReversal.transaction().transactionId());
        assertTrue(secondReversal.replayedFromIdempotencyCache());
        // Balance must reflect exactly ONE reversal, not two.
        assertEquals(dollars("100.00"), wallet.getBalance(alice.accountId()));
    }

    @Test
    void depositAndWithdrawalRoundTripRestoresBalance() {
        wallet.withdraw(alice.accountId(), dollars("50.00"), UUID.randomUUID().toString(), "cash out");
        assertEquals(dollars("50.00"), wallet.getBalance(alice.accountId()));

        wallet.deposit(alice.accountId(), dollars("50.00"), UUID.randomUUID().toString(), "cash back in");
        assertEquals(dollars("100.00"), wallet.getBalance(alice.accountId()));
    }
}
