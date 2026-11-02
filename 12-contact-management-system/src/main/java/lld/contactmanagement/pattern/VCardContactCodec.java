package lld.contactmanagement.pattern;

import lld.contactmanagement.model.AddressType;
import lld.contactmanagement.model.Contact;
import lld.contactmanagement.model.EmailAddress;
import lld.contactmanagement.model.EmailType;
import lld.contactmanagement.model.PhoneNumber;
import lld.contactmanagement.model.PhoneType;
import lld.contactmanagement.model.PostalAddress;

import java.util.ArrayList;
import java.util.List;

/**
 * A pragmatic, vCard-3.0-inspired codec: it follows the real vCard line
 * format (`PROPERTY;PARAM=VALUE:data`) closely enough to be recognizable and
 * round-trippable for this system's own fields, but is not a certified
 * RFC 6350 parser (no line folding/unfolding, no escaping beyond newlines in
 * {@code NOTE}, no exhaustive property set). That scope limitation is a
 * deliberate, documented simplification (README section 2) appropriate for
 * an interview-scope LLD; a production import/export feature would use a
 * vetted vCard library rather than a hand-rolled parser.
 */
public final class VCardContactCodec implements ContactCodec {

    @Override
    public String encode(Contact contact, List<String> groupNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCARD\n");
        sb.append("VERSION:3.0\n");
        sb.append("FN:").append(contact.displayName()).append('\n');
        for (PhoneNumber phone : contact.phoneNumbers()) {
            sb.append("TEL;TYPE=").append(phone.type()).append(':').append(phone.rawValue()).append('\n');
        }
        for (EmailAddress email : contact.emailAddresses()) {
            sb.append("EMAIL;TYPE=").append(email.type()).append(':').append(email.value()).append('\n');
        }
        for (PostalAddress address : contact.addresses()) {
            sb.append("ADR;TYPE=").append(address.type()).append(':')
                    .append(address.street()).append(';')
                    .append(address.city()).append(';')
                    .append(address.state()).append(';')
                    .append(address.zip()).append(';')
                    .append(address.country()).append('\n');
        }
        if (!groupNames.isEmpty()) {
            sb.append("CATEGORIES:").append(String.join(",", groupNames)).append('\n');
        }
        sb.append("X-FAVORITE:").append(contact.favorite()).append('\n');
        if (!contact.notes().isBlank()) {
            sb.append("NOTE:").append(contact.notes().replace("\n", "\\n")).append('\n');
        }
        sb.append("END:VCARD");
        return sb.toString();
    }

    @Override
    public DecodedContact decode(String text) {
        Contact.Builder builder = Contact.builder();
        List<String> groupNames = new ArrayList<>();

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.equalsIgnoreCase("BEGIN:VCARD")
                    || line.equalsIgnoreCase("END:VCARD") || line.toUpperCase().startsWith("VERSION:")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue; // malformed line; skip rather than fail the whole import
            }
            String keyPart = line.substring(0, colon);
            String value = line.substring(colon + 1);
            String[] keySegments = keyPart.split(";");
            String property = keySegments[0].toUpperCase();
            String typeParam = extractTypeParam(keySegments);

            switch (property) {
                case "FN" -> builder.displayName(value);
                case "TEL" -> builder.addPhone(PhoneNumber.of(value, parsePhoneType(typeParam)));
                case "EMAIL" -> builder.addEmail(EmailAddress.of(value, parseEmailType(typeParam)));
                case "ADR" -> builder.addAddress(parseAddress(value, typeParam));
                case "CATEGORIES" -> {
                    for (String name : value.split(",")) {
                        if (!name.isBlank()) {
                            groupNames.add(name.trim());
                        }
                    }
                }
                case "X-FAVORITE" -> builder.favorite(Boolean.parseBoolean(value));
                case "NOTE" -> builder.notes(value.replace("\\n", "\n"));
                default -> {
                    // Unknown/unsupported property: ignore rather than fail the import.
                }
            }
        }
        return new DecodedContact(builder, List.copyOf(groupNames));
    }

    /** Splits a 2.1/3.0-style vCard record string on blank lines into individual records. */
    public List<String> splitRecords(String multiVCardText) {
        List<String> records = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : multiVCardText.split("\\r?\\n")) {
            if (line.trim().equalsIgnoreCase("BEGIN:VCARD")) {
                current = new StringBuilder();
            }
            if (!current.isEmpty() || line.trim().equalsIgnoreCase("BEGIN:VCARD")) {
                current.append(line).append('\n');
            }
            if (line.trim().equalsIgnoreCase("END:VCARD")) {
                records.add(current.toString());
            }
        }
        return records;
    }

    private String extractTypeParam(String[] keySegments) {
        for (int i = 1; i < keySegments.length; i++) {
            if (keySegments[i].toUpperCase().startsWith("TYPE=")) {
                return keySegments[i].substring("TYPE=".length());
            }
        }
        return "OTHER";
    }

    private PhoneType parsePhoneType(String raw) {
        try {
            return PhoneType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PhoneType.OTHER;
        }
    }

    private EmailType parseEmailType(String raw) {
        try {
            return EmailType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return EmailType.OTHER;
        }
    }

    private AddressType parseAddressType(String raw) {
        try {
            return AddressType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AddressType.OTHER;
        }
    }

    private PostalAddress parseAddress(String value, String typeParam) {
        String[] parts = value.split(";", -1);
        String street = parts.length > 0 ? parts[0] : "";
        String city = parts.length > 1 ? parts[1] : "";
        String state = parts.length > 2 ? parts[2] : "";
        String zip = parts.length > 3 ? parts[3] : "";
        String country = parts.length > 4 ? parts[4] : "";
        return PostalAddress.of(street, city, state, zip, country, parseAddressType(typeParam));
    }
}
