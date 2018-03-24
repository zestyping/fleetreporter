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
    // the Location readings have been stable and close together (within
    // STABLE_MAX_DISTANCE) for a period of time (STABLE_MIN_MILLIS).

    private static final double STABLE_MAX_ACCURACY = 25.0;  // meters
    private static final double STABLE_MAX_SPEED = 2.0;  // meters per second
    private static final long STABLE_MIN_MILLIS = 60000;  // one minute
    private static final double STABLE_MAX_DISTANCE = 10.0;  // meters

    private long mStableStartMillis = -1;  // >= 0 iff the last Location was stable
    private Location mStableLoc = null;  // non-null iff the last Location was stable

    private long mRestingStartMillis = -1;  // >= 0 means our state is "resting"
    private long mMovingStartMillis = -1;  // >= 0 means our state is "moving"

    private final LocationFixListener mTarget;

    /** Creates a MotionListener that sends LocationFixes to target. */
    public MotionListener(LocationFixListener target) {
        mTarget = target;
    }

    @Override public void onLocationChanged(Location loc) {
        Log.i(TAG, "onLocationChanged: " + loc);

        // Decide if the location is stable, and note the time it became stable.
        if (isStable(loc)) {
            if (mStableLoc == null || !nearStableLocation(mStableLoc, loc)) {
                mStableLoc = loc;
                mStableStartMillis = loc.getTime();  // begin a new stable period
            }
            if (loc.getAccuracy() < mStableLoc.getAccuracy()) {
                mStableLoc = loc;  // keep the most accurate location
            }
            mStableLoc.setTime(loc.getTime());  // use the up-to-date fix time
        } else {
            mStableLoc = null;
            mStableStartMillis = -1;
        }

        // Decide if we are resting or moving, based on how long we've been stable.
        if (mStableLoc != null && loc.getTime() - mStableStartMillis > STABLE_MIN_MILLIS) {
            if (mRestingStartMillis < 0) {
                mRestingStartMillis = loc.getTime();
                mMovingStartMillis = -1;
            }
        } else {
            if (mMovingStartMillis < 0) {
                mRestingStartMillis = -1;
                mMovingStartMillis = loc.getTime();
            }
        }

        // Emit a resting or moving LocationFix.
        mTarget.onLocationFix(
            mRestingStartMillis >= 0 ?
            LocationFix.createResting(mStableLoc, mRestingStartMillis / 1000) :
            LocationFix.createMoving(loc, mMovingStartMillis / 1000)
        );
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
