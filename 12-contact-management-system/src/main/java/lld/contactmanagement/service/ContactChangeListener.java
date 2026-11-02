package lld.contactmanagement.service;

import lld.contactmanagement.model.ContactChangeEvent;

/**
 * Observer callback invoked by {@link ContactRepository} whenever a
 * contact's persisted state changes. Implemented by {@code IndexManager} to
 * keep every index synchronized with storage without storage needing to
 * know indexes exist (Observer pattern - see README section 7).
 */
@FunctionalInterface
public interface ContactChangeListener {
    void onChange(ContactChangeEvent event);
}
