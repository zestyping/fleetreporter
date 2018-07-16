package ca.zesty.fleetreporter;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Locale;

/** A pure data class to take the place of android.location.Location, which
    cannot be used in unit tests due to its unfortunate design. */
public class LocationFix implements Parcelable {
    static final double EQUATOR_RADIUS = 6378137;  // meters from Earth center to equator
    static final double POLE_RADIUS = 6356752;  // meters from Earth center to pole
    static final double MEAN_RADIUS = (2 * EQUATOR_RADIUS + POLE_RADIUS)/3;
    static final double RADIANS_PER_DEGREE = 2 * Math.PI / 360;

    public final long timeMillis;  // ms since 1970-01-01 00:00:00 UTC
    public final double latitude;  // degrees
    public final double longitude;  // degrees
    public final double altitude;  // meters
    public final double speedKmh;  // km/h
    public final double bearing;  // degrees
    public final double latLonSd;  // 68% confidence radius of lat/lon position, meters

    public LocationFix(long timeMillis, double latitude, double longitude,
                       double altitude, double speedKmh, double bearing, double latLonSd) {
        this.timeMillis = timeMillis;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.speedKmh = speedKmh;
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
                speedKmh == other.speedKmh &&
                bearing == other.bearing &&
                latLonSd == other.latLonSd;
        }
        return false;
    }

    public String toString() {
        return String.format(
            Locale.US, "%s: (%+.5f, %+.5f, %+.0f m), %.1f km/h brg %.0f, sd=%.0f m",
            Utils.formatUtcTimeMillis(timeMillis),
            latitude, longitude, altitude, speedKmh, bearing, latLonSd
        );
    }

    public LocationFix withTime(long timeMillis) {
        return new LocationFix(timeMillis, this.latitude, this.longitude,
            this.altitude, this.speedKmh, this.bearing, this.latLonSd);
    }

    public LocationFix withSpeedAndBearing(double speedKmh, double bearing) {
        return new LocationFix(this.timeMillis, this.latitude, this.longitude,
            this.altitude, speedKmh, bearing, this.latLonSd);
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

    /** Estimates the bearing at the start of the great circle from this fix to another. */
    public double bearingTo(LocationFix other) {
        double lat1 = latitude * RADIANS_PER_DEGREE;
        double lat2 = other.latitude * RADIANS_PER_DEGREE;
        double lon1 = longitude * RADIANS_PER_DEGREE;
        double lon2 = other.longitude * RADIANS_PER_DEGREE;
        double dlon = lon2 - lon1;
        double y = Math.sin(dlon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) -
            Math.sin(lat1) * Math.cos(lat2) * Math.cos(dlon);
        double degrees = Math.atan2(y, x) / RADIANS_PER_DEGREE;
        while (degrees < 0) degrees += 360;
        while (degrees >= 360) degrees -= 360;
        return degrees;
    }

    public static Parcelable.Creator CREATOR = new Parcelable.Creator<LocationFix>() {
        public LocationFix createFromParcel(Parcel parcel) {
            return new LocationFix(
                parcel.readLong(),
                parcel.readDouble(),
                parcel.readDouble(),
                parcel.readDouble(),
                parcel.readDouble(),
                parcel.readDouble(),
                parcel.readDouble()
            );
        }

        public LocationFix[] newArray(int n) {
            return new LocationFix[n];
        }
    };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(timeMillis);
        out.writeDouble(latitude);
        out.writeDouble(longitude);
        out.writeDouble(altitude);
        out.writeDouble(speedKmh);
        out.writeDouble(bearing);
        out.writeDouble(latLonSd);
    }
}
