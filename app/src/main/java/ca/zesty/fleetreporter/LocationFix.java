package ca.zesty.fleetreporter;

import android.location.Location;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** A data class for location fixes as stored and transmitted.

    Each fix is either a resting fix or a moving fix, as indicated by isResting.
    A "segment" is a period of time of continuous rest or continuous movement,
    which extends from segmentStartMillis to timeMillis (it is always the case
    that segmentStartMillis <= timeMillis).  isSegmentEnd indicates that the fix
    is at the end of a segment, at a transition between resting and moving.

    The semantics of the times in a LocationFix are as follows:
      - All timestamps are in GPS time.
      - As sent to the server, the timestamp is precise only to whole seconds.
      - The timestamp in whole seconds is to be treated as a unique key.
      - A fix with a given timestamp overrides any fixes previously received by
        the server with the same timestamp.
      - A fix with a resting segment overrides any fixes previously received by
        the server that have timestamps within that segment.
 */
public class LocationFix {
    private static final SimpleDateFormat RFC3339_UTC;
    static {
        RFC3339_UTC = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        RFC3339_UTC.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public final long timeMillis;  // ms since 1970-01-01 00:00:00 UTC
    public final double latitude;  // degrees
    public final double longitude;  // degrees
    public final double altitude;  // meters
    public final double speed;  // meters per second
    public final double bearing;  // degrees
    public final double latLonSd;  // 68% confidence radius of lat/lon position, meters
    public final long segmentStartMillis;  // ms since 1970-01-01 00:00:00 UTC
    public final boolean isResting;  // whether the segment is resting or moving
    public final boolean isSegmentEnd;  // whether at a transition between resting and moving

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

    public static LocationFix create(long timeMillis, Location loc,
        long segmentStartMillis, boolean isResting, boolean isSegmentEnd) {
        return new LocationFix(
            timeMillis, loc.getLatitude(), loc.getLongitude(), loc.getAltitude(),
            loc.getSpeed(), loc.getBearing(), loc.getAccuracy(),
            segmentStartMillis, isResting, isSegmentEnd
        );
    }

    public static LocationFix createResting(
        long timeMillis, Location loc, long restingStartMillis) {
        return create(timeMillis, loc, restingStartMillis, true, false);
    }

    public static LocationFix createRestingEnd(
        long timeMillis, Location loc, long restingStartMillis) {
        return create(timeMillis, loc, restingStartMillis, true, true);
    }

    public static LocationFix createMoving(
        long timeMillis, Location loc, long movingStartMillis) {
        return create(timeMillis, loc, movingStartMillis, false, false);
    }

    public static LocationFix createMovingEnd(
        long timeMillis, Location loc, long movingStartMillis) {
        return create(timeMillis, loc, movingStartMillis, false, true);
    }

    public boolean equals(LocationFix other) {
        return timeMillis == other.timeMillis &&
            latitude == other.latitude &&
            longitude == other.longitude &&
            altitude == other.altitude &&
            speed == other.speed &&
            bearing == other.bearing &&
            latLonSd == other.latLonSd &&
            segmentStartMillis == other.segmentStartMillis &&
            isResting == other.isResting &&
            isSegmentEnd == other.isSegmentEnd;
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

    /** Formats a location fix into a string of at most 68 characters. */
    public String format() {
        return String.format(Locale.US, "%s;%+.5f;%+.5f;%+d;%d;%d;%d;%d%s",
            RFC3339_UTC.format(new Date(timeMillis)).substring(0, 20),
            Utils.clamp(-90, 90, latitude),  // degrees, 9 chars
            Utils.clamp(-180, 180, longitude),  // degrees, 10 chars
            Utils.clamp(-9999, 9999, Math.round(altitude)),  // meters, 5 chars
            Utils.clamp(0, 999, Math.round(speed * 3.6)),  // km/h, 3 chars
            Utils.clamp(0, 360, Math.round(bearing)) % 360,  // degrees, 3 chars
            Utils.clamp(0, 9999, Math.round(latLonSd)),  // meters, 4 chars
            Utils.clamp(0, 99999, (timeMillis - segmentStartMillis) / 1000),  // seconds, 5 chars
            isSegmentEnd ? (isResting ? "RM" : "MR") : (isResting ? "R" : "M")  // 2 chars
        );  // length <= 20 + 9 + 10 + 5 + 3 + 3 + 4 + 4 + 2 + 7 separators = 68
    }
}
