package lld.splitwise.split;

import lld.splitwise.model.SplitType;

/**
 * Simple factory selecting the correct {@link SplitStrategy} for a
 * {@link SplitType}. Kept intentionally trivial (no DI framework, no
 * reflection/registry) because the set of split types is small and changes
 * rarely — introducing a generic plugin registry here would be
 * over-engineering for the problem size (see STAFF_LEVEL_DEEP_DIVE.md).
 */
public final class SplitStrategyFactory {

    private static final SplitStrategy EQUAL = new EqualSplitStrategy();
    private static final SplitStrategy EXACT = new ExactSplitStrategy();
    private static final SplitStrategy PERCENT = new PercentSplitStrategy();

    private SplitStrategyFactory() {
    }

    public static SplitStrategy forType(SplitType splitType) {
        return switch (splitType) {
            case EQUAL -> EQUAL;
            case EXACT -> EXACT;
            case PERCENT -> PERCENT;
        };
    }
}
