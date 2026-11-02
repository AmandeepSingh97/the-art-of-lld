package lld.contactmanagement.pattern;

import lld.contactmanagement.model.AddressType;
import lld.contactmanagement.model.Contact;
import lld.contactmanagement.model.EmailAddress;
import lld.contactmanagement.model.EmailType;
import lld.contactmanagement.model.PhoneNumber;
import lld.contactmanagement.model.PhoneType;
import lld.contactmanagement.model.PostalAddress;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VCardContactCodecTest {

    private final VCardContactCodec codec = new VCardContactCodec();

    @Test
    void encodeProducesExpectedVCardStructure() {
        Contact contact = Contact.builder()
                .displayName("Tara Wong")
                .addPhone(PhoneNumber.of("555-666-7777", PhoneType.WORK))
                .addEmail(EmailAddress.of("tara@example.com", EmailType.WORK))
                .addAddress(PostalAddress.of("1 Main St", "Springfield", "IL", "62704", "USA", AddressType.HOME))
                .build();

        String encoded = codec.encode(contact, List.of("Work", "VIP"));

        assertTrue(encoded.startsWith("BEGIN:VCARD"));
        assertTrue(encoded.endsWith("END:VCARD"));
        assertTrue(encoded.contains("FN:Tara Wong"));
        assertTrue(encoded.contains("TEL;TYPE=WORK:555-666-7777"));
        assertTrue(encoded.contains("EMAIL;TYPE=WORK:tara@example.com"));
        assertTrue(encoded.contains("ADR;TYPE=HOME:1 Main St;Springfield;IL;62704;USA"));
        assertTrue(encoded.contains("CATEGORIES:Work,VIP"));
    }

    @Test
    void decodeParsesEveryFieldBackIntoABuilder() {
        String text = """
                BEGIN:VCARD
                VERSION:3.0
                FN:Uma Fernandez
                TEL;TYPE=MOBILE:555-888-9999
                EMAIL;TYPE=PERSONAL:uma@example.com
                ADR;TYPE=WORK:200 Office Blvd;Metropolis;NY;10001;USA
                CATEGORIES:Clients,Priority
                X-FAVORITE:true
                NOTE:likes coffee
                END:VCARD""";

        ContactCodec.DecodedContact decoded = codec.decode(text);
        Contact contact = decoded.builder().build();

        assertEquals("Uma Fernandez", contact.displayName());
        assertTrue(contact.hasPhone("5558889999"));
        assertTrue(contact.hasEmail("uma@example.com"));
        assertEquals(1, contact.addresses().size());
        assertEquals("200 Office Blvd", contact.addresses().get(0).street());
        assertTrue(contact.favorite());
        assertEquals("likes coffee", contact.notes());
        assertEquals(List.of("Clients", "Priority"), decoded.groupNames());
    }

    @Test
    void splitRecordsSeparatesConcatenatedVCards() {
        String combined = """
                BEGIN:VCARD
                VERSION:3.0
                FN:First Person
                EMAIL;TYPE=OTHER:first@example.com
                END:VCARD

                BEGIN:VCARD
                VERSION:3.0
                FN:Second Person
                EMAIL;TYPE=OTHER:second@example.com
                END:VCARD""";

        List<String> records = codec.splitRecords(combined);

        assertEquals(2, records.size());
        assertTrue(records.get(0).contains("First Person"));
        assertTrue(records.get(1).contains("Second Person"));
    }

    @Test
    void unknownPhoneOrEmailTypeFallsBackToOtherRatherThanFailing() {
        String text = """
                BEGIN:VCARD
                VERSION:3.0
                FN:Vik Rao
                TEL;TYPE=FAX:555-111-2222
                END:VCARD""";

        Contact contact = codec.decode(text).builder().build();

        assertEquals(PhoneType.OTHER, contact.phoneNumbers().get(0).type());
    }
}
