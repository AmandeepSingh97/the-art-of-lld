package lld.snakeandladder.dice;

import java.util.List;
import java.util.Objects;

/**
 * Composes multiple {@link Dice} strategies and sums their individual
 * rolls into a single combined roll — e.g. "roll two six-sided dice",
 * which is itself a common Snakes and Ladders house rule.
 *
 * <p>This is a thin Composite over the {@code Dice} strategy interface:
 * because {@code CompositeDice} itself implements {@code Dice}, it can be
 * nested arbitrarily (e.g. a composite of a composite), though in practice
 * one level is all this domain ever needs.</p>
 */
public final class CompositeDice implements Dice {

    private final List<Dice> components;

    public CompositeDice(List<Dice> components) {
        this.components = List.copyOf(Objects.requireNonNull(components, "components"));
        if (this.components.isEmpty()) {
            throw new IllegalArgumentException("CompositeDice requires at least one component die");
        }
    }

    public static CompositeDice twoStandardDice() {
        return new CompositeDice(List.of(SingleDie.standardSixSided(), SingleDie.standardSixSided()));
    }

    @Override
    public int roll() {
        int total = 0;
        for (Dice die : components) {
            total += die.roll();
        }
        return total;
    }

    @Override
    public int minPossibleRoll() {
        return components.stream().mapToInt(Dice::minPossibleRoll).sum();
    }

    @Override
    public int maxPossibleRoll() {
        return components.stream().mapToInt(Dice::maxPossibleRoll).sum();
    }
}
