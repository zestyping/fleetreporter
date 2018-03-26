package ca.zesty.fleetreporter;

import java.util.Locale;

/** A pure data class to take the place of android.location.Location, which
    cannot be used in unit tests due to its unfortunate design.
 */
public class LocationFix {
    static final double EQUATOR_RADIUS = 6378137;  // meters from Earth center to equator
    static final double POLE_RADIUS = 6356752;  // meters from Earth center to pole
    static final double MEAN_RADIUS = (2 * EQUATOR_RADIUS + POLE_RADIUS)/3;
    static final double RADIANS_PER_DEGREE = 2 * Math.PI / 360;

    public final long timeMillis;  // ms since 1970-01-01 00:00:00 UTC
    public final double latitude;  // degrees
    public final double longitude;  // degrees
    public final double altitude;  // meters
    public final double speed;  // meters per second
    public final double bearing;  // degrees
    public final double latLonSd;  // 68% confidence radius of lat/lon position, meters

    public LocationFix(long timeMillis, double latitude, double longitude,
                       double altitude, double speed, double bearing, double latLonSd) {
        this.timeMillis = timeMillis;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.speed = speed;
        this.bearing = bearing;
        this.latLonSd = latLonSd;
    }

    public boolean equals(Object otherObject) {
        if (otherObject instanceof LocationFix) {
            LocationFix other = (LocationFix) otherObject;
            return timeMillis == other.timeMillis &&
                latitude == other.latitude &&
                longitude == other.longitude &&
                altitude == other.altitude &&
                speed == other.speed &&
                bearing == other.bearing &&
                latLonSd == other.latLonSd;
        }
        return false;
    }

    public String toString() {
        return String.format(
            Locale.US, "%s: (%+.5f, %+.5f, %+.0f m), %.1f m/s brg %.0f, sd=%.0f m",
            Utils.formatUtcTimeMillis(timeMillis),
            latitude, longitude, altitude, speed, bearing, latLonSd
        );
    }

    public LocationFix withTime(long timeMillis) {
        return new LocationFix(timeMillis, this.latitude, this.longitude,
            this.altitude, this.speed, this.bearing, this.latLonSd);
    }

    private double hav(double theta) {
        double s = Math.sin(theta / 2);
        return s * s;
    }

    /** Estimates the ground distance in meters between fixes, accurate to 0.5%. */
    public double distanceTo(LocationFix other) {
        double lat1 = latitude * RADIANS_PER_DEGREE;
        double lat2 = other.latitude * RADIANS_PER_DEGREE;
        double lon1 = longitude * RADIANS_PER_DEGREE;
        double lon2 = other.longitude * RADIANS_PER_DEGREE;

        double angularDistance = 2 * Math.asin(Math.sqrt(
            hav(lat2 - lat1) + Math.cos(lat1) * Math.cos(lat2) * hav(lon2 - lon1)
        ));

        return MEAN_RADIUS * angularDistance;
    }
}
