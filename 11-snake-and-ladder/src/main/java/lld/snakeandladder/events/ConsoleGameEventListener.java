package lld.snakeandladder.events;

/**
 * A simple reference {@link GameEventListener} that prints a human-readable
 * line per event to standard output. Used by the {@code Demo} class; not
 * intended for production logging (see {@code README.md §17 Production
 * Evolution} for how this would be replaced by structured logging).
 *
 * <p>Uses an {@code instanceof} chain rather than a pattern-matching
 * {@code switch} expression because pattern matching for {@code switch}
 * only became a stable (non-preview) language feature in Java 21; this
 * module targets Java 17+ as a baseline, so the chain keeps the code
 * portable to any compliant Java 17 compiler.</p>
 */
public final class ConsoleGameEventListener implements GameEventListener {

    @Override
    public void onEvent(GameEvent event) {
        if (event instanceof PlayerMovedEvent moved) {
            System.out.printf("%s rolled %d: %d -> %d%n",
                    moved.player().name(), moved.rolled(), moved.fromPosition(), moved.toPosition());
        } else if (event instanceof SnakeBiteEvent bite) {
            System.out.printf("  %s bitten by a snake: %d -> %d%n",
                    bite.player().name(), bite.fromCell(), bite.toCell());
        } else if (event instanceof LadderClimbEvent climb) {
            System.out.printf("  %s climbs a ladder: %d -> %d%n",
                    climb.player().name(), climb.fromCell(), climb.toCell());
        } else if (event instanceof TurnSkippedEvent skipped) {
            System.out.printf("%s rolled %d at %d: %s (turn skipped)%n",
                    skipped.player().name(), skipped.rolled(), skipped.atPosition(), skipped.reason());
        } else if (event instanceof PlayerWonEvent won) {
            System.out.printf("*** %s WINS after %d turns! ***%n", won.player().name(), won.turnsTaken());
        }
    }
}
