package lld.fooddelivery.domain;

/**
 * Immutable value object representing a geographic coordinate (latitude/longitude) used by
 * both restaurants, customers, and delivery partners to compute straight-line distance for
 * the nearest-available-partner assignment strategy.
 *
 * <p>Distance is computed via the Haversine formula, which is accurate enough for short
 * urban distances (a few kilometres) without the complexity of full geodesic routing. In a
 * production system this would be replaced by a routing-engine ETA (e.g. road distance/time
 * from a maps provider) — see STAFF_LEVEL_DEEP_DIVE.md for that trade-off.
 */
public final class GeoLocation {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private final double latitude;
    private final double longitude;

    public GeoLocation(double latitude, double longitude) {
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude out of range: " + latitude);
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude out of range: " + longitude);
        }
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    /** Returns the great-circle distance to {@code other} in kilometres. */
    public double distanceKmTo(GeoLocation other) {
        double lat1 = Math.toRadians(this.latitude);
        double lat2 = Math.toRadians(other.latitude);
        double deltaLat = Math.toRadians(other.latitude - this.latitude);
        double deltaLon = Math.toRadians(other.longitude - this.longitude);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GeoLocation)) return false;
        GeoLocation that = (GeoLocation) o;
        return Double.compare(that.latitude, latitude) == 0 && Double.compare(that.longitude, longitude) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(latitude) * 31 + Double.hashCode(longitude);
    }

    @Override
    public String toString() {
        return String.format("(%.5f, %.5f)", latitude, longitude);
    }
}
