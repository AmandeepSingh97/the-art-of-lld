package lld.slackchat.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A single immutable audit-trail entry recorded every time a message is edited. Keeping the
 * full edit history (rather than just the latest content) is what makes edits auditable --
 * a requirement explicitly called out for this problem.
 */
public record MessageEdit(String previousContent, Instant editedAt) {

    public MessageEdit {
        Objects.requireNonNull(previousContent, "previousContent");
        Objects.requireNonNull(editedAt, "editedAt");
    }
}
