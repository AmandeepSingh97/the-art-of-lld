package lld.contactmanagement.exception;

import java.util.UUID;

/** Thrown when a lookup/update/delete targets a contact id that does not exist. */
public class ContactNotFoundException extends RuntimeException {

    public ContactNotFoundException(UUID contactId) {
        super("No contact found with id " + contactId);
    }

    public ContactNotFoundException(String message) {
        super(message);
    }
}
