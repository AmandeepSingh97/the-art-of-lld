package lld.digitalwallet.model;

/**
 * Lifecycle status of a {@link Account}. A frozen or closed account rejects
 * all new debits/credits; this is the hook KYC/compliance/fraud systems use
 * to halt an account without deleting its history.
 */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
