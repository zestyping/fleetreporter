package ca.zesty.fleetreporter;

import android.util.Log;

/** A LocationListener that estimates whether the GPS receiver is resting or
    moving, and converts each received Location to an appropriate Point.
 */
public class MotionListener implements LocationFixListener {
    static final String TAG = "MotionListener";

    // Note the terminology "stable" versus "resting": "stable" is a property
    // of an individual location, whereas "resting"/"moving" describes the
    // state of the MotionListener as a whole.
    //
    // An individual Location is deemed "stable" if its confidence radius is
    // small enough (< STABLE_MAX_ACCURACY) and its "speed" field is close
    // enough to zero (< STABLE_MAX_SPEED).
    //
    // The MotionListener enters the "resting" state if all the location fixes
    // during a "settling period" (SETTLING_PERIOD_MILLIS) have been stable and
    // close together (within RESTING_RADIUS of a selected anchor location).

    static final double STABLE_MAX_ACCURACY = 50.0;  // meters
    static final double STABLE_MAX_SPEED = 2.0;  // meters per second
    static final long SETTLING_PERIOD_MILLIS = 60 * 1000;  // one minute
    static final double RESTING_RADIUS = 20.0;  // meters
    static final double GOOD_ENOUGH_ACCURACY = 10.0;  // meters

    private final PointListener mTarget;
    private boolean isResting = false;  // current state, either "resting" or "moving"
    private Long mLastTransitionMillis = null;  // time of last state transition

    private Long mSettlingStartMillis = null;  // non-null iff the last fix was stable
    private LocationFix mAnchor = null;  // center of the resting circle (time is unused)
    private LocationFix mLastRestingFix = null;  // last fix that was in resting state

    /** Creates a MotionListener that sends Points to a PointListener. */
    public MotionListener(PointListener target) {
        mTarget = target;
    }

    @Override public void onLocationFix(LocationFix fix) {
        Log.i(TAG, "onLocationFix: " + fix);
        if (fix == null) return;
        if (mLastTransitionMillis == null) mLastTransitionMillis = fix.timeMillis;

        // Decide whether we are "settling" (waiting to see if we stay within a
        // small radius around a selected anchor point for a "settling period").
        boolean enteredSettlingPeriod = false;
        if (isStable(fix)) {
            if (mAnchor != null && withinRestingRadius(mAnchor, fix)) {
                // We're staying near the anchor; leave the anchor there and
                // keep waiting for our position to settle.
                enteredSettlingPeriod = true;
            } else {
                // We don't have an anchor or we've moved too far from the anchor;
                // drop a new anchor here and start a new settling period.
                mAnchor = fix;
                mSettlingStartMillis = fix.timeMillis;
                enteredSettlingPeriod = false;
            }
            // If we have a more accurate fix, we'd like to improve the anchor
            // position.  But if we keep adjusting the anchor too much, it will
            // drift to follow new fixes, allowing new fixes to travel much
            // farther than RESTING_RADIUS without being detected as motion.
            // So, once the anchor accuracy is "good enough", stop moving it.
            if (mAnchor.latLonSd > GOOD_ENOUGH_ACCURACY && fix.latLonSd < mAnchor.latLonSd) {
                mAnchor = fix;
            }
        } else {
            mAnchor = null;
            mSettlingStartMillis = null;
        }

        // Decide if we need to transition to resting or moving.
        boolean nextResting = mAnchor != null &&
            fix.timeMillis - mSettlingStartMillis >= SETTLING_PERIOD_MILLIS;

        if (!isResting && nextResting) {
            // The resting segment actually started a little bit in the past,
            // at mSettlingStartMillis; indicate that motion ended at that time.
            emitPoint(mAnchor.withTime(mSettlingStartMillis), Point.Type.STOP);
        } else if (isResting && !nextResting) {
            // The resting segment actually ended a little bit in the past,
            // at the last fix that met the conditions for resting.
            emitPoint(mLastRestingFix, Point.Type.GO);
        } else if (isResting) {
            emitPoint(mAnchor.withTime(fix.timeMillis), Point.Type.RESTING);
        } else if (!enteredSettlingPeriod) {
            // Emit a moving Point only if we're not waiting to settle.
            emitPoint(fix, Point.Type.MOVING);
        }

        // Advance to the new state.
        isResting = nextResting;

        // Keep track of the last anchor that met the conditions for resting.
        mLastRestingFix = isResting ? mAnchor.withTime(fix.timeMillis) : null;
    }

    private void emitPoint(LocationFix fix, Point.Type type) {
        if (mLastTransitionMillis == null) mLastTransitionMillis = fix.timeMillis;
        Point point = new Point(fix, type, mLastTransitionMillis);
        mTarget.onPoint(point);
        if (point.isTransition()) mLastTransitionMillis = fix.timeMillis;
    }

    private boolean isStable(LocationFix fix) {
        return fix.speed < STABLE_MAX_SPEED && fix.latLonSd < STABLE_MAX_ACCURACY;
    }

    private boolean withinRestingRadius(LocationFix fix1, LocationFix fix2) {
        return fix1.distanceTo(fix2) < RESTING_RADIUS;
    }
}
