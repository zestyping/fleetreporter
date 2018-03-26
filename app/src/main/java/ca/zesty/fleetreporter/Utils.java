package ca.zesty.fleetreporter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class Utils {
    private static final SimpleDateFormat RFC3339_UTC;
    static {
        RFC3339_UTC = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        RFC3339_UTC.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /** Formats a time as an RFC3339 timestamp in UTC of exactly 20 characters. */
    public static String formatUtcTimestamp(long timeMillis) {
        return RFC3339_UTC.format(new Date(timeMillis));
    }

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
