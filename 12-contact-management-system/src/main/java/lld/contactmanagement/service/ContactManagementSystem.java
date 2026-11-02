package lld.contactmanagement.service;

import lld.contactmanagement.index.IndexManager;
import lld.contactmanagement.index.NameIndex;
import lld.contactmanagement.pattern.CompositeDuplicateMatchStrategy;
import lld.contactmanagement.pattern.ExactContactPointMatchStrategy;
import lld.contactmanagement.pattern.NormalizedNameMatchStrategy;

import java.util.List;

/**
 * Facade (Facade pattern) that wires together every collaborator and
 * exposes them as a single construction point for callers ({@code Main},
 * tests) that don't want to hand-assemble the object graph themselves.
 *
 * <p><b>This is deliberately a thin facade, not a god class.</b> It
 * contains zero business logic of its own - every method is a one-line
 * delegation (or, here, not even that: callers reach the sub-services
 * directly via the getters). All actual behavior lives in the focused
 * services it wires together ({@code ContactService}, {@code
 * SearchService}, etc.), each independently unit-testable without this
 * class. See the Staff-Level Deep Dive document for the explicit
 * "why isn't this a god class" discussion.
 *
 * <p>The choice of {@link NameIndex} implementation (Trie vs sorted-array)
 * is injected at construction time, making the Strategy-pattern trade-off
 * from README section 10 a real, swappable constructor parameter rather
 * than a hard-coded choice.
 */
public final class ContactManagementSystem {

    private final ContactRepository contactRepository;
    private final GroupRepository groupRepository;
    private final HistoryService historyService;
    private final IndexManager indexManager;
    private final ContactService contactService;
    private final GroupService groupService;
    private final SearchService searchService;
    private final DuplicateDetectionService duplicateDetectionService;
    private final ImportExportService importExportService;

    public ContactManagementSystem(NameIndex nameIndex) {
        this.contactRepository = new InMemoryContactRepository();
        this.groupRepository = new InMemoryGroupRepository();
        this.historyService = new HistoryService();
        this.indexManager = new IndexManager(nameIndex);
        this.contactRepository.addChangeListener(indexManager);

        this.contactService = new ContactService(contactRepository, groupRepository, historyService);
        this.groupService = new GroupService(groupRepository, contactService, indexManager);
        this.searchService = new SearchService(indexManager, contactRepository);
        this.duplicateDetectionService = new DuplicateDetectionService(
                indexManager,
                contactRepository,
                new CompositeDuplicateMatchStrategy(
                        List.of(new ExactContactPointMatchStrategy(), new NormalizedNameMatchStrategy()),
                        CompositeDuplicateMatchStrategy.Mode.ANY));
        this.importExportService = new ImportExportService(contactService, groupService);
    }

    public ContactService contacts() {
        return contactService;
    }

    public GroupService groups() {
        return groupService;
    }

    public SearchService search() {
        return searchService;
    }

    public DuplicateDetectionService duplicates() {
        return duplicateDetectionService;
    }

    public ImportExportService importExport() {
        return importExportService;
    }

    public HistoryService history() {
        return historyService;
    }
}
