package lld.contactmanagement.model;

import java.util.Objects;

/**
 * Value object representing a postal address. {@code state} and {@code zip}
 * are optional (empty string) since not every country/address uses them;
 * {@code street}, {@code city}, and {@code country} are required to keep the
 * address minimally useful.
 */
public record PostalAddress(
        String street,
        String city,
        String state,
        String zip,
        String country,
        AddressType type) {

    public PostalAddress {
        Objects.requireNonNull(street, "street must not be null");
        Objects.requireNonNull(city, "city must not be null");
        Objects.requireNonNull(country, "country must not be null");
        Objects.requireNonNull(type, "type must not be null");
        state = state == null ? "" : state;
        zip = zip == null ? "" : zip;
        if (street.isBlank() || city.isBlank() || country.isBlank()) {
            throw new IllegalArgumentException("street, city and country must not be blank");
        }
    }

    public static PostalAddress of(
            String street, String city, String state, String zip, String country, AddressType type) {
        return new PostalAddress(street, city, state, zip, country, type);
    }
}
