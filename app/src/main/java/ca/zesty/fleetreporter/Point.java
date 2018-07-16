package ca.zesty.fleetreporter;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Locale;

/** A data class for reporting location information about a tracked entity.

    At any given moment in time, a tracked entity is considered to be in a
    "resting" state or a "moving" state.  A "segment" is a period of continuous
    rest or continuous movement; a new segment begins at each state transition.

    The period of time from lastTransitionMillis to fix.timeMillis is the
    segment leading up to this Point (lastTransitionMillis <= fix.timeMillis).

    A "resting point" indicates that the entity is resting both before and after.
    A "moving point" indicates that the entity is moving both before and after.
    A "go point" is a transition from resting to moving.
    A "stop point" is a transition from moving to resting.

    The semantics of the times in a Point are as follows:
      - All timestamps are in GPS time.
      - As sent to the server, the timestamp is precise only to whole seconds.
      - The timestamp in whole seconds is to be treated as a unique key.
      - A point with a given timestamp overrides any points previously
        received by the server with the same timestamp.
      - A resting point overrides any points previously received by the server
        that have timestamps between lastTransitionMillis and timeMillis.
 */
public class Point implements Parcelable {
    public final LocationFix fix;
    enum Type { RESTING, MOVING, GO, STOP };
    public final Type type;
    public final long lastTransitionMillis;  // ms since 1970-01-01 00:00:00 UTC

    public Point(LocationFix fix, Type type, Long lastTransitionMillis) {
        this.fix = fix;
        this.type = type;
        this.lastTransitionMillis = lastTransitionMillis == null ?
            fix.timeMillis : Math.min(fix.timeMillis, lastTransitionMillis);
    }

    public boolean equals(Object otherObject) {
        if (!(otherObject instanceof Point)) return false;
        Point other = (Point) otherObject;
        return fix.equals(other.fix) &&
            lastTransitionMillis == other.lastTransitionMillis &&
            type == other.type;
    }

    public boolean isTransition() {
        return type == Type.GO || type == Type.STOP;
    }

    public long getSeconds() {
        return fix.timeMillis / 1000;
    }

    public long getSegmentMillis() {
        return fix.timeMillis - lastTransitionMillis;
    }
    public long getSegmentSeconds() {
        return getSegmentMillis() / 1000;
    }

    public Point withFix(LocationFix fix) {
        return new Point(fix, this.type, this.lastTransitionMillis);
    }

    /** Formats a point for readability and debugging. */
    public String toString() {
        return String.format(Locale.US, "<%s, %s %d s%s>", fix,
            (type == Type.RESTING || type == Type.GO) ? "rested" : "moved",
            getSegmentSeconds(),
            type == Type.GO ? ", go" : type == Type.STOP ? ", stop" : ""
        );
    }

    /** Formats a point into a string of at most 67 characters. */
    public String format() {
        return String.format(Locale.US, "%s;%+.5f;%+.5f;%+d;%d;%d;%d;%d%s",
            Utils.formatUtcTimeSeconds(fix.timeMillis),  // 20 chars
            Utils.clamp(-90, 90, fix.latitude),  // degrees, 9 chars
            Utils.clamp(-180, 180, fix.longitude),  // degrees, 10 chars
            Utils.clamp(-9999, 9999, Math.round(fix.altitude)),  // meters, 5 chars
            Utils.clamp(0, 999, Math.round(fix.speedKmh)),  // km/h, 3 chars
            Utils.clamp(0, 360, Math.round(fix.bearing)) % 360,  // degrees, 3 chars
            Utils.clamp(0, 9999, Math.round(fix.latLonSd)),  // meters, 4 chars
            Utils.clamp(0, 99999, getSegmentSeconds()),  // seconds, 5 chars
            type.name().substring(0, 1).toLowerCase()  // 1 char
        );  // length <= 20 + 9 + 10 + 5 + 3 + 3 + 4 + 5 + 1 + 7 separators = 67
    }

    public static Parcelable.Creator CREATOR = new Parcelable.Creator<Point>() {
        public Point createFromParcel(Parcel parcel) {
            return new Point(
                (LocationFix) parcel.readParcelable(LocationFix.class.getClassLoader()),
                Type.valueOf(parcel.readString()),
                parcel.readLong()
            );
        }

        public Point[] newArray(int n) {
            return new Point[n];
        }
    };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(fix, 0);
        out.writeString(type.name());
        out.writeLong(lastTransitionMillis);
    }
}
