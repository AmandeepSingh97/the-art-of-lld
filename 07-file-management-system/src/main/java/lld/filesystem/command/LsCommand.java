package lld.filesystem.command;

import lld.filesystem.FileSystem;
import lld.filesystem.FileSystemEntry;
import lld.filesystem.Session;

import java.util.List;
import java.util.stream.Collectors;

/** {@code ls [path]}: lists the immediate children of a directory (defaults to cwd). */
public final class LsCommand implements Command {

    @Override
    public String name() {
        return "ls";
    }

    @Override
    public CommandResult execute(FileSystem fileSystem, Session session, String[] args) {
        String path = args.length == 0 ? "." : args[0];
        List<FileSystemEntry> entries = fileSystem.ls(session, path);
        String output = entries.stream()
                .map(e -> e.isDirectory() ? e.getName() + "/" : e.getName())
                .collect(Collectors.joining("\n"));
        return CommandResult.ok(output);
    }
}
