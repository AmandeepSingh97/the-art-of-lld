package lld.contactmanagement.pattern;

import lld.contactmanagement.model.Contact;

/**
 * Strategy pattern: pluggable rule for deciding whether two contacts are
 * likely duplicates. Multiple independent strategies (exact contact-point
 * match, normalized-name match, ...) can be combined via
 * {@link CompositeDuplicateMatchStrategy} without {@code
 * DuplicateDetectionService} knowing which specific rules are active -
 * new matching heuristics (e.g. fuzzy/edit-distance name matching) can be
 * added later as new implementations with zero changes to existing code
 * (Open/Closed Principle).
 */
@FunctionalInterface
public interface DuplicateMatchStrategy {
    boolean isDuplicate(Contact a, Contact b);
}
