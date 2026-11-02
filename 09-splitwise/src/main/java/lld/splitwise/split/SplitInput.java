package lld.splitwise.split;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lld.splitwise.model.Money;

/**
 * Sealed hierarchy of inputs that parameterize a {@link SplitStrategy}.
 *
 * <p>Using a sealed interface (Java 17+) instead of a single "god" DTO with
 * nullable fields for every split type means:
 * <ul>
 *   <li>Each split type only carries the data it actually needs (no
 *       {@code nullable exactAmounts} field on an equal split, etc.).</li>
 *   <li>The compiler enforces exhaustiveness: {@link SplitStrategyFactory} and
 *       any {@code switch} over {@code SplitInput} must handle every case or
 *       fail to compile when a new split type is added.</li>
 * </ul>
 */
public sealed interface SplitInput {

    /** Split the total equally among the given participants. */
    record EqualSplitInput(List<String> participantUserIds) implements SplitInput {
        public EqualSplitInput {
            participantUserIds = List.copyOf(Objects.requireNonNull(participantUserIds, "participantUserIds"));
            if (participantUserIds.isEmpty()) {
                throw new IllegalArgumentException("EqualSplitInput requires at least one participant");
            }
        }
    }

    /** Split the total using caller-specified exact amounts per participant. */
    record ExactSplitInput(Map<String, Money> exactAmountsByUserId) implements SplitInput {
        public ExactSplitInput {
            exactAmountsByUserId = Map.copyOf(Objects.requireNonNull(exactAmountsByUserId, "exactAmountsByUserId"));
            if (exactAmountsByUserId.isEmpty()) {
                throw new IllegalArgumentException("ExactSplitInput requires at least one participant");
            }
        }
    }

    /** Split the total using caller-specified percentages (must sum to 100) per participant. */
    record PercentSplitInput(Map<String, BigDecimal> percentageByUserId) implements SplitInput {
        public PercentSplitInput {
            percentageByUserId = Map.copyOf(Objects.requireNonNull(percentageByUserId, "percentageByUserId"));
            if (percentageByUserId.isEmpty()) {
                throw new IllegalArgumentException("PercentSplitInput requires at least one participant");
            }
        }
    }
}
