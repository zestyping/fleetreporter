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
    static final LocationFix L0 = new LocationFix(T0, 37, -122, 0, 0, 0, 12);
    static final LocationFix L0_NEAR = new LocationFix(T0, 37.00010, -122, 0, 0, 0, 12);
    static final LocationFix L0_FAR = new LocationFix(T0, 37.00030, -122, 0, 0, 0, 12);

    private AppendListener al;
    private MotionListener ml;

    @Before public void setUp() {
        al = new AppendListener();
        ml = new MotionListener(al);
        ml.onLocationFix(L0.withTime(T0));
        assertMovingPoint(L0, T0, T0, al.popOne());
    }

    @Test public void whileStabilizing_emitsNoPoints() throws Exception {
        simulateFix(L0, T0 + 1);
        assertNoPoints();
        simulateFix(L0, T0 + STABLE_MIN_MILLIS - 1);
        assertNoPoints();
    }

    @Test public void afterStabilized_emitsRetroactiveMovingEndPoint() throws Exception {
        whileStabilizing_emitsNoPoints();
        simulateFix(L0, T0 + STABLE_MIN_MILLIS + 1);
        assertMovingEndPoint(L0, T0, T0, al.popOne());
    }

    @Test public void immediateDepartureFromStartingPoint_emitsMovingPoint() throws Exception {
        simulateFix(L0_FAR, T0 + 1);
        assertMovingPoint(L0_FAR, T0 + 1, T0, al.popOne());
    }

    @Test public void departureFromStartingPointBeforeStabilized_emitsMovingPoint() throws Exception {
        simulateFix(L0_NEAR, T0 + 1);
        assertNoPoints();
        simulateFix(L0_NEAR, T1 - 1);
        assertNoPoints();
        simulateFix(L0_FAR, T1);
        assertMovingPoint(L0_FAR, T1, T0, al.popOne());
    }

    @Test public void afterMovingWhileStabilizing_emitsNoPoints() throws Exception {
        simulateFix(L0_FAR, T1);
        assertMovingPoint(L0_FAR, T1, T0, al.popOne());
        simulateFix(L0_FAR, T1 + 1);
        assertNoPoints();
        simulateFix(L0_FAR, T1 + STABLE_MIN_MILLIS - 1);
        assertNoPoints();
    }

    @Test public void afterMovingThenStabilized_emitsRetroactiveMovingEndPoint() throws Exception {
        afterMovingWhileStabilizing_emitsNoPoints();
        simulateFix(L0_FAR, T1 + STABLE_MIN_MILLIS + 1);
        assertMovingEndPoint(L0_FAR, T1, T0, al.popOne());
    }

    private void simulateFix(LocationFix fix) {
        ml.onLocationFix(fix);
    }

    private void simulateFix(LocationFix fix, long timeMillis) {
        ml.onLocationFix(fix.withTime(timeMillis));
    }

    private void assertNoPoints() {
        assertEquals(0, al.points.size());
    }

    private static void assertRestingPoint(LocationFix fix, long timeMillis, long restingStartMillis, Point actual) {
        assertEquals(Point.createResting(fix.withTime(timeMillis), restingStartMillis), actual);
    }

    private static void assertRestingEndPoint(LocationFix fix, long timeMillis, long restingStartMillis, Point actual) {
        assertEquals(Point.createRestingEnd(fix.withTime(timeMillis), restingStartMillis), actual);
    }

    private static void assertMovingPoint(LocationFix fix, long timeMillis, long movingStartMillis, Point actual) {
        assertEquals(Point.createMoving(fix.withTime(timeMillis), movingStartMillis), actual);
    }

    private static void assertMovingEndPoint(LocationFix fix, long timeMillis, long movingStartMillis, Point actual) {
        assertEquals(Point.createMovingEnd(fix.withTime(timeMillis), movingStartMillis), actual);
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
