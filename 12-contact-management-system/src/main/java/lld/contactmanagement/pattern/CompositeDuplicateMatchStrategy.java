package lld.contactmanagement.pattern;

import lld.contactmanagement.model.Contact;

import java.util.List;

/**
 * Combines multiple {@link DuplicateMatchStrategy} instances under one of
 * two modes:
 * <ul>
 *   <li>{@link Mode#ANY} - duplicate if <em>any</em> sub-strategy agrees
 *       (maximizes recall; used by default).</li>
 *   <li>{@link Mode#ALL} - duplicate only if <em>every</em> sub-strategy
 *       agrees (maximizes precision; useful for an auto-merge policy that
 *       must not act on a false positive).</li>
 * </ul>
 */
public final class CompositeDuplicateMatchStrategy implements DuplicateMatchStrategy {

    public enum Mode { ANY, ALL }

    private final List<DuplicateMatchStrategy> strategies;
    private final Mode mode;

    public CompositeDuplicateMatchStrategy(List<DuplicateMatchStrategy> strategies, Mode mode) {
        this.strategies = List.copyOf(strategies);
        this.mode = mode;
    }

    @Override
    public boolean isDuplicate(Contact a, Contact b) {
        return switch (mode) {
            case ANY -> strategies.stream().anyMatch(s -> s.isDuplicate(a, b));
            case ALL -> !strategies.isEmpty() && strategies.stream().allMatch(s -> s.isDuplicate(a, b));
        };
    }
}
