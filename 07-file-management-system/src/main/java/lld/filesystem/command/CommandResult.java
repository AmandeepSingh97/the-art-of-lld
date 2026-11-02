package lld.filesystem.command;

/**
 * Immutable result of executing one {@link Command}. Chosen over throwing exceptions across the
 * command boundary so that a {@link Shell} (and tests) can treat "command failed" as ordinary
 * data rather than control flow -- useful because, unlike a library API, a shell's contract is
 * "never throw out of execute(), always report".
 */
public final class CommandResult {

    private final boolean success;
    private final String output;
    private final String errorMessage;

    private CommandResult(boolean success, String output, String errorMessage) {
        this.success = success;
        this.output = output;
        this.errorMessage = errorMessage;
    }

    public static CommandResult ok(String output) {
        return new CommandResult(true, output, null);
    }

    public static CommandResult ok() {
        return new CommandResult(true, "", null);
    }

    public static CommandResult failure(String errorMessage) {
        return new CommandResult(false, null, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getOutput() {
        return output;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        return success ? output : "error: " + errorMessage;
    }
}
