package ca.zesty.fleetreporter;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class MotionListenerTest {
    public static final long SECOND = 1000;  // millis
    public static final long MINUTE = 60000;  // millis
    public static final long T0 = 1514764800_000L;  // 2018-01-01 00:00:00 UTC
    public static final LocationFix L0 = new LocationFix(T0, 37, -122, 0, 0, 0, 12);

    private AppendListener al;
    private MotionListener ml;

    @Before public void setUp() {
        al = new AppendListener();
        ml = new MotionListener(al);
    }

    @Test public void initialRestingPeriod_yieldsMovingEndPoint() throws Exception {
        ml.onLocationFix(L0.withTime(T0));
        assertMovingPoint(L0, T0, T0, al.popOne());
        ml.onLocationFix(L0.withTime(T0 + 10 * SECOND));
        assertEquals(0, al.points.size());
        ml.onLocationFix(L0.withTime(T0 + 61 * SECOND));
        assertMovingEndPoint(L0, T0, T0, al.popOne());
        ml.onLocationFix(L0.withTime(T0 + 99 * SECOND));
        assertRestingPoint(L0, T0 + 99 * SECOND, T0, al.popOne());
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
