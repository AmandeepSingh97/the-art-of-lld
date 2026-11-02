package lld.bookmyshow.repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Thread-safe in-memory {@link Repository} backed by a {@link ConcurrentHashMap}.
 *
 * <p><b>Thread-safety:</b> {@code ConcurrentHashMap} gives us safe
 * publication and lock-striped concurrent reads/writes for the map
 * operations themselves. That is sufficient here because every entity
 * stored in these repositories either is immutable (e.g. {@code Movie},
 * {@code Show}) or manages its own internal synchronization for mutable
 * fields (e.g. {@code Booking} uses an {@code AtomicReference} for status).
 * The repository therefore never needs to lock across a read-modify-write
 * of an entity's internals — only the map structure (insert/lookup) needs
 * to be concurrent, and {@code ConcurrentHashMap} provides exactly that.
 */
public class InMemoryRepository<ID, T> implements Repository<ID, T> {

    private final ConcurrentHashMap<ID, T> store = new ConcurrentHashMap<>();
    private final Function<T, ID> idExtractor;

    public InMemoryRepository(Function<T, ID> idExtractor) {
        this.idExtractor = idExtractor;
    }

    @Override
    public T save(T entity) {
        store.put(idExtractor.apply(entity), entity);
        return entity;
    }

    @Override
    public Optional<T> findById(ID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<T> findAll() {
        return List.copyOf(store.values());
    }
}
