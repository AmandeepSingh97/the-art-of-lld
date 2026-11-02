package lld.contactmanagement.pattern;

import lld.contactmanagement.model.Contact;
import lld.contactmanagement.util.ContactNormalizer;

/**
 * Two contacts are duplicates if their normalized display names are
 * identical (case/whitespace-insensitive exact match - e.g. "John Smith" and
 * "  john   smith" match, "Jon Smith" does not). This is a lower-precision,
 * higher-recall complement to {@link ExactContactPointMatchStrategy}: it
 * catches the "same person, re-entered with different phone/email" case,
 * at the cost of false positives for distinct people who happen to share a
 * common name. Combining both strategies (see {@link
 * CompositeDuplicateMatchStrategy}) with an {@code ANY} match mode is the
 * standard way to trade precision for recall deliberately rather than by
 * accident. True fuzzy (edit-distance/phonetic) name matching is named as a
 * documented future strategy, not implemented here, to avoid pulling in a
 * string-similarity dependency for an interview-scope LLD.
 */
public final class NormalizedNameMatchStrategy implements DuplicateMatchStrategy {

    @Override
    public boolean isDuplicate(Contact a, Contact b) {
        return ContactNormalizer.normalizeName(a.displayName())
                .equals(ContactNormalizer.normalizeName(b.displayName()));
    }
}
