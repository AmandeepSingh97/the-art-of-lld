package lld.filesystem;

import lld.filesystem.command.CommandResult;
import lld.filesystem.command.Shell;

/**
 * Runnable demonstration of the in-memory file system: builds a small tree using the Command
 * pattern (the same path a real CLI/REPL would use), then exercises mkdir, touch, write, cat,
 * ls, cp -r, mv, and rm -r, printing each step.
 */
public final class Demo {

    public static void main(String[] args) {
        FileSystem fileSystem = new FileSystem();
        Session session = fileSystem.newSession();
        Shell shell = Shell.withDefaultCommands(fileSystem, session);

        run(shell, "pwd");
        run(shell, "mkdir -p /home/user/docs");
        run(shell, "mkdir -p /home/user/pictures");
        run(shell, "cd /home/user/docs");
        run(shell, "pwd");
        run(shell, "touch notes.txt");
        run(shell, "write notes.txt \"first line\"");
        run(shell, "write -a notes.txt \" second line\"");
        run(shell, "cat notes.txt");
        run(shell, "ls /home/user");

        run(shell, "cp -r /home/user/docs /home/user/docs_backup");
        run(shell, "ls /home/user");
        run(shell, "cat /home/user/docs_backup/notes.txt");

        run(shell, "mv /home/user/docs_backup /home/user/pictures/docs_backup");
        run(shell, "ls /home/user");
        run(shell, "ls /home/user/pictures");

        // Cycle prevention: cannot move a directory into its own descendant.
        run(shell, "mv /home/user /home/user/docs");

        run(shell, "rm -r /home/user/pictures/docs_backup");
        run(shell, "ls /home/user/pictures");
    }

    private static void run(Shell shell, String line) {
        CommandResult result = shell.execute(line);
        System.out.println("$ " + line);
        if (result.isSuccess()) {
            if (!result.getOutput().isEmpty()) {
                System.out.println(result.getOutput());
            }
        } else {
            System.out.println(result);
        }
    }
}
