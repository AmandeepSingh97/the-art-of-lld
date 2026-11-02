package lld.filesystem.command;

import lld.filesystem.FileSystem;
import lld.filesystem.Session;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code cp [-r] [-f] <source> <dest>}: copies a file, or (with {@code -r}) recursively copies a
 * directory subtree. {@code -f} allows overwriting an existing destination of the same kind.
 */
public final class CpCommand implements Command {

    @Override
    public String name() {
        return "cp";
    }

    @Override
    public CommandResult execute(FileSystem fileSystem, Session session, String[] args) {
        boolean recursive = false;
        boolean force = false;
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            switch (arg) {
                case "-r", "-R" -> recursive = true;
                case "-f" -> force = true;
                default -> positional.add(arg);
            }
        }
        if (positional.size() != 2) {
            return CommandResult.failure("cp: usage: cp [-r] [-f] <source> <dest>");
        }
        fileSystem.cp(session, positional.get(0), positional.get(1), recursive, force);
        return CommandResult.ok();
    }
}
