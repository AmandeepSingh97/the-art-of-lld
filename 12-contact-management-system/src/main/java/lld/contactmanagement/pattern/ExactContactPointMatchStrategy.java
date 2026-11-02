package lld.contactmanagement.pattern;

import lld.contactmanagement.model.Contact;
import lld.contactmanagement.model.EmailAddress;
import lld.contactmanagement.model.PhoneNumber;

/**
 * Two contacts are duplicates if they share at least one normalized phone
 * number or email address. This is the highest-precision, lowest-recall
 * strategy: a shared contact point is strong evidence of duplication (false
 * positives are rare - two different real people essentially never share a
 * phone number or personal email), but it misses duplicates entered with
 * completely different contact details under the same name.
 */
public final class ExactContactPointMatchStrategy implements DuplicateMatchStrategy {

    @Override
    public boolean isDuplicate(Contact a, Contact b) {
        for (PhoneNumber phone : a.phoneNumbers()) {
            if (b.hasPhone(phone.normalized())) {
                return true;
            }
        }
        for (EmailAddress email : a.emailAddresses()) {
            if (b.hasEmail(email.value())) {
                return true;
            }
        }
        return false;
    }
}
