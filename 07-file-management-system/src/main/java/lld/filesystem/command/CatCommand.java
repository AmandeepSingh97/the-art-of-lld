package lld.filesystem.command;

import lld.filesystem.FileSystem;
import lld.filesystem.Session;

/** {@code cat <path>}: prints a file's content. */
public final class CatCommand implements Command {

    @Override
    public String name() {
        return "cat";
    }

    @Override
    public CommandResult execute(FileSystem fileSystem, Session session, String[] args) {
        if (args.length == 0) {
            return CommandResult.failure("cat: missing operand");
        }
        return CommandResult.ok(fileSystem.cat(session, args[0]));
    }
}
