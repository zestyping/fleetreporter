package ca.zesty.fleetreporter;

import android.location.Location;
import android.location.LocationManager;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class MotionListenerTest {
    public static final long SECOND = 1000;  // millis
    public static final long MINUTE = 60000;  // millis
    public static final long T0 = 1514764800_000L;  // 2018-01-01 00:00:00 UTC

    public static final Location L0 = newLocation(T0, 37, -122, 0, 0, 0, 12);

    private AppendListener al;
    private MotionListener ml;

    @Before public void setUp() {
        al = new AppendListener();
        ml = new MotionListener(al);
    }

    @Test public void initialRestingPeriod_yieldsMovingEndFix() throws Exception {
        ml.onLocationChanged(newLocation(T0, L0));
        assertEquals(newMovingFix(T0, L0, T0), al.popOne());
        ml.onLocationChanged(newLocation(T0 + 10 * SECOND, L0));
        assertEquals(0, al.fixes.size());
        ml.onLocationChanged(newLocation(T0 + 61 * SECOND, L0));
        assertEquals(newMovingEndFix(T0, L0, T0), al.popOne());
        ml.onLocationChanged(newLocation(T0 + 99 * SECOND, L0));
        assertEquals(newRestingFix(T0 + 99 * SECOND, L0, T0), al.popOne());
    }

    private static Location newLocation(long time, Location location) {
        Location result = new Location(location);
        result.setTime(time);
        return result;
    }

    private static Location newLocation(
        long time, double latitude, double longitude, double altitude,
        double speed, double bearing, double accuracy) {
        Location result = new Location(LocationManager.GPS_PROVIDER);
        result.setTime(time);
        result.setLatitude(latitude);
        result.setLongitude(longitude);
        result.setAltitude(altitude);
        result.setSpeed((float) speed);
        result.setBearing((float) bearing);
        result.setAccuracy((float) accuracy);
        return result;
    }

    private static LocationFix newRestingFix(long time, Location location, long restingStartMillis) {
        return LocationFix.createResting(time, location, restingStartMillis);
    }

    private static LocationFix newRestingEndFix(long time, Location location, long restingStartMillis) {
        return LocationFix.createRestingEnd(time, location, restingStartMillis);
    }

    private static LocationFix newMovingFix(long time, Location location, long movingStartMillis) {
        return LocationFix.createMoving(time, location, movingStartMillis);
    }

    private static LocationFix newMovingEndFix(long time, Location location, long movingStartMillis) {
        return LocationFix.createMovingEnd(time, location, movingStartMillis);
    }

    private class AppendListener implements LocationFixListener {
        public final List<LocationFix> fixes = new ArrayList<>();

        @Override public void onLocationFix(LocationFix fix) {
            fixes.add(fix);
        }

        public LocationFix popOne() {
            assertEquals(1, fixes.size());
            LocationFix fix = fixes.get(0);
            fixes.remove(0);
            return fix;
        }

        public String toString() {
            String result = "";
            for (LocationFix fix : fixes) {
                result += fix.toString() + "\n";
            }
            return result;
        }
    }
}
