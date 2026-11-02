package lld.filesystem.command;

import lld.filesystem.FileSystem;
import lld.filesystem.Session;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code mv [-f] <source> <dest>}: moves/renames a file or directory. Works for both files and
 * directories uniformly (unlike {@code cp}, no {@code -r} flag is needed: a move never copies
 * content, it only re-parents a node, so it is O(1) in the size of the subtree being moved).
 */
public final class MvCommand implements Command {

    @Override
    public String name() {
        return "mv";
    }

    @Override
    public CommandResult execute(FileSystem fileSystem, Session session, String[] args) {
        boolean force = false;
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("-f")) {
                force = true;
            } else {
                positional.add(arg);
            }
        }
        if (positional.size() != 2) {
            return CommandResult.failure("mv: usage: mv [-f] <source> <dest>");
        }
        fileSystem.mv(session, positional.get(0), positional.get(1), force);
        return CommandResult.ok();
    }
}
