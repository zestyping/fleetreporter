package ca.zesty.fleetreporter;

import java.util.List;

public class Utils {
    public static String plural(long count, String singular, String plural) {
        return (count == 1) ? singular : plural;
    }

    public static String plural(long count) {
        return (count == 1) ? "" : "s";
    }

    public static long clamp(long min, long max, long value) {
        return (value < min) ? min : (value > max) ? max : value;
    }

    public static double clamp(double min, double max, double value) {
        return (value < min) ? min : (value > max) ? max : value;
    }

    public static long[] toLongArray(List<Long> list) {
        // Java is horrible.  Converting from List<Long> to long[] requires this mess.
        long[] array = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }
}
