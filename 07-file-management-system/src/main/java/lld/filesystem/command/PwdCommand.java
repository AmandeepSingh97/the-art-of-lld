package lld.filesystem.command;

import lld.filesystem.FileSystem;
import lld.filesystem.Session;

/** {@code pwd}: prints the session's current working directory as an absolute path. */
public final class PwdCommand implements Command {

    @Override
    public String name() {
        return "pwd";
    }

    @Override
    public CommandResult execute(FileSystem fileSystem, Session session, String[] args) {
        return CommandResult.ok(fileSystem.pwd(session));
    }
}
