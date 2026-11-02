package lld.filesystem.command;

import lld.filesystem.FileSystem;
import lld.filesystem.Session;
import lld.filesystem.exception.FileSystemException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokenizes a command line and dispatches it to the matching {@link Command}, converting any
 * {@link FileSystemException} into a failed {@link CommandResult} so callers never need a
 * try/catch -- a real shell never crashes because one command failed.
 *
 * <p>This class intentionally does not know anything about mkdir/cp/mv/etc directly: every verb
 * is registered via {@link #register(Command)}, so the dispatcher is open for new commands
 * without modification (Open/Closed Principle) and is independently testable from the commands
 * themselves.</p>
 */
public final class Shell {

    // Splits on whitespace but keeps "quoted strings" together, so `write /a/b.txt "hello world"`
    // treats "hello world" as a single argument instead of two.
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"([^\"]*)\"|(\\S+)");

    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final FileSystem fileSystem;
    private final Session session;

    public Shell(FileSystem fileSystem, Session session) {
        this.fileSystem = fileSystem;
        this.session = session;
    }

    /** Creates a {@link Shell} with every built-in command already registered. */
    public static Shell withDefaultCommands(FileSystem fileSystem, Session session) {
        Shell shell = new Shell(fileSystem, session);
        shell.register(new MkdirCommand());
        shell.register(new CdCommand());
        shell.register(new PwdCommand());
        shell.register(new LsCommand());
        shell.register(new TouchCommand());
        shell.register(new CatCommand());
        shell.register(new WriteCommand());
        shell.register(new CpCommand());
        shell.register(new MvCommand());
        shell.register(new RmCommand());
        return shell;
    }

    public void register(Command command) {
        commands.put(command.name(), command);
    }

    public CommandResult execute(String commandLine) {
        List<String> tokens = tokenize(commandLine);
        if (tokens.isEmpty()) {
            return CommandResult.ok();
        }
        String verb = tokens.get(0);
        Command command = commands.get(verb);
        if (command == null) {
            return CommandResult.failure(verb + ": command not found");
        }
        String[] args = tokens.subList(1, tokens.size()).toArray(new String[0]);
        try {
            return command.execute(fileSystem, session, args);
        } catch (FileSystemException e) {
            return CommandResult.failure(e.getMessage());
        }
    }

    private static List<String> tokenize(String commandLine) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(commandLine.trim());
        while (matcher.find()) {
            tokens.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        }
        return tokens;
    }
}
