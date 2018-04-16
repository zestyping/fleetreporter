package ca.zesty.fleetreporter;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationProvider;
import android.os.Bundle;

/** A LocationListener that converts Locations into LocationFixes, emits nulls
    when the GPS provider is disabled or cannot determine the location, and can
    re-emit the last fix (with an updated timestamp) on command.
 */
public class LocationAdapter implements LocationListener {
    private final LocationFixListener mTarget;
    private LocationFix mLastFix = null;
    private long mGpsTimeOffsetMillis = 0;

    public LocationAdapter(LocationFixListener target) {
        mTarget = target;
    }

    @Override public void onLocationChanged(Location location) {
        // The phone's clock could be inaccurate.  Whenever we get a Location,
        // we can estimate the offset between the phone's clock and GPS time,
        // and this allows us to use estimated GPS time for all stored times and
        // for scheduling all timed actions (see all uses of getGpsTimeMillis()).
        mGpsTimeOffsetMillis = location.getTime() - System.currentTimeMillis();
        mTarget.onLocationFix(new LocationFix(
            location.getTime(),
            location.getLatitude(), location.getLongitude(), location.getAltitude(),
            location.getSpeed(), location.getBearing(), location.getAccuracy()
        ));
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {
        if (status != LocationProvider.AVAILABLE) onGpsSignalLost();
    }

    @Override public void onProviderEnabled(String provider) { }

    @Override public void onProviderDisabled(String provider) {
        onGpsSignalLost();
    }

    public void onGpsSignalLost() {
        mTarget.onLocationFix(null);
    }

    public long getGpsTimeMillis() {
        return System.currentTimeMillis() + mGpsTimeOffsetMillis;
    }
}
