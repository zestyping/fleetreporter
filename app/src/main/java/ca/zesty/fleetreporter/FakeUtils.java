package ca.zesty.fleetreporter;

/** Stub implementation of Utils, for use in tests. */
public class FakeUtils extends Utils {
    public FakeUtils() {
        super(null);
    }

    public String getPref(String key, String defaultValue) {
        return defaultValue;
    }

    public boolean getBooleanPref(String key, boolean defaultValue) {
        return defaultValue;
    }

    public int getIntPref(String key, int defaultValue) {
        return defaultValue;
    }

    public float getFloatPref(String key, double defaultValue) {
        return (float) defaultValue;
    }
}
