package ca.zesty.fleetreporter;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class MotionListenerTest {
    static final long STABLE_MIN_MILLIS = MotionListener.STABLE_MIN_MILLIS;
    static final long SECOND = 1000;  // millis
    static final long MINUTE = 60000;  // millis
    static final long T0 = 1514764800_000L;  // 2018-01-01 00:00:00 UTC
    static final long T1 = T0 + STABLE_MIN_MILLIS - 1;  // just before stabilization
    static final long T2 = T0 + STABLE_MIN_MILLIS + 1;  // just after stabilization
    static final LocationFix L0 = new LocationFix(T0, 37, -122, 0, 0, 0, 12);
    static final LocationFix L0_NEAR = new LocationFix(T0, 37.00010, -122, 0, 0, 0, 12);
    static final LocationFix L0_FAR = new LocationFix(T0, 37.00030, -122, 0, 0, 0, 12);

    private AppendListener al;
    private MotionListener ml;

    @Before public void setUp() {
        al = new AppendListener();
        ml = new MotionListener(al);
        simulateFix(L0, T0);
        assertMovingPoint("for the very first fix", L0, T0, T0, al.popOne());
    }

    @Test public void testNearbyFixesWithinAndAfterStabilizationPeriod() throws Exception {
        simulateFix(L0, T0 + 1);
        assertNoPoints("for a nearby fix within the stabilization period");
        simulateFix(L0, T1);
        assertNoPoints("for a nearby fix within the stabilization period");
        simulateFix(L0, T2);
        assertStopPoint("retroactively, for a nearby fix after the stabilization period",
            L0, T0, T0, al.popOne());
    }

    @Test public void testAdditionalNearbyFixesAfterStopped() throws Exception {
        testNearbyFixesWithinAndAfterStabilizationPeriod();
        simulateFix(L0, T2 + 1);
        assertRestingPoint("for each additional nearby fix after stopping", L0,  T2 + 1, T0, al.popOne());
        simulateFix(L0, T2 + 2);
        assertRestingPoint("for each additional nearby fix after stopping", L0, T2 + 2, T0, al.popOne());
        simulateFix(L0, T2 + 10000);
        assertRestingPoint("for each additional nearby fix after stopping", L0, T2 + 10000, T0, al.popOne());
    }

    @Test public void testDepartureAfterStopped() throws Exception {
        testNearbyFixesWithinAndAfterStabilizationPeriod();
        simulateFix(L0_FAR, T2 + 1);
        assertGoPoint("for a departing fix after stopping", L0_FAR, T2 + 1, T0, al.popOne());
    }

    @Test public void testImmediateFarawayFix() throws Exception {
        simulateFix(L0_FAR, T0 + 1);
        assertMovingPoint("for an immediate faraway fix", L0_FAR, T0 + 1, T0, al.popOne());
    }

    @Test public void testFarawayFixWithinStabilizationPeriod() throws Exception {
        simulateFix(L0_NEAR, T0 + 1);
        assertNoPoints("for a nearby fix within the stabilization period");
        simulateFix(L0_NEAR, T1 - 1);
        assertNoPoints("for a nearby fix within the stabilization period");
        simulateFix(L0_FAR, T1);
        assertMovingPoint("for a faraway fix just before the end of the stabilization period",
            L0_FAR, T1, T0, al.popOne());
    }

    @Test public void testFarawayFixAfterStabilizationPeriod() throws Exception {
        simulateFix(L0_NEAR, T0 + 1);
        assertNoPoints("for a nearby fix within the stabilization period");
        simulateFix(L0_NEAR, T1);
        assertNoPoints("for a nearby fix within the stabilization period");
        simulateFix(L0_FAR, T2);
        assertMovingPoint("for a faraway fix after the stabilization period",
            L0_FAR, T2, T0, al.popOne());
    }

    @Test public void testMovingThenStopping() throws Exception {
        simulateFix(L0_FAR, T1);
        assertMovingPoint("for a faraway fix within the stabilization period",
            L0_FAR, T1, T0, al.popOne());
        simulateFix(L0_FAR, T1 + 1);
        assertNoPoints("for a nearby fix within the stabilization period, after moving");
        simulateFix(L0_FAR, T1 + STABLE_MIN_MILLIS - 1);
        assertNoPoints("for a nearby fix within the stabilization period, after moving");
        simulateFix(L0_FAR, T1 + STABLE_MIN_MILLIS + 1);
        assertStopPoint("retroactively, for a nearby fix after the stabilization period, after moving",
            L0_FAR, T1, T0, al.popOne());
    }

    private void simulateFix(LocationFix fix, long timeMillis) {
        ml.onLocationFix(fix.withTime(timeMillis));
    }

    private void assertNoPoints(String situation) {
        assertEquals("MotionListener should emit nothing " + situation, 0, al.points.size());
    }

    private static void assertRestingPoint(String situation, LocationFix fix, long timeMillis, long restingStartMillis, Point actual) {
        assertEquals("MotionListener should emit a resting point " + situation,
            Point.createResting(fix.withTime(timeMillis), restingStartMillis), actual);
    }

    private static void assertGoPoint(String situation, LocationFix fix, long timeMillis, long restingStartMillis, Point actual) {
        assertEquals("MotionListener should emit a go point " + situation,
            Point.createGo(fix.withTime(timeMillis), restingStartMillis), actual);
    }

    private static void assertMovingPoint(String situation, LocationFix fix, long timeMillis, long movingStartMillis, Point actual) {
        assertEquals("MotionListener should emit a moving point " + situation,
            Point.createMoving(fix.withTime(timeMillis), movingStartMillis), actual);
    }

    private static void assertStopPoint(String situation, LocationFix fix, long timeMillis, long movingStartMillis, Point actual) {
        assertEquals("MotionListener should emit a stop point " + situation,
            Point.createStop(fix.withTime(timeMillis), movingStartMillis), actual);
    }

    private class AppendListener implements PointListener {
        public final List<Point> points = new ArrayList<>();

        @Override public void onPoint(Point point) {
            points.add(point);
        }

        public Point popOne() {
            assertEquals(1, points.size());
            Point point = points.get(0);
            points.remove(0);
            return point;
        }

        public String toString() {
            String result = "";
            for (Point point : points) {
                result += point.toString() + "\n";
            }
            return result;
        }
    }
}
