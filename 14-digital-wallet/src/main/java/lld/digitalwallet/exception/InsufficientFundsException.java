package lld.digitalwallet.exception;

import lld.digitalwallet.model.Money;

/** Thrown when a debit would drive an account's balance negative. This is the exception that enforces
 *  the "no overdraft" invariant at the point of mutation, inside the lock, so it can never be bypassed
 *  by a racing thread (see Account#applyDebit and the concurrency section of the README). */
public final class InsufficientFundsException extends WalletException {
    public InsufficientFundsException(String accountId, Money requested, Money available) {
        super("Account " + accountId + " has insufficient funds: requested=" + requested + ", available=" + available);
    }
}
