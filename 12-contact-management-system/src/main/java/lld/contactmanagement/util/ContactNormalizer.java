package lld.contactmanagement.util;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Centralizes the normalization rules used across the contact management
 * domain. Normalization is what makes hash-based lookups (phone/email) and
 * prefix search (name) correct and index-friendly: two contact points that a
 * human would consider "the same" must normalize to an identical key.
 *
 * <p>This class intentionally does NOT depend on a full telephony library
 * (e.g. libphonenumber). It implements a pragmatic subset (digits + optional
 * leading '+') sufficient for an interview-scope LLD. The README documents
 * this as a named simplification with a clear production upgrade path.
 */
public final class ContactNormalizer {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private ContactNormalizer() {
        // utility class - no instances
    }

    /**
     * Normalizes a phone number to a digits-only canonical form, preserving a
     * single leading '+' if present. Punctuation, spaces, and parentheses are
     * stripped. This is what duplicate detection and the phone hash index key
     * on - NOT the raw, user-facing formatting.
     *
     * @throws IllegalArgumentException if no digit remains after stripping
     */
    public static String normalizePhone(String raw) {
        Objects.requireNonNull(raw, "raw phone must not be null");
        String trimmed = raw.trim();
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            } else if (c == '+' && sb.isEmpty()) {
                sb.append(c);
            }
        }
        String normalized = sb.toString();
        if (normalized.isEmpty() || normalized.equals("+")) {
            throw new IllegalArgumentException(
                    "Phone number must contain at least one digit: " + raw);
        }
        return normalized;
    }

    /**
     * Normalizes an email address to lowercase, trimmed canonical form.
     * Unlike phone numbers, email normalization is lossless enough to be
     * used directly as the stored/display value (see {@code EmailAddress}).
     *
     * @throws IllegalArgumentException if the value does not look like an email
     */
    public static String normalizeEmail(String raw) {
        Objects.requireNonNull(raw, "raw email must not be null");
        String normalized = raw.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Not a valid email address: " + raw);
        }
        return normalized;
    }

    /**
     * Normalizes a display name (or a single name token) for indexing and
     * comparison: trims and lowercases. Collapses internal whitespace runs to
     * a single space so "  John   Smith " and "John Smith" index identically.
     */
    public static String normalizeName(String raw) {
        Objects.requireNonNull(raw, "raw name must not be null");
        return raw.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /**
     * Splits a normalized display name into tokens (first name, last name,
     * middle names, ...) for per-token prefix indexing, so a search for
     * "smi" matches "John Smith" even though the display name starts with
     * "john".
     */
    public static String[] nameTokens(String displayName) {
        String normalized = normalizeName(displayName);
        if (normalized.isEmpty()) {
            return new String[0];
        }
        return normalized.split(" ");
    }
}
