package lld.snakeandladder;

import lld.snakeandladder.dice.CompositeDice;
import lld.snakeandladder.dice.Dice;
import lld.snakeandladder.dice.FixedSequenceDice;
import lld.snakeandladder.dice.SingleDie;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link Dice} strategy hierarchy: range guarantees, seeded
 * determinism, composite summing, and scripted sequences.
 */
class DiceTest {

    @RepeatedTest(50)
    void singleDieAlwaysWithinDeclaredRange() {
        Dice dice = SingleDie.standardSixSided();
        int roll = dice.roll();
        assertTrue(roll >= 1 && roll <= 6, "roll " + roll + " outside [1,6]");
        assertEquals(1, dice.minPossibleRoll());
        assertEquals(6, dice.maxPossibleRoll());
    }

    @Test
    void seededDieProducesIdenticalSequenceForIdenticalSeed() {
        SingleDie dieA = SingleDie.seeded(6, 1234L);
        SingleDie dieB = SingleDie.seeded(6, 1234L);

        for (int i = 0; i < 20; i++) {
            assertEquals(dieA.roll(), dieB.roll(), "Sequence diverged at roll " + i);
        }
    }

    @Test
    void seededDiceWithDifferentSeedsTypicallyDiverge() {
        SingleDie dieA = SingleDie.seeded(6, 1L);
        SingleDie dieB = SingleDie.seeded(6, 2L);

        boolean sawDifference = false;
        for (int i = 0; i < 20; i++) {
            if (dieA.roll() != dieB.roll()) {
                sawDifference = true;
                break;
            }
        }
        assertTrue(sawDifference, "Two different seeds produced an identical 20-roll sequence");
    }

    @Test
    void compositeDiceSumsComponentRolls() {
        Dice composite = new CompositeDice(java.util.List.of(
                FixedSequenceDice.of(3), FixedSequenceDice.of(4)));
        assertEquals(7, composite.roll());
        assertEquals(7, composite.minPossibleRoll(), "Both components are fixed, so min equals the sum");
        assertEquals(7, composite.maxPossibleRoll(), "Both components are fixed, so max equals the sum");
    }

    @Test
    void compositeDiceRangeIsSumOfComponentRanges() {
        Dice composite = new CompositeDice(java.util.List.of(
                SingleDie.standardSixSided(), SingleDie.standardSixSided()));
        assertEquals(2, composite.minPossibleRoll());
        assertEquals(12, composite.maxPossibleRoll());
    }

    @Test
    void fixedSequenceDiceReplaysExactlyThenCycles() {
        FixedSequenceDice dice = FixedSequenceDice.of(6, 6, 3);
        assertEquals(6, dice.roll());
        assertEquals(6, dice.roll());
        assertEquals(3, dice.roll());
        assertEquals(6, dice.roll(), "Sequence should cycle back to the first scripted roll");
    }

    @Test
    void fixedSequenceDiceRejectsEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> new FixedSequenceDice(java.util.List.of()));
    }

    @Test
    void fixedSequenceDiceRejectsNonPositiveRoll() {
        assertThrows(IllegalArgumentException.class, () -> FixedSequenceDice.of(0, 3));
    }

    @Test
    void singleDieRejectsFewerThanTwoFaces() {
        assertThrows(IllegalArgumentException.class, () -> new SingleDie(1, new java.util.Random()));
    }
}
