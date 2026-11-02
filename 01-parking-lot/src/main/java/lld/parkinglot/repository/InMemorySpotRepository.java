package lld.parkinglot.repository;

import lld.parkinglot.enums.SpotSize;
import lld.parkinglot.enums.SpotStatus;
import lld.parkinglot.model.Floor;
import lld.parkinglot.model.ParkingSpot;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * In-memory {@link SpotRepository}. The concurrency design in one sentence:
 * <b>a spot's availability is represented in exactly one place at a time -
 * either it sits in the per (floor, size) {@link ConcurrentSkipListSet}
 * "available index", or it does not, and whoever atomically removes it from
 * that index is the sole owner of the next state transition.</b>
 *
 * <p>This turns every allocation, and every admin out-of-service operation,
 * into one lock-free {@code NavigableSet.pollFirst()}/{@code remove()} plus
 * one confirming CAS on {@link ParkingSpot} itself - no explicit locks, no
 * risk of deadlock, and linear scalability across entry gates since
 * different (floor, size) buckets never contend with each other.
 *
 * <p>Trade-off made explicitly here: {@code pollFirst()} gives us the
 * lowest {@code spotNumber} spot, which is our proxy for "nearest". A
 * tree/skip-list is O(log n) per operation, which is more than adequate for
 * a single garage (hundreds to low thousands of spots) and dramatically
 * simpler and more scalable under contention than a single
 * {@code synchronized} linear scan would be.
 */
public final class InMemorySpotRepository implements SpotRepository {

    private final List<Floor> floors;
    private final Map<String, ParkingSpot> spotsById = new ConcurrentHashMap<>();
    // floorNumber -> size -> available spots ordered by spotNumber ascending
    private final Map<Integer, Map<SpotSize, NavigableSet<ParkingSpot>>> availableIndex = new ConcurrentHashMap<>();

    private static final Comparator<ParkingSpot> BY_SPOT_NUMBER_THEN_ID =
            Comparator.comparingInt(ParkingSpot::getSpotNumber).thenComparing(ParkingSpot::getId);

    public InMemorySpotRepository(List<Floor> floors) {
        this.floors = floors;
        for (Floor floor : floors) {
            Map<SpotSize, NavigableSet<ParkingSpot>> bySize = new ConcurrentHashMap<>();
            for (SpotSize size : SpotSize.values()) {
                bySize.put(size, new ConcurrentSkipListSet<>(BY_SPOT_NUMBER_THEN_ID));
            }
            availableIndex.put(floor.getFloorNumber(), bySize);
            for (ParkingSpot spot : floor.getSpots()) {
                spotsById.put(spot.getId(), spot);
                if (spot.getStatus() == SpotStatus.AVAILABLE) {
                    bySize.get(spot.getSize()).add(spot);
                }
            }
        }
    }

    @Override
    public List<Floor> getFloors() {
        return floors;
    }

    @Override
    public Optional<ParkingSpot> reserveSpot(int floorNumber, SpotSize size) {
        NavigableSet<ParkingSpot> candidates = bucket(floorNumber, size);
        if (candidates == null) {
            return Optional.empty();
        }
        ParkingSpot candidate;
        while ((candidate = candidates.pollFirst()) != null) {
            if (candidate.tryReserve()) {
                return Optional.of(candidate);
            }
            // Defensive only: under this repository's invariants a polled spot always
            // wins tryReserve, since removal from the index is the sole gate for
            // AVAILABLE -> {RESERVED, OUT_OF_SERVICE}. We keep the loop for safety in
            // case of future callers that mutate ParkingSpot status directly.
        }
        return Optional.empty();
    }

    @Override
    public void releaseReservation(ParkingSpot spot) {
        spot.releaseReservation();
        bucket(spot.getFloorNumber(), spot.getSize()).add(spot);
    }

    @Override
    public void freeSpot(ParkingSpot spot) {
        spot.vacate();
        bucket(spot.getFloorNumber(), spot.getSize()).add(spot);
    }

    @Override
    public Optional<ParkingSpot> findById(String spotId) {
        return Optional.ofNullable(spotsById.get(spotId));
    }

    @Override
    public boolean markOutOfService(String spotId) {
        ParkingSpot spot = spotsById.get(spotId);
        if (spot == null) {
            return false;
        }
        NavigableSet<ParkingSpot> bucket = bucket(spot.getFloorNumber(), spot.getSize());
        if (!bucket.remove(spot)) {
            // Spot was not sitting in the available index (already reserved/occupied/out-of-service).
            return false;
        }
        if (!spot.tryMarkOutOfService()) {
            // Should be unreachable given the index is the sole gate; restore the index on the
            // defensive path so the system self-heals rather than silently losing a spot.
            bucket.add(spot);
            return false;
        }
        return true;
    }

    @Override
    public boolean bringBackInService(String spotId) {
        ParkingSpot spot = spotsById.get(spotId);
        if (spot == null) {
            return false;
        }
        if (!spot.bringBackInService()) {
            return false;
        }
        bucket(spot.getFloorNumber(), spot.getSize()).add(spot);
        return true;
    }

    private NavigableSet<ParkingSpot> bucket(int floorNumber, SpotSize size) {
        Map<SpotSize, NavigableSet<ParkingSpot>> bySize = availableIndex.get(floorNumber);
        return bySize == null ? null : bySize.get(size);
    }
}
