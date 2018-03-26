package ca.zesty.fleetreporter;

import android.util.Log;

/** A LocationListener that estimates whether the GPS receiver is resting or
    moving, and converts each received Location to an appropriate Point.
 */
public class MotionListener implements LocationFixListener {
    private static final String TAG = "MotionListener";

    // Note the terminology "stable" versus "resting": "stable" is a property
    // of an individual location, whereas "resting"/"moving" describes the
    // state of the MotionListener as a whole.
    //
    // An individual Location is deemed "stable" if its confidence radius is
    // small enough (< STABLE_MAX_ACCURACY) and its "speed" field is close
    // enough to zero (< STABLE_MAX_SPEED).
    //
    // The MotionListener decides that the GPS receiver is "resting" if all
    // the Location readings for a period of time (STABLE_MIN_MILLIS) have
    // been stable and close together (within STABLE_MAX_DISTANCE).

    private static final double STABLE_MAX_ACCURACY = 50.0;  // meters
    private static final double STABLE_MAX_SPEED = 2.0;  // meters per second
    private static final long STABLE_MIN_MILLIS = 60 * 1000;  // one minute
    private static final double STABLE_MAX_DISTANCE = 20.0;  // meters
    private static final double GOOD_ENOUGH_ACCURACY = 10.0;  // meters

    private Long mStableStartMillis = null;  // non-null iff the last LocationFix was stable
    private LocationFix mStableFix = null;  // last stable LocationFix (time is unused)

    private Long mRestingStartMillis = null;  // non-null means our state is "resting"
    private Long mMovingStartMillis = null;  // non-null means our state is "moving"

    private final PointListener mTarget;

    /** Creates a MotionListener that sends Points to a PointListener. */
    public MotionListener(PointListener target) {
        mTarget = target;
    }

    @Override public void onLocationFix(LocationFix fix) {
        Log.i(TAG, "onLocationFix: " + fix);
        if (fix == null) return;

        // Decide if the location is stable, and note the time it became stable.
        if (isStable(fix)) {
            if (mStableStartMillis == null || !nearStableFix(mStableFix, fix)) {
                mStableStartMillis = fix.timeMillis;  // begin a new stable period
                mStableFix = fix;
            }
            // If the stable fix isn't that accurate, and we get a more accurate
            // fix, we'd like to improve the stable fix.  But if we keep adjusting
            // the stable fix too much, it will drift to follow the new fix,
            // which would allow the new fix to travel much farther than
            // STABLE_MAX_DISTANCE without being detected as motion.  So, once
            // the stable fix reaches "good enough" accuracy, stop adjusting.
            if (mStableFix.latLonSd > GOOD_ENOUGH_ACCURACY &&
                fix.latLonSd < mStableFix.latLonSd) {
                mStableFix = fix;
            }
        } else {
            mStableStartMillis = null;
        }

        // Decide if we need to transition to resting or moving.
        Point point = null;
        if (mStableStartMillis != null && mStableFix != null &&
            fix.timeMillis - mStableStartMillis >= STABLE_MIN_MILLIS) {
            if (mRestingStartMillis == null) {  // transition to resting
                // The resting segment actually started a little bit in the past,
                // at mStableStartMillis; indicate that motion ended at that time.
                if (mMovingStartMillis != null) {
                    point = Point.createMovingEnd(
                        mStableFix.withTime(mStableStartMillis), mMovingStartMillis);
                }
                mRestingStartMillis = mStableStartMillis;
                mMovingStartMillis = null;
            }
        } else {
            if (mMovingStartMillis == null) {  // transition to moving
                if (mRestingStartMillis != null && mStableFix != null) {
                    point = Point.createRestingEnd(
                        mStableFix.withTime(fix.timeMillis), mRestingStartMillis);
                }
                mRestingStartMillis = null;
                mMovingStartMillis = fix.timeMillis;
            }
        }

        // If we haven't created a special Point for a transition, make
        // a normal resting or moving Point.
        if (point == null && mRestingStartMillis != null) {
            point = Point.createResting(mStableFix.withTime(fix.timeMillis), mRestingStartMillis);
        }
        if (point == null && mMovingStartMillis != null) {
            // Emit a moving Point only if we're not waiting to stabilize.
            if (mStableStartMillis == null || fix.timeMillis == mStableStartMillis) {
                point = Point.createMoving(fix, mMovingStartMillis);
            }
        }

        // Emit the Point.
        if (point != null) mTarget.onPoint(point);
    }

    private boolean isStable(LocationFix fix) {
        return fix.speed < STABLE_MAX_SPEED && fix.latLonSd < STABLE_MAX_ACCURACY;
    }

    private boolean nearStableFix(LocationFix stableFix, LocationFix fix) {
        return stableFix.distanceTo(fix) < STABLE_MAX_DISTANCE;
    }
}
