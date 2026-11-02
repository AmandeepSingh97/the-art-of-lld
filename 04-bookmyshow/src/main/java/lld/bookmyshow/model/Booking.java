package lld.bookmyshow.model;

import lld.bookmyshow.enums.BookingStatus;
import lld.bookmyshow.exception.InvalidBookingStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Aggregate root for a single booking attempt: a user, a show, a set of
 * seats, and the money involved. Tracks its own lifecycle via
 * {@link BookingStatus} and validates every transition so that, for
 * example, a booking cannot be confirmed twice or cancelled after it was
 * never confirmed in the first place.
 *
 * <p><b>Why an explicit transition table instead of a full State-pattern
 * class hierarchy:</b> the behavioural difference between states here is
 * limited to "which transitions are legal", not divergent per-state
 * behaviour with its own methods. A table-driven guard captures that with
 * a fraction of the ceremony of one class per state. See
 * {@code README.md §7 Design Patterns} for the full discussion of why the
 * State pattern was considered and rejected.
 */
public final class Booking {

    private static final Map<BookingStatus, EnumSet<BookingStatus>> TRANSITIONS = buildTransitions();

    private static Map<BookingStatus, EnumSet<BookingStatus>> buildTransitions() {
        Map<BookingStatus, EnumSet<BookingStatus>> map = new EnumMap<>(BookingStatus.class);
        map.put(BookingStatus.CREATED, EnumSet.of(BookingStatus.SEATS_LOCKED, BookingStatus.FAILED));
        map.put(BookingStatus.SEATS_LOCKED, EnumSet.of(BookingStatus.PAYMENT_PENDING, BookingStatus.EXPIRED, BookingStatus.FAILED));
        map.put(BookingStatus.PAYMENT_PENDING, EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.FAILED, BookingStatus.EXPIRED));
        map.put(BookingStatus.CONFIRMED, EnumSet.of(BookingStatus.CANCELLED));
        map.put(BookingStatus.CANCELLED, EnumSet.noneOf(BookingStatus.class));
        map.put(BookingStatus.EXPIRED, EnumSet.noneOf(BookingStatus.class));
        map.put(BookingStatus.FAILED, EnumSet.noneOf(BookingStatus.class));
        return map;
    }

    private final String id;
    private final String userId;
    private final String showId;
    private final List<String> seatIds;
    private final String lockId;
    private final BigDecimal amount;
    private final String idempotencyKey;
    private final Instant createdAt;
    private final AtomicReference<BookingStatus> status = new AtomicReference<>(BookingStatus.CREATED);
    private volatile String paymentId;

    public Booking(String id, String userId, String showId, List<String> seatIds, String lockId,
                   BigDecimal amount, String idempotencyKey, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.showId = Objects.requireNonNull(showId, "showId");
        this.seatIds = List.copyOf(seatIds);
        this.lockId = Objects.requireNonNull(lockId, "lockId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getShowId() {
        return showId;
    }

    public List<String> getSeatIds() {
        return seatIds;
    }

    public String getLockId() {
        return lockId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public BookingStatus getStatus() {
        return status.get();
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    /**
     * Atomically moves the booking to {@code target}, validating the
     * transition against the state table. Uses a CAS loop (rather than
     * {@code synchronized}) so that two threads racing to transition the
     * same booking (e.g. a confirm call racing the TTL-expiry sweeper)
     * cannot both "win" — exactly one CAS succeeds, and the loser sees a
     * current state that may no longer permit its intended transition and
     * throws instead of corrupting state.
     */
    public void transitionTo(BookingStatus target) {
        while (true) {
            BookingStatus current = status.get();
            EnumSet<BookingStatus> allowed = TRANSITIONS.get(current);
            if (allowed == null || !allowed.contains(target)) {
                throw new InvalidBookingStateException(
                        "Cannot transition booking " + id + " from " + current + " to " + target);
            }
            if (status.compareAndSet(current, target)) {
                return;
            }
        }
    }

    /** Non-throwing variant used by idempotent retries that may race a terminal state. */
    public boolean tryTransitionTo(BookingStatus target) {
        while (true) {
            BookingStatus current = status.get();
            EnumSet<BookingStatus> allowed = TRANSITIONS.get(current);
            if (allowed == null || !allowed.contains(target)) {
                return false;
            }
            if (status.compareAndSet(current, target)) {
                return true;
            }
        }
    }
}
