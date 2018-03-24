package ca.zesty.fleetreporter;

import android.location.Location;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** A data class for location fixes as stored and transmitted.

    Each fix is either a resting fix or a moving fix.  In a resting fix,
    restingStartTime is nonnegative and indicates how long the GPS receiver has
    been stationary.  In a moving fix, movingStartTime is nonnegative and
    indicates how long the GPS receiver has not been stationary.  In all cases,
    exactly one of restingStartTime and movingStartTime is nonnegative, and
    both fields are <= fixTime.

    All time fields in this interface are in seconds, not milliseconds.
 */
public class LocationFix {
    private static final SimpleDateFormat RFC3339_UTC;
    static {
        RFC3339_UTC = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        RFC3339_UTC.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public final long fixTime;  // seconds since 1970-01-01 00:00:00 UTC
    public final long restingStartTime;  // seconds since 1970-01-01 00:00:00 UTC
    public final long movingStartTime;  // seconds since 1970-01-01 00:00:00 UTC
    public final double latitude;  // degrees
    public final double longitude;  // degrees
    public final double altitude;  // meters
    public final double speed;  // meters per second
    public final double bearing;  // degrees
    public final double latLonSd;  // 68% confidence radius of lat/lon position, meters

    private LocationFix(
        long fixTime, long restingStartTime, long movingStartTime,
        double latitude, double longitude, double altitude,
        double speed, double bearing, double latLonSd
    ) {
        this.fixTime = fixTime;
        this.restingStartTime = Math.min(fixTime, restingStartTime);
        this.movingStartTime = Math.min(fixTime, movingStartTime);
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.speed = speed;
        this.bearing = bearing;
        this.latLonSd = latLonSd;
    }

    public static LocationFix createResting(Location loc, long restingStartTime) {
        long fixTime = loc.getTime() / 1000;
        return new LocationFix(
            fixTime, restingStartTime, -1,
            loc.getLatitude(), loc.getLongitude(), loc.getAltitude(),
            loc.getSpeed(), loc.getBearing(), loc.getAccuracy()
        );
    }

    public static LocationFix createMoving(Location loc, long movingStartTime) {
        long fixTime = loc.getTime() / 1000;
        return new LocationFix(
            fixTime, -1, movingStartTime,
            loc.getLatitude(), loc.getLongitude(), loc.getAltitude(),
            loc.getSpeed(), loc.getBearing(), loc.getAccuracy()
        );
    }

    public long getMillis() {
        return fixTime * 1000;
    }

    public boolean isResting() {
        return restingStartTime >= 0;
    }

    public long getRestingSeconds() {
        return isResting() ? fixTime - restingStartTime : 0;
    }

    public long getMovingSeconds() {
        return !isResting() ? fixTime - movingStartTime : 0;
    }

    public String toString() {
        return String.format(
            Locale.US, "<%s: (%+.4f, %+.4f, %+.0f m), %.1f m/s brg %.0f, sd=%.0f m, %s>",
            RFC3339_UTC.format(new Date(fixTime * 1000)),
            latitude, longitude, altitude, speed, bearing, latLonSd,
            isResting() ?
                "resting " + (fixTime - restingStartTime) + " s" :
                "moving " + (fixTime - movingStartTime) + " s"
        );
    }

    /** Formats a location fix into a string of at most 66 characters. */
    public String format() {
        return String.format(Locale.US, "%s;%+.5f;%+.5f;%+d;%d;%d;%+d;%d",
            RFC3339_UTC.format(new Date(fixTime * 1000)).substring(0, 20),
            Utils.clamp(-90, 90, latitude),  // degrees, 9 chars
            Utils.clamp(-180, 180, longitude),  // degrees, 10 chars
            Utils.clamp(-9999, 9999, Math.round(altitude)),  // meters, 5 chars
            Utils.clamp(0, 999, Math.round(speed * 3.6)),  // km/h, 3 chars
            Utils.clamp(0, 360, Math.round(bearing)) % 360,  // degrees, 3 chars
            Utils.clamp(-9999, 9999, isResting() ?
                -getRestingSeconds()/60 : getMovingSeconds()/60),  // minutes, 5 chars
            Utils.clamp(0, 9999, Math.round(latLonSd))  // meters, 4 chars
        );  // length <= 20 + 9 + 10 + 5 + 3 + 3 + 5 + 4 + 7 separators = 66
    }
}
