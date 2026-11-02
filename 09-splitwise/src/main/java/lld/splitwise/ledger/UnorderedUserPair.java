package lld.splitwise.ledger;

import java.util.Objects;

/**
 * An unordered pair of user ids, canonically ordered so {@code (a, b)} and
 * {@code (b, a)} hash/equal identically. Used as the key of the pairwise
 * balance map in {@link BalanceSheet}; the sign convention for the stored
 * value is documented there.
 */
final class UnorderedUserPair {

    final String first;  // lexicographically smaller user id
    final String second; // lexicographically larger user id

    private UnorderedUserPair(String first, String second) {
        this.first = first;
        this.second = second;
    }

    static UnorderedUserPair of(String userA, String userB) {
        if (userA.equals(userB)) {
            throw new IllegalArgumentException("Cannot create a balance pair for a user and themselves: " + userA);
        }
        return userA.compareTo(userB) < 0
                ? new UnorderedUserPair(userA, userB)
                : new UnorderedUserPair(userB, userA);
    }

    /** +1 if {@code userId} is the canonical "first" of the pair, -1 if it is "second". */
    int signFor(String userId) {
        if (userId.equals(first)) return 1;
        if (userId.equals(second)) return -1;
        throw new IllegalArgumentException(userId + " is not part of this pair");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UnorderedUserPair that)) return false;
        return first.equals(that.first) && second.equals(that.second);
    }

    @Override
    public int hashCode() {
        return Objects.hash(first, second);
    }
}
