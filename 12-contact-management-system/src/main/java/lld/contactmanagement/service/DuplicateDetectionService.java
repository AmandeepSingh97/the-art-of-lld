package lld.contactmanagement.service;

import lld.contactmanagement.index.IndexManager;
import lld.contactmanagement.model.Contact;
import lld.contactmanagement.pattern.DuplicateMatchStrategy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Finds potential duplicate contacts for a given target contact.
 *
 * <p><b>Why this doesn't scan every contact.</b> A naive implementation
 * would compare the target against all N other contacts - O(N) strategy
 * evaluations per check, O(N^2) to de-duplicate an entire address book.
 * Instead, this service uses the existing indexes purely as a
 * <em>candidate-generation / blocking</em> step (a standard technique from
 * record-linkage literature): only contacts that already share a normalized
 * phone, email, or a name token with the target are even considered -
 * typically a handful of candidates, resolved in O(1) per phone/email via
 * the hash indexes and O(|token|) via the name index. The (cheap, O(1))
 * {@link DuplicateMatchStrategy} checks then run only against that small
 * candidate set, not the whole repository.
 */
public final class DuplicateDetectionService {

    private final IndexManager indexManager;
    private final ContactRepository repository;
    private final DuplicateMatchStrategy strategy;

    public DuplicateDetectionService(
            IndexManager indexManager, ContactRepository repository, DuplicateMatchStrategy strategy) {
        this.indexManager = indexManager;
        this.repository = repository;
        this.strategy = strategy;
    }

    public List<Contact> findPotentialDuplicates(Contact target) {
        Set<UUID> candidateIds = new LinkedHashSet<>();

        target.phoneNumbers().forEach(p -> candidateIds.addAll(indexManager.findByNormalizedPhone(p.normalized())));
        target.emailAddresses().forEach(e -> candidateIds.addAll(indexManager.findByNormalizedEmail(e.value())));
        for (String token : target.nameTokens()) {
            candidateIds.addAll(indexManager.searchByNamePrefix(token));
        }
        candidateIds.remove(target.id());

        List<Contact> duplicates = new ArrayList<>();
        for (UUID candidateId : candidateIds) {
            repository.findById(candidateId).ifPresent(candidate -> {
                if (strategy.isDuplicate(target, candidate)) {
                    duplicates.add(candidate);
                }
            });
        }
        return List.copyOf(duplicates);
    }
}
