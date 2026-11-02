package lld.digitalwallet.exception;

/** Thrown when an account referenced by id does not exist in the repository. */
public final class AccountNotFoundException extends WalletException {
    public AccountNotFoundException(String accountId) {
        super("Account not found: " + accountId);
    }
}
