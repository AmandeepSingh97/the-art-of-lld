package lld.contactmanagement.service;

import lld.contactmanagement.model.Contact;
import lld.contactmanagement.model.Group;
import lld.contactmanagement.pattern.ContactCodec;
import lld.contactmanagement.pattern.VCardContactCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates import/export by combining a {@link ContactCodec} (pure
 * text <-> fields translation, no persistence awareness) with
 * {@link ContactService} and {@link GroupService} (which resolve/create
 * groups by name and persist the resulting contacts). This split keeps the
 * codec unit-testable with plain strings and keeps persistence/validation
 * concerns out of the format-parsing code.
 */
public final class ImportExportService {

    private final ContactService contactService;
    private final GroupService groupService;
    private final ContactCodec codec;
    private final VCardContactCodec vCardCodec;

    public ImportExportService(ContactService contactService, GroupService groupService) {
        this.contactService = contactService;
        this.groupService = groupService;
        this.vCardCodec = new VCardContactCodec();
        this.codec = vCardCodec;
    }

    /** Exports a single contact as one vCard-style record, resolving its group ids to names. */
    public String exportContact(Contact contact) {
        List<String> groupNames = new ArrayList<>();
        for (UUID groupId : contact.groupIds()) {
            groupService.get(groupId).map(Group::name).ifPresent(groupNames::add);
        }
        return codec.encode(contact, groupNames);
    }

    /** Exports every known contact, concatenated with a blank line between records. */
    public String exportAll() {
        StringBuilder sb = new StringBuilder();
        for (Contact contact : contactService.getAll()) {
            sb.append(exportContact(contact)).append("\n\n");
        }
        return sb.toString().strip();
    }

    /**
     * Imports a single vCard-style record: decodes it, resolves/creates each
     * named group, and creates the contact through {@link ContactService} so
     * normal validation applies.
     */
    public Contact importContact(String vCardText) {
        ContactCodec.DecodedContact decoded = codec.decode(vCardText);
        for (String groupName : decoded.groupNames()) {
            Group group = groupService.findOrCreateByName(groupName);
            decoded.builder().addGroup(group.id());
        }
        return contactService.create(decoded.builder());
    }

    /** Imports a batch of concatenated vCard-style records (as produced by {@link #exportAll}). */
    public List<Contact> importAll(String multiVCardText) {
        List<Contact> imported = new ArrayList<>();
        for (String record : vCardCodec.splitRecords(multiVCardText)) {
            if (!record.isBlank()) {
                imported.add(importContact(record));
            }
        }
        return List.copyOf(imported);
    }
}
