package lld.bookmyshow.repository;

import java.util.List;
import java.util.Optional;

/**
 * Minimal CRUD contract shared by the in-memory repositories in this demo.
 * Kept intentionally small (no generic query/specification support) — this
 * is an LLD interview exercise, not a persistence framework; see
 * {@code STAFF_LEVEL_DEEP_DIVE.md} for how this would be replaced by a real
 * DAO/JPA repository in production.
 */
public interface Repository<ID, T> {
    T save(T entity);

    Optional<T> findById(ID id);

    List<T> findAll();
}
