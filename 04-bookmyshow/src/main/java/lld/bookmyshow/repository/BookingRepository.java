package lld.bookmyshow.repository;

import lld.bookmyshow.model.Booking;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class BookingRepository extends InMemoryRepository<String, Booking> {

    // Secondary index: idempotencyKey -> bookingId, for duplicate-request detection.
    private final ConcurrentHashMap<String, String> byIdempotencyKey = new ConcurrentHashMap<>();

    public BookingRepository() {
        super(Booking::getId);
    }

    @Override
    public Booking save(Booking entity) {
        Booking saved = super.save(entity);
        byIdempotencyKey.putIfAbsent(entity.getIdempotencyKey(), entity.getId());
        return saved;
    }

    public Optional<Booking> findByIdempotencyKey(String idempotencyKey) {
        String bookingId = byIdempotencyKey.get(idempotencyKey);
        return bookingId == null ? Optional.empty() : findById(bookingId);
    }
}
