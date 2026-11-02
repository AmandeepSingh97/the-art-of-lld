package lld.snakeandladder;

import lld.snakeandladder.board.BoardBuilder;
import lld.snakeandladder.dice.SingleDie;
import lld.snakeandladder.engine.Game;
import lld.snakeandladder.events.ConsoleGameEventListener;
import lld.snakeandladder.model.Board;
import lld.snakeandladder.model.Player;
import lld.snakeandladder.rules.OvershootClampWinCondition;

/**
 * Runnable example wiring together every major component: a 10x10 board
 * with a handful of snakes and ladders, three players, a seeded die for
 * reproducible output, and a console listener for human-readable play-by-play.
 *
 * <p>Run with: {@code java -cp <classes-dir> lld.snakeandladder.Demo}</p>
 */
public final class Demo {

    public static void main(String[] args) {
        Board board = new BoardBuilder()
                .withSize(10)
                .addLadder(2, 23)
                .addLadder(8, 34)
                .addLadder(20, 77)
                .addLadder(49, 67)
                .addSnake(32, 10)
                .addSnake(62, 19)
                .addSnake(88, 24)
                .addSnake(95, 56)
                .build();

        Player alice = Player.of("Alice");
        Player bob = Player.of("Bob");
        Player carol = Player.of("Carol");

        Game game = Game.builder()
                .withBoard(board)
                .addPlayer(alice)
                .addPlayer(bob)
                .addPlayer(carol)
                .withDice(SingleDie.seeded(6, 42L))
                .withWinCondition(new OvershootClampWinCondition())
                .addListener(new ConsoleGameEventListener())
                .withMaxTurns(2_000)
                .build();

        Player winner = game.playUntilWinner();

        System.out.println();
        System.out.printf("Winner: %s after %d total turns.%n", winner.name(), game.turnsTaken());
    }

    private Demo() {
    }
}
