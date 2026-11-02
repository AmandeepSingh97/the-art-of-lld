package lld.digitalwallet.exception;

import lld.digitalwallet.model.KycTier;

/** Thrown when a debit would exceed the account's KYC-tier daily transfer limit. */
public final class TransferLimitExceededException extends WalletException {
    public TransferLimitExceededException(String accountId, KycTier tier, long projectedMinorUnits) {
        super("Account " + accountId + " (tier=" + tier + ") would exceed its daily limit of "
                + tier.dailyTransferLimitMinorUnits() + " minor units; projected total=" + projectedMinorUnits);
    }
}
