package ca.zesty.fleetreporter;

import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.util.Log;

/** A LocationListener that estimates whether the GPS receiver is resting or
    moving, and converts each received Location to an appropriate LocationFix.
 */
public class MotionListener implements LocationListener {
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

    private Long mStableStartMillis = null;  // non-null iff the last Location was stable
    private Location mStableLocation = null;  // last stable Location (time is unused)

    private Long mRestingStartMillis = null;  // non-null means our state is "resting"
    private Long mMovingStartMillis = null;  // non-null means our state is "moving"

    private final LocationFixListener mTarget;

    /** Creates a MotionListener that sends LocationFixes to target. */
    public MotionListener(LocationFixListener target) {
        mTarget = target;
    }

    @Override public void onLocationChanged(Location loc) {
        Log.i(TAG, "onLocationChanged: " + loc);
        long timeMillis = loc.getTime();

        // Decide if the location is stable, and note the time it became stable.
        if (isStable(loc)) {
            if (mStableStartMillis == null || !nearStableLocation(mStableLocation, loc)) {
                mStableStartMillis = timeMillis;  // begin a new stable period
                mStableLocation = loc;
            }
            if (loc.getAccuracy() < mStableLocation.getAccuracy()) {
                mStableLocation = loc;  // keep the most accurate location
            }
        } else {
            mStableStartMillis = null;
        }

        // Decide if we need to transition to resting or moving.
        LocationFix fix = null;
        if (mStableStartMillis != null && mStableLocation != null &&
            timeMillis - mStableStartMillis > STABLE_MIN_MILLIS) {
            if (mRestingStartMillis == null) {  // transition to resting
                // The resting segment actually started a little bit in the past,
                // at mStableStartMillis; indicate that motion ended at that time.
                if (mMovingStartMillis != null) {
                    fix = LocationFix.createMovingEnd(
                        mStableStartMillis, mStableLocation, mMovingStartMillis);
                }
                mRestingStartMillis = mStableStartMillis;
                mMovingStartMillis = null;
            }
        } else {
            if (mMovingStartMillis == null) {  // transition to moving
                if (mRestingStartMillis != null && mStableLocation != null) {
                    fix = LocationFix.createRestingEnd(
                        timeMillis, mStableLocation, mRestingStartMillis);
                }
                mRestingStartMillis = null;
                mMovingStartMillis = timeMillis;
            }
        }

        // If we haven't created a special LocationFix for a transition, make
        // a normal resting or moving LocationFix.
        if (fix == null && mRestingStartMillis != null) {
            fix = LocationFix.createResting(timeMillis, mStableLocation, mRestingStartMillis);
        }
        if (fix == null && mMovingStartMillis != null) {
            fix = LocationFix.createMoving(timeMillis, loc, mMovingStartMillis);
        }

        // Emit the LocationFix.
        if (fix != null) mTarget.onLocationFix(fix);
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override public void onProviderEnabled(String provider) { }

    @Override public void onProviderDisabled(String provider) { }

    private boolean isStable(Location loc) {
        return loc.getSpeed() < STABLE_MAX_SPEED && loc.getAccuracy() < STABLE_MAX_ACCURACY;
    }

    private boolean nearStableLocation(Location stableLoc, Location loc) {
        return stableLoc.distanceTo(loc) < STABLE_MAX_DISTANCE;
    }
}
