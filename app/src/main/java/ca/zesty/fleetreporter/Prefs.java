package ca.zesty.fleetreporter;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;

/** Access to shared preferences. */
public class Prefs {
    static final String DESTINATION_NUMBER_KEY = "pref_destination_number";

    public static SharedPreferences getPrefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    @NonNull public static String getDestinationNumber(Context context) {
        return getPrefs(context).getString(DESTINATION_NUMBER_KEY, "").trim();
    }
}
