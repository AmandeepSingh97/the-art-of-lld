package lld.clouddrive.service;

import lld.clouddrive.exception.InvalidOperationException;
import lld.clouddrive.model.File;
import lld.clouddrive.model.Folder;
import lld.clouddrive.util.IdGenerator;

import java.time.Clock;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Centralizes construction and validation of {@link Folder} and {@link File} nodes (Factory
 * pattern). Every caller that needs a new node -- {@code DriveService.createFolder},
 * {@code createFile}, and {@code TrashService.restore} re-parenting logic -- goes through here,
 * so naming rules (length, forbidden characters) and id/timestamp generation live in exactly one
 * place instead of being duplicated/drifting across call sites.
 */
public final class DriveNodeFactory {

    private static final Pattern VALID_NAME = Pattern.compile("^[^/\\\\]{1,255}$");

    private final IdGenerator idGenerator;
    private final Clock clock;

    public DriveNodeFactory(IdGenerator idGenerator, Clock clock) {
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public static void validateName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new InvalidOperationException(
                    "Invalid name '" + name + "': must be 1-255 characters and contain no '/' or '\\\\'");
        }
    }

    public Folder newFolder(String name, String ownerId, Folder parent) {
        validateName(name);
        Instant now = clock.instant();
        return new Folder(idGenerator.next(), name, ownerId, parent, now);
    }

    public File newFile(String name, String ownerId, Folder parent) {
        validateName(name);
        Instant now = clock.instant();
        return new File(idGenerator.next(), name, ownerId, parent, now);
    }
}
