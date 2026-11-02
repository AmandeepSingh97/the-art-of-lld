package lld.contactmanagement.model;

import lld.contactmanagement.util.ContactNormalizer;

import java.util.Objects;

/**
 * Value object representing an email address.
 *
 * <p>Unlike {@link PhoneNumber}, email normalization (trim + lowercase) is
 * lossless and safe to apply as the single canonical stored value - email
 * addresses are conventionally case-insensitive in practice, and preserving
 * "original casing" has no real display benefit. This means {@code equals}/
 * {@code hashCode} (record-derived, structural on {@code value, type}) are
 * sufficient for duplicate detection without a separate normalization step.
 */
public record EmailAddress(String value, EmailType type) {

    public EmailAddress {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(type, "type must not be null");
        value = ContactNormalizer.normalizeEmail(value);
    }

    public static EmailAddress of(String value, EmailType type) {
        return new EmailAddress(value, type);
    }
}
