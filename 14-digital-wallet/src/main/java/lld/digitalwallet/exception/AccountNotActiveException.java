package lld.digitalwallet.exception;

import lld.digitalwallet.model.AccountStatus;

/** Thrown when an operation is attempted against a FROZEN or CLOSED account that forbids it. */
public final class AccountNotActiveException extends WalletException {
    public AccountNotActiveException(String accountId, AccountStatus status) {
        super("Account " + accountId + " is not active (status=" + status + ")");
    }
}
