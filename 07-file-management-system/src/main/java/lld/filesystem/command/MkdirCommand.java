package lld.filesystem.command;

import lld.filesystem.FileSystem;
import lld.filesystem.Session;

/** {@code mkdir [-p] <path>}: creates a directory; {@code -p} also creates missing parents. */
public final class MkdirCommand implements Command {

    @Override
    public String name() {
        return "mkdir";
    }

    @Override
    public CommandResult execute(FileSystem fileSystem, Session session, String[] args) {
        if (args.length == 0) {
            return CommandResult.failure("mkdir: missing operand");
        }
        boolean createParents = false;
        String path = null;
        for (String arg : args) {
            if (arg.equals("-p")) {
                createParents = true;
            } else {
                path = arg;
            }
        }
        if (path == null) {
            return CommandResult.failure("mkdir: missing operand");
        }
        fileSystem.mkdir(session, path, createParents);
        return CommandResult.ok();
    }
}
