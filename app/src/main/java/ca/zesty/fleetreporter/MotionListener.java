package ca.zesty.fleetreporter;

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
    // during a "settling period" have been stable and close together (within
    // RESTING_RADIUS of a selected anchor location).

    static final double STABLE_MAX_ACCURACY = 50.0;  // meters
    static final double STABLE_MAX_SPEED = 5.0;  // km/h
    static final double RESTING_RADIUS = 20.0;  // meters
    static final double GOOD_ENOUGH_ANCHOR_ACCURACY = 10.0;  // meters

    // Accuracy must be better than STABLE_MAX_ACCURACY to settle into a
    // resting state.  Once resting, the MotionListener will allow a looser
    // accuracy level if the position remains more tightly confined.

    static final double RESTING_LOOSE_ACCURACY = 100.0;  // meters
    static final double RESTING_TIGHT_RADIUS = 10.0;  // meters

    private final PointListener mTarget;
    private final Getter<Long> mGetSettlingPeriodMillis;
    private boolean isResting = false;  // current state, either "resting" or "moving"
    private Long mLastTransitionMillis = null;  // time of last state transition

    private Long mSettlingStartMillis = null;  // non-null iff the last fix was stable
    private LocationFix mAnchor = null;  // center of the resting circle (time is unused)
    private LocationFix mLastRestingFix = null;  // last fix that was in resting state

    /** Creates a MotionListener that sends Points to a PointListener. */
    public MotionListener(PointListener target, Getter<Long> getSettlingPeriodMillis) {
        mTarget = target;
        mGetSettlingPeriodMillis = getSettlingPeriodMillis;
    }

    @Override public void onFix(LocationFix fix) {
        String description = "" + fix;
        if (fix != null) description += mAnchor == null ? " (no anchor)" :
            Utils.format(" (%.1f m from anchor, settled %d s)",
                fix.distanceTo(mAnchor), (fix.timeMillis - mSettlingStartMillis) / 1000);
        Utils.log(TAG, "onFix: " + description);
        if (fix == null) {
            emitNullPoint();
            return;
        }
        if (mLastTransitionMillis == null) mLastTransitionMillis = fix.timeMillis;

        // Decide whether we are "settling" (waiting to see if we stay within a
        // small radius around a selected anchor point for a "settling period").
        boolean enteredSettlingPeriod = false;
        if (isStable(fix) || isContinuingToRest(mAnchor, fix)) {
            if (withinRestingRadius(mAnchor, fix)) {
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
            if (mAnchor.latLonSd > GOOD_ENOUGH_ANCHOR_ACCURACY && fix.latLonSd < mAnchor.latLonSd) {
                mAnchor = fix;
            }
        } else {
            mAnchor = null;
            mSettlingStartMillis = null;
        }

        // Decide if we need to transition to resting or moving.
        boolean nextResting = mAnchor != null &&
            fix.timeMillis - mSettlingStartMillis >= mGetSettlingPeriodMillis.get();

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
        } else {
            // A moving Point is provisional if we're waiting to settle.
            emitPoint(fix, Point.Type.MOVING, enteredSettlingPeriod);
        }

        // Advance to the new state.
        isResting = nextResting;

        // Keep track of the last anchor that met the conditions for resting.
        mLastRestingFix = isResting ? mAnchor.withTime(fix.timeMillis) : null;
    }

    private void emitNullPoint() {
        mTarget.onPoint(null, false);
    }

    private void emitPoint(LocationFix fix, Point.Type type) {
        emitPoint(fix, type, false);
    }

    private void emitPoint(LocationFix fix, Point.Type type, boolean isProvisional) {
        if (mLastTransitionMillis == null) mLastTransitionMillis = fix.timeMillis;
        Point point = new Point(fix, type, mLastTransitionMillis);
        mTarget.onPoint(point, isProvisional);
        if (point.isTransition()) mLastTransitionMillis = fix.timeMillis;
    }

    private boolean isStable(LocationFix fix) {
        return fix.speedKmh < STABLE_MAX_SPEED && fix.latLonSd < STABLE_MAX_ACCURACY;
    }

    private boolean isContinuingToRest(LocationFix anchor, LocationFix fix) {
        // Once resting is established, the resting state can continue with a
        // looser accuracy bound in exchange for a tighter position bound.
        // This corner case was added because we observed GPS readings with
        // very little position change (< 3 m) while the accuracy value slowly
        // increased from 30 m to 80-90 m, for an entirely stationary reporter.
        return withinRestingTightRadius(anchor, fix) && fix.latLonSd < RESTING_LOOSE_ACCURACY;
    }

    private boolean withinRestingRadius(LocationFix fix1, LocationFix fix2) {
        return fix1 != null && fix2 != null && fix1.distanceTo(fix2) < RESTING_RADIUS;
    }

    private boolean withinRestingTightRadius(LocationFix fix1, LocationFix fix2) {
        return fix1 != null && fix2 != null && fix1.distanceTo(fix2) < RESTING_TIGHT_RADIUS;
    }
}
