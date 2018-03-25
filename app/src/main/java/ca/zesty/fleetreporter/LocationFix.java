package ca.zesty.fleetreporter;

import android.location.Location;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** A data class for location fixes as stored and transmitted.

    Each fix is either a resting fix or a moving fix, as indicated by isResting.
    A "segment" is a period of time of continuous rest or continuous movement;
    segmentStartMillis is always less than or equal to timeMillis, and it
    indicates how long the unit has been in its current resting or moving state.
    isSegmentEnd indicates that the fix is at the end of a segment, that is,
    a transition from resting to moving or from moving to resting.
 */
public class LocationFix {
    private static final SimpleDateFormat RFC3339_UTC;
    static {
        RFC3339_UTC = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        RFC3339_UTC.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public final long timeMillis;  // ms since 1970-01-01 00:00:00 UTC
    public final long segmentStartMillis;  // ms since 1970-01-01 00:00:00 UTC
    public final boolean isResting;
    public final boolean isSegmentEnd;
    public final double latitude;  // degrees
    public final double longitude;  // degrees
    public final double altitude;  // meters
    public final double speed;  // meters per second
    public final double bearing;  // degrees
    public final double latLonSd;  // 68% confidence radius of lat/lon position, meters

    private LocationFix(
        long timeMillis, double latitude, double longitude, double altitude,
        double speed, double bearing, double latLonSd,
        long segmentStartMillis, boolean isResting, boolean isSegmentEnd
    ) {
        this.timeMillis = timeMillis;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.speed = speed;
        this.bearing = bearing;
        this.latLonSd = latLonSd;
        this.segmentStartMillis = Math.min(timeMillis, segmentStartMillis);
        this.isResting = isResting;
        this.isSegmentEnd = isSegmentEnd;
    }

    public static LocationFix create(
        Location loc, long segmentStartMillis, boolean isResting, boolean isSegmentEnd) {
        return new LocationFix(
            loc.getTime(), loc.getLatitude(), loc.getLongitude(), loc.getAltitude(),
            loc.getSpeed(), loc.getBearing(), loc.getAccuracy(),
            segmentStartMillis, isResting, isSegmentEnd
        );
    }

    public static LocationFix createResting(Location loc, long restingStartMillis) {
        return create(loc, restingStartMillis, true, false);
    }

    public static LocationFix createRestingEnd(Location loc, long restingStartMillis) {
        return create(loc, restingStartMillis, true, true);
    }

    public static LocationFix createMoving(Location loc, long movingStartMillis) {
        return create(loc, movingStartMillis, false, false);
    }

    public static LocationFix createMovingEnd(Location loc, long movingStartMillis) {
        return create(loc, movingStartMillis, false, true);
    }

    public long getSeconds() {
        return timeMillis / 1000;
    }

    /** Formats a location fix for readability and debugging. */
    public String toString() {
        return String.format(
            Locale.US, "<%s: (%+.5f, %+.5f, %+.0f m), %.1f m/s brg %.0f, sd=%.0f m, %s %d s%s>",
            RFC3339_UTC.format(new Date(timeMillis)),
            latitude, longitude, altitude, speed, bearing, latLonSd,
            isResting ? "resting" : "moving",
            (timeMillis - segmentStartMillis) / 1000,
            isSegmentEnd ? " (end)" : ""
        );
    }

    /** Formats a location fix into a string of at most 67 characters. */
    public String format() {
        return String.format(Locale.US, "%s;%+.5f;%+.5f;%+d;%d;%d;%d;%d%s",
            RFC3339_UTC.format(new Date(timeMillis)).substring(0, 20),
            Utils.clamp(-90, 90, latitude),  // degrees, 9 chars
            Utils.clamp(-180, 180, longitude),  // degrees, 10 chars
            Utils.clamp(-9999, 9999, Math.round(altitude)),  // meters, 5 chars
            Utils.clamp(0, 999, Math.round(speed * 3.6)),  // km/h, 3 chars
            Utils.clamp(0, 360, Math.round(bearing)) % 360,  // degrees, 3 chars
            Utils.clamp(0, 9999, Math.round(latLonSd)),  // meters, 4 chars
            Utils.clamp(0, 9999, (timeMillis - segmentStartMillis) / 60000),  // minutes, 4 chars
            isSegmentEnd ? (isResting ? "RM" : "MR") : (isResting ? "R" : "M")  // 2 chars
        );  // length <= 20 + 9 + 10 + 5 + 3 + 3 + 4 + 4 + 2 + 7 separators = 67
    }
}
