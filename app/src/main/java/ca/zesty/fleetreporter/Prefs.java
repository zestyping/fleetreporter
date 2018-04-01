package ca.zesty.fleetreporter;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;

/** Access to shared preferences. */
public class Prefs {
    static final String DESTINATION_NUMBER_KEY = "pref_destination_number";
    static final String REPORTER_ID_KEY = "pref_reporter_id";
    static final String REPORTER_LABEL_KEY = "pref_reporter_label";

    public static SharedPreferences getPrefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    @NonNull public static String getDestinationNumber(Context context) {
        return getPrefs(context).getString(DESTINATION_NUMBER_KEY, "").trim();
    }

    public static void setDestinationNumber(Context context, String label) {
        getPrefs(context).edit().putString(DESTINATION_NUMBER_KEY, label).commit();
    }

    @NonNull public static String getReporterId(Context context) {
        return getPrefs(context).getString(REPORTER_ID_KEY, "").trim();
    }

    public static void setReporterId(Context context, String reporterId) {
        getPrefs(context).edit().putString(REPORTER_ID_KEY, reporterId).commit();
    }

    @NonNull public static String getReporterLabel(Context context) {
        return getPrefs(context).getString(REPORTER_LABEL_KEY, "").trim();
    }

    public static void setReporterLabel(Context context, String label) {
        getPrefs(context).edit().putString(REPORTER_LABEL_KEY, label).commit();
    }
}
