package ca.zesty.fleetreporter;

public class Utils {
    public static String plural(long count) {
        return (count == 1) ? "" : "s";
    }
}
