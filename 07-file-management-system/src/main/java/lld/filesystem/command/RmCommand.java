package lld.filesystem.command;

import lld.filesystem.FileSystem;
import lld.filesystem.Session;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code rm [-r] [-f] <path>}: removes a file, or (with {@code -r}) a directory and everything
 * under it. {@code -f} suppresses the "not found" error (a no-op instead), mirroring POSIX.
 */
public final class RmCommand implements Command {

    @Override
    public String name() {
        return "rm";
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
                case "-rf", "-fr" -> {
                    recursive = true;
                    force = true;
                }
                default -> positional.add(arg);
            }
        }
        if (positional.size() != 1) {
            return CommandResult.failure("rm: usage: rm [-r] [-f] <path>");
        }
        fileSystem.rm(session, positional.get(0), recursive, force);
        return CommandResult.ok();
    }
}
