package lld.filesystem.command;

import lld.filesystem.FileSystem;
import lld.filesystem.Session;

/**
 * {@code write [-a] <path> <content...>}: writes (or, with {@code -a}, appends) text content to
 * a file, creating it if it does not exist. Remaining arguments after the path are joined with a
 * single space to form the content, so simple multi-word content does not need manual quoting
 * when driven from {@link lld.filesystem.command.Shell#execute(String)}.
 */
public final class WriteCommand implements Command {

    @Override
    public String name() {
        return "write";
    }

    @Override
    public CommandResult execute(FileSystem fileSystem, Session session, String[] args) {
        boolean append = false;
        int index = 0;
        if (args.length > 0 && args[0].equals("-a")) {
            append = true;
            index = 1;
        }
        if (args.length <= index + 1) {
            return CommandResult.failure("write: usage: write [-a] <path> <content>");
        }
        String path = args[index];
        StringBuilder content = new StringBuilder();
        for (int i = index + 1; i < args.length; i++) {
            if (i > index + 1) {
                content.append(' ');
            }
            content.append(args[i]);
        }
        fileSystem.write(session, path, content.toString(), append);
        return CommandResult.ok();
    }
}
