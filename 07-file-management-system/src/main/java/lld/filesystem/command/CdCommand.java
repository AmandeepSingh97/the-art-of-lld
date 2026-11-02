package lld.filesystem.command;

import lld.filesystem.FileSystem;
import lld.filesystem.Session;

/** {@code cd <path>}: changes the session's current working directory. */
public final class CdCommand implements Command {

    @Override
    public String name() {
        return "cd";
    }

    @Override
    public CommandResult execute(FileSystem fileSystem, Session session, String[] args) {
        String path = args.length == 0 ? "/" : args[0];
        fileSystem.cd(session, path);
        return CommandResult.ok();
    }
}
