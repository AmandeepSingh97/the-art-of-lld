package lld.contactmanagement.model;

import lld.contactmanagement.exception.InvalidContactException;
import lld.contactmanagement.util.ContactNormalizer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate root of the domain: a single person/organization's contact
 * record with multiple phone numbers, emails, postal addresses, group
 * memberships, a favorite flag, and free-form notes.
 *
 * <p><b>Immutability is the central design decision of this class.</b> Every
 * field is final and every collection is defensively copied into an
 * unmodifiable form at construction. "Editing" a contact never mutates an
 * existing instance; it produces a brand new {@code Contact} via
 * {@link #toBuilder()}. This buys three things simultaneously:
 * <ol>
 *   <li><b>Thread safety for free</b> - a published {@code Contact} reference
 *       can be read concurrently with zero synchronization.</li>
 *   <li><b>The Memento pattern, for free</b> - the previous {@code Contact}
 *       instance already IS the "memento" needed to undo an edit. No parallel
 *       snapshot/state-capture bookkeeping is required (see
 *       {@code HistoryService}).</li>
 *   <li><b>Lock-free optimistic concurrency</b> - updates become "read
 *       current immutable snapshot, build new snapshot, atomically swap if
 *       nobody else swapped first" (see {@code ContactService#update} and
 *       {@code InMemoryContactRepository#compareAndSet}).</li>
 * </ol>
 *
 * <p>Entity identity: {@link #equals(Object)} and {@link #hashCode()} are
 * intentionally based on {@link #id()} alone, matching standard entity
 * semantics (two snapshots of the "same" contact at different points in time
 * are the same entity). This is a deliberate trade-off: it means {@code
 * equals} must NEVER be used as a substitute for reference-identity
 * comparisons - see the javadoc on {@code InMemoryContactRepository
 * #compareAndSet} for why that distinction matters for correctness.
 */
public final class Contact {

    private final UUID id;
    private final String displayName;
    private final List<PhoneNumber> phoneNumbers;
    private final List<EmailAddress> emailAddresses;
    private final List<PostalAddress> addresses;
    private final Set<UUID> groupIds;
    private final boolean favorite;
    private final String notes;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Contact(Builder builder) {
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.phoneNumbers = dedupePhones(builder.phoneNumbers);
        this.emailAddresses = List.copyOf(new LinkedHashSet<>(builder.emailAddresses));
        this.addresses = List.copyOf(builder.addresses);
        this.groupIds = Set.copyOf(builder.groupIds);
        this.favorite = builder.favorite;
        this.notes = builder.notes == null ? "" : builder.notes;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    /**
     * De-duplicates phone numbers by their <em>normalized</em> value plus
     * type (not record equality on raw formatting), so "(555) 123-4567" and
     * "555-123-4567" of the same {@link PhoneType} collapse to one entry
     * while preserving first-seen order and original formatting.
     */
    private static List<PhoneNumber> dedupePhones(List<PhoneNumber> source) {
        Map<String, PhoneNumber> byKey = new LinkedHashMap<>();
        for (PhoneNumber phone : source) {
            String key = phone.normalized() + "#" + phone.type();
            byKey.putIfAbsent(key, phone);
        }
        return List.copyOf(byKey.values());
    }

    public UUID id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public List<PhoneNumber> phoneNumbers() {
        return phoneNumbers;
    }

    public List<EmailAddress> emailAddresses() {
        return emailAddresses;
    }

    public List<PostalAddress> addresses() {
        return addresses;
    }

    public Set<UUID> groupIds() {
        return groupIds;
    }

    public boolean favorite() {
        return favorite;
    }

    public String notes() {
        return notes;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** Returns the normalized first whitespace-delimited token of the name. */
    public String[] nameTokens() {
        return ContactNormalizer.nameTokens(displayName);
    }

    public boolean hasPhone(String normalizedPhone) {
        return phoneNumbers.stream().anyMatch(p -> p.normalized().equals(normalizedPhone));
    }

    public boolean hasEmail(String normalizedEmail) {
        return emailAddresses.stream().anyMatch(e -> e.value().equals(normalizedEmail));
    }

    /** Starts a new builder with defaulted id/timestamps for a brand new contact. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts a builder pre-populated with this contact's current state, for
     * copy-on-write edits. The resulting contact keeps the same {@code id}
     * and {@code createdAt} unless explicitly overridden.
     */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.id = this.id;
        b.displayName = this.displayName;
        b.phoneNumbers = new ArrayList<>(this.phoneNumbers);
        b.emailAddresses = new ArrayList<>(this.emailAddresses);
        b.addresses = new ArrayList<>(this.addresses);
        b.groupIds = new LinkedHashSet<>(this.groupIds);
        b.favorite = this.favorite;
        b.notes = this.notes;
        b.createdAt = this.createdAt;
        b.updatedAt = this.updatedAt;
        return b;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Contact other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Contact{id=%s, displayName='%s', phones=%d, emails=%d, addresses=%d, favorite=%s}"
                .formatted(id, displayName, phoneNumbers.size(), emailAddresses.size(),
                        addresses.size(), favorite);
    }

    /** Builder for {@link Contact}; see class javadoc for the copy-on-write editing model. */
    public static final class Builder {
        private UUID id = UUID.randomUUID();
        private String displayName = "";
        private List<PhoneNumber> phoneNumbers = new ArrayList<>();
        private List<EmailAddress> emailAddresses = new ArrayList<>();
        private List<PostalAddress> addresses = new ArrayList<>();
        private Set<UUID> groupIds = new LinkedHashSet<>();
        private boolean favorite = false;
        private String notes = "";
        private Instant createdAt = Instant.now();
        private Instant updatedAt = Instant.now();

        private Builder() {
        }

        /** Internal use by {@code ContactService}/{@code ImportExportService} for restores. */
        public Builder id(UUID id) {
            this.id = Objects.requireNonNull(id);
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = Objects.requireNonNull(displayName);
            return this;
        }

        public Builder addPhone(PhoneNumber phone) {
            this.phoneNumbers.add(Objects.requireNonNull(phone));
            return this;
        }

        public Builder phoneNumbers(List<PhoneNumber> phones) {
            this.phoneNumbers = new ArrayList<>(Objects.requireNonNull(phones));
            return this;
        }

        public Builder removePhone(String normalizedPhone) {
            this.phoneNumbers.removeIf(p -> p.normalized().equals(normalizedPhone));
            return this;
        }

        public Builder addEmail(EmailAddress email) {
            this.emailAddresses.add(Objects.requireNonNull(email));
            return this;
        }

        public Builder emailAddresses(List<EmailAddress> emails) {
            this.emailAddresses = new ArrayList<>(Objects.requireNonNull(emails));
            return this;
        }

        public Builder removeEmail(String normalizedEmail) {
            this.emailAddresses.removeIf(e -> e.value().equals(normalizedEmail));
            return this;
        }

        public Builder addAddress(PostalAddress address) {
            this.addresses.add(Objects.requireNonNull(address));
            return this;
        }

        public Builder addresses(List<PostalAddress> addresses) {
            this.addresses = new ArrayList<>(Objects.requireNonNull(addresses));
            return this;
        }

        public Builder addGroup(UUID groupId) {
            this.groupIds.add(Objects.requireNonNull(groupId));
            return this;
        }

        public Builder removeGroup(UUID groupId) {
            this.groupIds.remove(groupId);
            return this;
        }

        public Builder groupIds(Set<UUID> groupIds) {
            this.groupIds = new LinkedHashSet<>(Objects.requireNonNull(groupIds));
            return this;
        }

        public Builder favorite(boolean favorite) {
            this.favorite = favorite;
            return this;
        }

        public Builder notes(String notes) {
            this.notes = notes;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = Objects.requireNonNull(createdAt);
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = Objects.requireNonNull(updatedAt);
            return this;
        }

        /**
         * Builds the immutable contact, enforcing the single structural
         * (entity-level) invariant: a non-blank display name. The richer
         * business rule - "must have at least one contact point" - is
         * deliberately enforced one layer up, in {@code ContactService}, not
         * here; see README section 5 (Domain Invariants) for the rationale.
         */
        public Contact build() {
            if (displayName == null || displayName.isBlank()) {
                throw new InvalidContactException("displayName must not be blank");
            }
            return new Contact(this);
        }
    }
}
