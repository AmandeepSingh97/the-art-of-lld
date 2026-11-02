package lld.filesystem.command;

import lld.filesystem.FileSystem;
import lld.filesystem.Session;

/** {@code touch <path>}: creates an empty file, or is a no-op if it already exists. */
public final class TouchCommand implements Command {

    @Override
    public String name() {
        return "touch";
    }

    @Override
    public CommandResult execute(FileSystem fileSystem, Session session, String[] args) {
        if (args.length == 0) {
            return CommandResult.failure("touch: missing operand");
        }
        fileSystem.touch(session, args[0]);
        return CommandResult.ok();
    }
}
