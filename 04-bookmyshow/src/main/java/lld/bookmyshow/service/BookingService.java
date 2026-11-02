package lld.bookmyshow.service;

import lld.bookmyshow.enums.BookingStatus;
import lld.bookmyshow.exception.BookingNotFoundException;
import lld.bookmyshow.exception.InvalidBookingStateException;
import lld.bookmyshow.exception.PaymentFailedException;
import lld.bookmyshow.exception.SeatLockExpiredException;
import lld.bookmyshow.exception.ShowNotFoundException;
import lld.bookmyshow.model.Booking;
import lld.bookmyshow.model.Payment;
import lld.bookmyshow.model.SeatLock;
import lld.bookmyshow.model.Show;
import lld.bookmyshow.model.ShowSeat;
import lld.bookmyshow.repository.BookingRepository;
import lld.bookmyshow.repository.PaymentRepository;
import lld.bookmyshow.repository.SeatInventoryRepository;
import lld.bookmyshow.repository.ShowRepository;
import lld.bookmyshow.strategy.PricingStrategy;
import lld.bookmyshow.util.IdGenerator;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Orchestrates the end-to-end booking workflow: seat hold -&gt; payment -&gt;
 * confirmation, plus cancellation/refund. This is the component that
 * composes {@link SeatLockService} and {@link PaymentGateway} into a single
 * consistent operation and owns the {@link Booking} aggregate's lifecycle.
 *
 * <h2>Idempotency</h2>
 * Every state-changing entry point ({@link #initiateBooking},
 * {@link #confirmBooking}) accepts an {@code idempotencyKey} supplied by the
 * caller (typically generated client-side once per user action and resent
 * unchanged on retry). Two safeguards use it:
 * <ul>
 *   <li>{@link #initiateBooking} looks up any existing booking for the key
 *       first — a retried "lock seats" call after a network timeout returns
 *       the <i>same</i> booking instead of attempting to lock the seats
 *       again (which would fail anyway since they're already held by the
 *       first, successful attempt).</li>
 *   <li>{@link #confirmBooking} forwards the key to {@link PaymentGateway},
 *       which guarantees a retried confirm cannot charge the card twice,
 *       and short-circuits if the booking is already {@code CONFIRMED}.</li>
 * </ul>
 */
public class BookingService {

    private final SeatLockService seatLockService;
    private final PaymentGateway paymentGateway;
    private final PricingStrategy pricingStrategy;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final ShowRepository showRepository;
    private final SeatInventoryRepository seatInventoryRepository;
    private final Clock clock;
    private final Duration lockTtl;
    private final List<BookingEventListener> listeners = new CopyOnWriteArrayList<>();

    // Reverse index so the seat-lock-expiry callback (keyed by lockId) can find the booking to expire.
    private final ConcurrentHashMap<String, String> bookingIdByLockId = new ConcurrentHashMap<>();

    public BookingService(SeatLockService seatLockService, PaymentGateway paymentGateway,
                           PricingStrategy pricingStrategy, BookingRepository bookingRepository,
                           PaymentRepository paymentRepository, ShowRepository showRepository,
                           SeatInventoryRepository seatInventoryRepository, Clock clock, Duration lockTtl) {
        this.seatLockService = seatLockService;
        this.paymentGateway = paymentGateway;
        this.pricingStrategy = pricingStrategy;
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.showRepository = showRepository;
        this.seatInventoryRepository = seatInventoryRepository;
        this.clock = clock;
        this.lockTtl = lockTtl;
        this.seatLockService.onLockExpired(this::handleLockExpired);
    }

    public void addListener(BookingEventListener listener) {
        listeners.add(listener);
    }

    /**
     * Step 1 of checkout: hold the requested seats and price the booking.
     * Does not charge payment — the caller has {@code lockTtl} to call
     * {@link #confirmBooking} before the hold expires.
     *
     * @throws lld.bookmyshow.exception.SeatUnavailableException if any requested seat is already held/booked
     */
    public Booking initiateBooking(String userId, String showId, List<String> seatIds, String idempotencyKey) {
        Booking existing = bookingRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return existing;
        }

        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ShowNotFoundException("No such show: " + showId));

        SeatLock lock = seatLockService.lockSeats(showId, seatIds, userId, lockTtl);
        BigDecimal amount = computeAmount(show, seatIds);

        Booking booking = new Booking(IdGenerator.uuid(), userId, showId, seatIds, lock.getLockId(),
                amount, idempotencyKey, clock.instant());
        booking.transitionTo(BookingStatus.SEATS_LOCKED);
        bookingRepository.save(booking);
        bookingIdByLockId.put(lock.getLockId(), booking.getId());
        return booking;
    }

    /**
     * Step 2 of checkout: charge payment and, if successful, permanently
     * confirm the held seats. Safe to retry with the same
     * {@code idempotencyKey} (see class javadoc).
     *
     * @throws PaymentFailedException   if the charge is declined (seats are released)
     * @throws SeatLockExpiredException if the hold expired before confirmation completed (rare race; payment is refunded)
     */
    public Booking confirmBooking(String bookingId, String idempotencyKey) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("No such booking: " + bookingId));

        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            return booking; // idempotent re-entry
        }
        if (booking.getStatus() != BookingStatus.SEATS_LOCKED) {
            throw new InvalidBookingStateException(
                    "Booking " + bookingId + " cannot be confirmed from state " + booking.getStatus());
        }

        booking.transitionTo(BookingStatus.PAYMENT_PENDING);

        PaymentGateway.PaymentResult result = paymentGateway.charge(idempotencyKey, booking.getAmount());
        if (!result.success()) {
            seatLockService.releaseLock(booking.getLockId());
            booking.transitionTo(BookingStatus.FAILED);
            throw new PaymentFailedException("Payment declined: " + result.failureReason());
        }

        Payment payment = new Payment(IdGenerator.uuid(), booking.getId(), booking.getAmount(), clock.instant());
        payment.markSuccess();
        paymentRepository.save(payment);
        booking.setPaymentId(payment.getId());

        boolean seatsConfirmed = seatLockService.confirmLock(booking.getLockId());
        if (!seatsConfirmed) {
            // The hold expired in the narrow window between acquiring it and the payment completing.
            // We already charged the customer, so refund immediately rather than leaving them charged
            // with no seats.
            paymentGateway.refund(payment.getId(), payment.getAmount());
            payment.markRefunded();
            booking.transitionTo(BookingStatus.EXPIRED);
            listeners.forEach(l -> l.onBookingExpired(booking));
            throw new SeatLockExpiredException(
                    "Seat hold expired before payment could be confirmed for booking " + bookingId);
        }

        booking.transitionTo(BookingStatus.CONFIRMED);
        bookingIdByLockId.remove(booking.getLockId());
        listeners.forEach(l -> l.onBookingConfirmed(booking));
        return booking;
    }

    /** Cancels a confirmed booking, frees its seats, and refunds the payment. */
    public Booking cancelBooking(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("No such booking: " + bookingId));

        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new InvalidBookingStateException(
                    "Only CONFIRMED bookings can be cancelled; booking " + bookingId + " is " + booking.getStatus());
        }

        booking.transitionTo(BookingStatus.CANCELLED);

        for (String seatId : booking.getSeatIds()) {
            seatInventoryRepository.findShowSeat(booking.getShowId(), seatId)
                    .ifPresent(ShowSeat::cancelBooking);
        }

        if (booking.getPaymentId() != null) {
            paymentRepository.findById(booking.getPaymentId()).ifPresent(payment -> {
                paymentGateway.refund(payment.getId(), payment.getAmount());
                payment.markRefunded();
            });
        }

        listeners.forEach(l -> l.onBookingCancelled(booking));
        return booking;
    }

    public Booking getBooking(String bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("No such booking: " + bookingId));
    }

    private BigDecimal computeAmount(Show show, List<String> seatIds) {
        BigDecimal total = BigDecimal.ZERO;
        for (String seatId : seatIds) {
            ShowSeat showSeat = seatInventoryRepository.findShowSeat(show.getId(), seatId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown seat " + seatId + " for show " + show.getId()));
            total = total.add(pricingStrategy.priceFor(show, showSeat.getCategory(), clock.instant()));
        }
        return total;
    }

    /** Invoked by {@link SeatLockService} when a hold expires without being confirmed/released. */
    private void handleLockExpired(SeatLock lock) {
        String bookingId = bookingIdByLockId.remove(lock.getLockId());
        if (bookingId == null) {
            return;
        }
        bookingRepository.findById(bookingId).ifPresent(booking -> {
            // tryTransitionTo (not transitionTo): if the booking already raced ahead to CONFIRMED/FAILED
            // before this callback ran, EXPIRED is no longer a valid transition and we silently no-op
            // rather than throwing on a callback thread.
            if (booking.tryTransitionTo(BookingStatus.EXPIRED)) {
                listeners.forEach(l -> l.onBookingExpired(booking));
            }
        });
    }
}
