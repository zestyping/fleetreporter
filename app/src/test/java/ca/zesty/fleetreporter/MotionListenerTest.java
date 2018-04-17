package ca.zesty.fleetreporter;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class MotionListenerTest implements PointListener {
    static final long SETTLING_PERIOD_MILLIS = MotionListener.SETTLING_PERIOD_MILLIS;
    static final long SECOND = 1000;  // millis
    static final long MINUTE = 60000;  // millis
    static final long T0 = 1514764800_000L;  // 2018-01-01 00:00:00 UTC
    static final long T1 = T0 + SETTLING_PERIOD_MILLIS - 1;  // just before settling
    static final long T2 = T0 + SETTLING_PERIOD_MILLIS + 1;  // just after settling
    static final LocationFix L0 = new LocationFix(T0, 37, -122, 0, 0, 0, 12);
    static final LocationFix L0_NEAR = new LocationFix(T0, 37.00010, -122, 0, 0, 0, 12);
    static final LocationFix L0_FAR = new LocationFix(T0, 37.00030, -122, 0, 0, 0, 12);

    private MotionListener ml;
    private final List<Point> points = new ArrayList<>();

    @Before public void setUp() {
        points.clear();
        ml = new MotionListener(this);
        simulateFix(L0, T0);
        assertMovingPoint("for the very first fix", L0, T0, T0);
    }

    @Test public void testNearbyFixesWithinAndAfterSettlingPeriod() throws Exception {
        simulateFix(L0, T0 + 1);
        assertNoPoints("for a nearby fix within the settling period");
        simulateFix(L0, T1);
        assertNoPoints("for a nearby fix within the settling period");
        simulateFix(L0, T2);
        assertStopPoint("retroactively, for a nearby fix after the settling period",
            L0, T0, T0);
    }

    @Test public void testAdditionalNearbyFixesAfterStopped() throws Exception {
        testNearbyFixesWithinAndAfterSettlingPeriod();
        simulateFix(L0, T2 + 1);
        assertRestingPoint("for each additional nearby fix after stopping",
            L0,  T2 + 1, T0);
        simulateFix(L0, T2 + 2);
        assertRestingPoint("for each additional nearby fix after stopping",
            L0, T2 + 2, T0);
        simulateFix(L0, T2 + 10000);
        assertRestingPoint("for each additional nearby fix after stopping",
            L0, T2 + 10000, T0);
    }

    @Test public void testDepartureAfterStopped() throws Exception {
        testNearbyFixesWithinAndAfterSettlingPeriod();
        simulateFix(L0_FAR, T2 + 1);
        assertGoPoint("retroactively, for a departing fix after stopping",
            L0, T2, T0);
    }

    @Test public void testImmediateFarawayFix() throws Exception {
        simulateFix(L0_FAR, T0 + 1);
        assertMovingPoint("for an immediate faraway fix",
            L0_FAR, T0 + 1, T0);
    }

    @Test public void testFarawayFixWithinSettlingPeriod() throws Exception {
        simulateFix(L0_NEAR, T0 + 1);
        assertNoPoints("for a nearby fix within the settling period");
        simulateFix(L0_NEAR, T1 - 1);
        assertNoPoints("for a nearby fix within the settling period");
        simulateFix(L0_FAR, T1);
        assertMovingPoint("for a faraway fix just before the end of the settling period",
            L0_FAR, T1, T0);
    }

    @Test public void testFarawayFixAfterSettlingPeriod() throws Exception {
        simulateFix(L0_NEAR, T0 + 1);
        assertNoPoints("for a nearby fix within the settling period");
        simulateFix(L0_NEAR, T1);
        assertNoPoints("for a nearby fix within the settling period");
        simulateFix(L0_FAR, T2);
        assertMovingPoint("for a faraway fix after the settling period",
            L0_FAR, T2, T0);
    }

    @Test public void testMovingThenStopping() throws Exception {
        simulateFix(L0_FAR, T1);
        assertMovingPoint("for a faraway fix within the settling period",
            L0_FAR, T1, T0);
        simulateFix(L0_FAR, T1 + 1);
        assertNoPoints("for a nearby fix within the settling period, after moving");
        simulateFix(L0_FAR, T1 + SETTLING_PERIOD_MILLIS - 1);
        assertNoPoints("for a nearby fix within the settling period, after moving");
        simulateFix(L0_FAR, T1 + SETTLING_PERIOD_MILLIS + 1);
        assertStopPoint("retroactively, for a nearby fix after the settling period, after moving",
            L0_FAR, T1, T0);
    }

    private void simulateFix(LocationFix fix, long timeMillis) {
        ml.onLocationFix(fix.withTime(timeMillis));
    }

    private void assertNoPoints(String situation) {
        assertEquals("MotionListener should emit nothing " + situation, 0, points.size());
    }

    private void assertRestingPoint(
        String situation, LocationFix fix, long timeMillis, long lastTransitionMillis) {
        assertEquals("MotionListener should emit a resting point " + situation,
            new Point(fix.withTime(timeMillis), Point.Type.RESTING, lastTransitionMillis),
            expectOnePoint());
    }

    private void assertGoPoint(
        String situation, LocationFix fix, long timeMillis, long lastTransitionMillis) {
        assertEquals("MotionListener should emit a go point " + situation,
            new Point(fix.withTime(timeMillis), Point.Type.GO, lastTransitionMillis),
            expectOnePoint());
    }

    private void assertMovingPoint(
        String situation, LocationFix fix, long timeMillis, long lastTransitionMillis) {
        assertEquals("MotionListener should emit a moving point " + situation,
            new Point(fix.withTime(timeMillis), Point.Type.MOVING, lastTransitionMillis),
            expectOnePoint());
    }

    private void assertStopPoint(
        String situation, LocationFix fix, long timeMillis, long lastTransitionMillis) {
        assertEquals("MotionListener should emit a stop point " + situation,
            new Point(fix.withTime(timeMillis), Point.Type.STOP, lastTransitionMillis),
            expectOnePoint());
    }

    @Override public void onPoint(Point point, boolean isProvisional) {
        if (!isProvisional) points.add(point);
        System.out.println("Point: " + point);
    }

    public Point expectOnePoint() {
        assertEquals(1, points.size());
        Point point = points.get(0);
        points.remove(0);
        return point;
    }
}
