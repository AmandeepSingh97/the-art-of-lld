package lld.digitalwallet.model;

/**
 * Lightweight stand-in for a real KYC/AML tiering system. Each tier caps how
 * much value can move out of an account in a rolling day, mirroring how
 * Paytm/PayPal/Venmo throttle unverified wallets. A production system would
 * back this with a dedicated risk/compliance service (velocity checks,
 * sanctions screening, per-counterparty limits) - this enum only captures
 * the policy knob the wallet domain needs to enforce the limit invariant.
 */
public enum KycTier {
    TIER_0_UNVERIFIED(10_000_00L),   // e.g. $10,000.00/day equivalent in minor units
    TIER_1_BASIC(50_000_00L),
    TIER_2_FULL(1_000_000_00L);

    private final long dailyTransferLimitMinorUnits;

    KycTier(long dailyTransferLimitMinorUnits) {
        this.dailyTransferLimitMinorUnits = dailyTransferLimitMinorUnits;
    }

    public long dailyTransferLimitMinorUnits() {
        return dailyTransferLimitMinorUnits;
    }
}
