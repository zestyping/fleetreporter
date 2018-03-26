package ca.zesty.fleetreporter;

import java.util.Locale;

/** A data class for location fixes as stored and transmitted.

    Each point is either a resting point or a moving point, as indicated by
    isResting.  A "segment" is a period of time of continuous rest or continuous
    movement, which extends from segmentStartMillis to timeMillis (it is always
    true that segmentStartMillis <= timeMillis).  isSegmentEnd indicates that the
    point is at the end of a segment, at a transition between resting and moving.

    The semantics of the times in a Point are as follows:
      - All timestamps are in GPS time.
      - As sent to the server, the timestamp is precise only to whole seconds.
      - The timestamp in whole seconds is to be treated as a unique key.
      - A point with a given timestamp overrides any points previously
        received by the server with the same timestamp.
      - A point with a resting segment overrides any points previously
        received by the server that have timestamps within that segment.
 */
public class Point {
    public final LocationFix fix;
    public final long segmentStartMillis;  // ms since 1970-01-01 00:00:00 UTC
    public final boolean isResting;  // whether the segment is resting or moving
    public final boolean isSegmentEnd;  // whether at a transition between resting and moving

    private Point(LocationFix fix, long segmentStartMillis, boolean isResting, boolean isSegmentEnd) {
        this.fix = fix;
        this.segmentStartMillis = Math.min(fix.timeMillis, segmentStartMillis);
        this.isResting = isResting;
        this.isSegmentEnd = isSegmentEnd;
    }

    public static Point createResting(LocationFix fix, long restingStartMillis) {
        return new Point(fix, restingStartMillis, true, false);
    }

    public static Point createRestingEnd(LocationFix fix, long restingStartMillis) {
        return new Point(fix, restingStartMillis, true, true);
    }

    public static Point createMoving(LocationFix fix, long movingStartMillis) {
        return new Point(fix, movingStartMillis, false, false);
    }

    public static Point createMovingEnd(LocationFix fix, long movingStartMillis) {
        return new Point(fix, movingStartMillis, false, true);
    }

    public boolean equals(Object otherObject) {
        if (otherObject instanceof Point) {
            Point other = (Point) otherObject;
            return fix.equals(other.fix) &&
                segmentStartMillis == other.segmentStartMillis &&
                isResting == other.isResting &&
                isSegmentEnd == other.isSegmentEnd;
        }
        return false;
    }

    public long getSeconds() {
        return fix.timeMillis / 1000;
    }

    public long getSegmentSeconds() {
        return (fix.timeMillis - segmentStartMillis) / 1000;
    }

    /** Formats a point for readability and debugging. */
    public String toString() {
        return String.format(Locale.US, "<%s, %s %d s%s>", fix,
            isResting ? "rested" : "moved", getSegmentSeconds(),
            isSegmentEnd ? (isResting ? ", go" : ", stop") : ""
        );
    }

    /** Formats a point into a string of at most 67 characters. */
    public String format() {
        return String.format(Locale.US, "%s;%+.5f;%+.5f;%+d;%d;%d;%d;%d%s",
            Utils.formatUtcTimestamp(fix.timeMillis),  // 20 chars
            Utils.clamp(-90, 90, fix.latitude),  // degrees, 9 chars
            Utils.clamp(-180, 180, fix.longitude),  // degrees, 10 chars
            Utils.clamp(-9999, 9999, Math.round(fix.altitude)),  // meters, 5 chars
            Utils.clamp(0, 999, Math.round(fix.speed * 3.6)),  // km/h, 3 chars
            Utils.clamp(0, 360, Math.round(fix.bearing)) % 360,  // degrees, 3 chars
            Utils.clamp(0, 9999, Math.round(fix.latLonSd)),  // meters, 4 chars
            Utils.clamp(0, 99999, getSegmentSeconds()),  // seconds, 5 chars
            isSegmentEnd ? (isResting ? "g" : "s") : (isResting ? "r" : "m")  // 1 chars
        );  // length <= 20 + 9 + 10 + 5 + 3 + 3 + 4 + 5 + 1 + 7 separators = 67
    }
}
