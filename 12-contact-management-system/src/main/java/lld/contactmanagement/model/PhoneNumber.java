package lld.contactmanagement.model;

import lld.contactmanagement.util.ContactNormalizer;

import java.util.Objects;

/**
 * Value object representing a phone number.
 *
 * <p>Design note: unlike {@link EmailAddress}, we deliberately keep
 * {@code rawValue} (the user's original formatting, e.g. {@code "(555) 123-4567"})
 * separate from the normalized digits-only form used for indexing and
 * duplicate detection. Phone formatting is locale/display-sensitive in a way
 * email is not, so eagerly overwriting it would lose information the UI
 * layer needs. {@link #normalized()} is computed on demand (cheap - short
 * strings) rather than cached, keeping this record trivially correct.
 *
 * <p>Equality is structural on {@code (rawValue, type)}, matching normal
 * value-object semantics for two independently-entered records. Callers that
 * need "same phone number regardless of formatting" semantics (e.g. duplicate
 * detection, hash indexing) must explicitly compare {@link #normalized()},
 * not rely on {@code equals}.
 */
public record PhoneNumber(String rawValue, PhoneType type) {

    public PhoneNumber {
        Objects.requireNonNull(rawValue, "rawValue must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (rawValue.isBlank()) {
            throw new IllegalArgumentException("rawValue must not be blank");
        }
        // Fail fast at construction time rather than at index/search time.
        ContactNormalizer.normalizePhone(rawValue);
    }

    /** Canonical digits-only form, used for hashing/indexing/dedup. */
    public String normalized() {
        return ContactNormalizer.normalizePhone(rawValue);
    }

    public static PhoneNumber of(String rawValue, PhoneType type) {
        return new PhoneNumber(rawValue, type);
    }
}
