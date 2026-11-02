package lld.fooddelivery.domain;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A delivery partner (rider). {@link #status} is the critical piece of shared mutable state
 * in the assignment flow: two concurrent order-assignment attempts must never both succeed in
 * reserving the same partner.
 *
 * <p><b>Thread-safety:</b> {@code status} is an {@link AtomicReference} so that "reserve this
 * partner" is a single compare-and-set (AVAILABLE -&gt; BUSY) rather than a read-then-write pair
 * that could race. This is the same compare-and-swap idiom used for {@link MenuItem}
 * availability and is deliberately lock-free: the assignment service may need to try several
 * candidate partners in a loop, and taking a lock per candidate would add contention for no
 * benefit over a CAS loop.
 */
public final class DeliveryPartner {

    private final String id;
    private final String name;
    private volatile GeoLocation currentLocation;
    private final AtomicReference<PartnerStatus> status;

    public DeliveryPartner(String id, String name, GeoLocation currentLocation, PartnerStatus initialStatus) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.currentLocation = Objects.requireNonNull(currentLocation, "currentLocation");
        this.status = new AtomicReference<>(Objects.requireNonNull(initialStatus, "initialStatus"));
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public GeoLocation currentLocation() {
        return currentLocation;
    }

    public void updateLocation(GeoLocation location) {
        this.currentLocation = Objects.requireNonNull(location, "location");
    }

    public PartnerStatus status() {
        return status.get();
    }

    /**
     * Attempts to atomically reserve this partner (AVAILABLE -&gt; BUSY).
     *
     * @return {@code true} if this call won the race and reserved the partner; {@code false}
     *         if another thread already reserved it (or it is offline) — the caller must then
     *         try the next candidate rather than assuming success.
     */
    public boolean tryReserve() {
        return status.compareAndSet(PartnerStatus.AVAILABLE, PartnerStatus.BUSY);
    }

    /** Releases a partner back to the pool, e.g. after a compensating rollback or delivery completion. */
    public void release() {
        status.set(PartnerStatus.AVAILABLE);
    }

    public void goOffline() {
        status.set(PartnerStatus.OFFLINE);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeliveryPartner)) return false;
        return id.equals(((DeliveryPartner) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "DeliveryPartner{" + id + ", " + name + ", status=" + status.get() + '}';
    }
}
