package lld.contactmanagement.pattern;

import lld.contactmanagement.model.Contact;

import java.util.List;

/**
 * Strategy pattern for pluggable import/export wire formats. A different
 * implementation (e.g. a CSV codec) could be substituted without touching
 * {@code ImportExportService} or any domain code.
 *
 * <p>Group membership is handled out-of-band: {@code encode} takes already-
 * resolved group <em>names</em> (not ids, which are meaningless outside this
 * process), and {@code decode} returns the group names it found so the
 * caller ({@code ImportExportService}) can resolve/create {@code Group}
 * entities and attach their ids - keeping this class free of any repository
 * dependency.
 */
public interface ContactCodec {

    String encode(Contact contact, List<String> groupNames);

    /** Parses a single encoded record into a not-yet-built {@link Contact.Builder} plus group names. */
    DecodedContact decode(String text);

    /** Result of decoding one record: the contact fields, and the raw group names to resolve. */
    record DecodedContact(Contact.Builder builder, List<String> groupNames) {
    }
}
