package lld.contactmanagement.service;

import lld.contactmanagement.model.Contact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stateless field-level merge logic for combining two contacts believed to
 * be duplicates, extracted from {@code ContactService} so the merge rules
 * themselves can be unit tested in isolation from repository/locking/
 * eventing concerns (single-responsibility: this class only knows how to
 * combine two {@code Contact} values, not how to persist the result).
 */
public final class ContactMerger {

    private ContactMerger() {
    }

    /**
     * Merges {@code duplicate} into {@code primary}, keeping {@code
     * primary}'s identity ({@code id}) and display name (the caller is
     * expected to have already decided which of the two is "primary",
     * typically the more complete or more recently used record). All other
     * fields are unioned: phone numbers, emails, addresses, and group
     * memberships are combined and de-duplicated (via {@code Contact}'s own
     * builder de-duplication); {@code favorite} is OR'd so marking either
     * side as a favorite survives the merge; {@code createdAt} takes the
     * earlier of the two timestamps (the contact's "true" creation time
     * predates either duplicate record being merged); {@code notes} are
     * concatenated when both are non-blank.
     */
    public static Contact merge(Contact primary, Contact duplicate) {
        Contact.Builder builder = primary.toBuilder();

        primary.phoneNumbers().forEach(builder::addPhone);
        duplicate.phoneNumbers().forEach(builder::addPhone);

        primary.emailAddresses().forEach(builder::addEmail);
        duplicate.emailAddresses().forEach(builder::addEmail);

        builder.addresses(mergeAddresses(primary, duplicate));

        Set<java.util.UUID> groupIds = new LinkedHashSet<>(primary.groupIds());
        groupIds.addAll(duplicate.groupIds());
        builder.groupIds(groupIds);

        builder.favorite(primary.favorite() || duplicate.favorite());

        Instant earliestCreated = primary.createdAt().isBefore(duplicate.createdAt())
                ? primary.createdAt()
                : duplicate.createdAt();
        builder.createdAt(earliestCreated);
        builder.updatedAt(Instant.now());

        String mergedNotes = mergeNotes(primary.notes(), duplicate.notes());
        builder.notes(mergedNotes);

        return builder.build();
    }

    private static List<lld.contactmanagement.model.PostalAddress> mergeAddresses(
            Contact primary, Contact duplicate) {
        List<lld.contactmanagement.model.PostalAddress> merged =
                new ArrayList<>(primary.addresses());
        for (lld.contactmanagement.model.PostalAddress address : duplicate.addresses()) {
            if (!merged.contains(address)) {
                merged.add(address);
            }
        }
        return merged;
    }

    private static String mergeNotes(String primaryNotes, String duplicateNotes) {
        boolean primaryBlank = primaryNotes == null || primaryNotes.isBlank();
        boolean duplicateBlank = duplicateNotes == null || duplicateNotes.isBlank();
        if (primaryBlank) {
            return duplicateBlank ? "" : duplicateNotes;
        }
        if (duplicateBlank) {
            return primaryNotes;
        }
        return primaryNotes + " | " + duplicateNotes;
    }
}
