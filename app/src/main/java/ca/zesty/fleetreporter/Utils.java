package ca.zesty.fleetreporter;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Environment;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.InputFilter;
import android.text.Spanned;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.crashlytics.android.Crashlytics;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Utils {
    static final String TAG = "Utils";
    static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    static final SimpleDateFormat RFC3339_UTC_SECONDS;
    static final SimpleDateFormat RFC3339_UTC_MILLIS;
    static {
        RFC3339_UTC_SECONDS = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        RFC3339_UTC_SECONDS.setTimeZone(UTC);
        RFC3339_UTC_MILLIS = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        RFC3339_UTC_MILLIS.setTimeZone(UTC);
    }

    static final Pattern PATTERN_TIMESTAMP = Pattern.compile(
        "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})Z");

    static int sNumLogRemoteLines = 0;

    /** Calls String.format with the US locale. */
    public static String format(String template, Object... args) {
        return String.format(Locale.US, template, args);
    }

    public static String escapeString(String str) {
        return str.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
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

    /** Slices a string in the humane, Python way, without throwing Java tantrums. */
    public static String slice(String str, int start, int end) {
        if (start < 0) start += str.length();
        if (end < 0) end += str.length();
        if (end > str.length()) end = str.length();
        return str.substring(start, end);
    }

    public static long[] toLongArray(List<Long> list) {
        // Java is horrible.  Converting from List<Long> to long[] requires this mess.
        long[] array = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    /** Formats a time as an RFC3339 timestamp in UTC of exactly 20 characters. */
    public static String formatUtcTimeSeconds(long timeMillis) {
        return RFC3339_UTC_SECONDS.format(new Date(timeMillis));
    }

    /** Formats a time as an RFC3339 timestamp in UTC including milliseconds. */
    public static String formatUtcTimeMillis(long timeMillis) {
        return RFC3339_UTC_MILLIS.format(new Date(timeMillis));
    }

    /** Parses an RFC3339 timestamp in UTC to give a time in milliseconds, or null. */
    public static Long parseTimestamp(String timestamp) {
        Matcher matcher = PATTERN_TIMESTAMP.matcher(timestamp);
        if (!matcher.matches()) return null;
        Calendar calendar = Calendar.getInstance(UTC);
        calendar.set(
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)) - 1, // Java is insane (0 = Jan, 11 = Dec)
            Integer.parseInt(matcher.group(3)),
            Integer.parseInt(matcher.group(4)),
            Integer.parseInt(matcher.group(5)),
            Integer.parseInt(matcher.group(6))
        );
        return calendar.getTimeInMillis();
    }

    /** Describes a time period using a short phrase like "23 min". */
    public static String describePeriod(long elapsedMillis) {
        return describePeriod(elapsedMillis, false);
    }

    /** Describes a time period using a short phrase like "23 min". */
    public static String describePeriod(long elapsedMillis, boolean showSeconds) {
        long elapsedSec = elapsedMillis/1000;
        if (elapsedSec < 60 && showSeconds) return format("%d sec", elapsedSec);
        if (elapsedSec < 3600) return format("%d min", elapsedSec/60);
        if (elapsedSec < 36000)
            return format("%.1f h", (float) elapsedSec/3600);
        if (elapsedSec < 24*3600) return format("%d h", elapsedSec/3600);
        if (elapsedSec < 7*24*3600)
            return format("%.1f d", (float) elapsedSec/24/3600);
        return format("%d d", elapsedSec/24/3600);
    }

    /** Describes a time in the past using a short phrase like "15 h ago". */
    public static String describeTime(long timeMillis) {
        long elapsedMillis = System.currentTimeMillis() - timeMillis;
        if (elapsedMillis < 60000) return "just now";
        else return describePeriod(elapsedMillis) + " ago";
    }

    /** Describes a distance using a phrase like "150 m" or "7.3 km". */
    public static String describeDistance(double meters) {
        if (meters < 100) return format("%.0f m", meters);
        if (meters < 10000) return format("%.1f km", 0.001 * meters);
        return format("%.0f km", 0.001 * meters);
    }

    public static int countMinutesSinceMidnight(String hourMinute) {
        try {
            String[] parts = hourMinute.split(":");
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            return hours*60 + minutes;
        } catch (NullPointerException | IndexOutOfBoundsException | NumberFormatException e) {
            return 0;
        }
    }

    public static int getLocalMinutesSinceMidnight() {
        Calendar localTime = Calendar.getInstance();
        return localTime.get(Calendar.HOUR_OF_DAY) * 60 + localTime.get(Calendar.MINUTE);
    }

    public static boolean isLocalTimeOfDayBetween(String startHourMinute, String endHourMinute) {
        int startMinutes = countMinutesSinceMidnight(startHourMinute);
        int endMinutes = countMinutesSinceMidnight(endHourMinute);
        int localMinutes = getLocalMinutesSinceMidnight();
        if (startMinutes <= endMinutes) {
            return startMinutes <= localMinutes && localMinutes < endMinutes;
        } else {
            return startMinutes <= localMinutes || localMinutes < endMinutes;
        }
    }

    public static IntentFilter getMaxPrioritySmsFilter() {
        IntentFilter filter = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
        filter.setPriority(Integer.MAX_VALUE);
        return filter;
    }

    public static SmsMessage getSmsFromIntent(Intent intent) {
        Object[] pdus = (Object[]) intent.getExtras().get("pdus");
        for (Object pdu : pdus) {
            return SmsMessage.createFromPdu((byte[]) pdu);
        }
        return null;
    }

    public static class PrintableAsciiFilter implements InputFilter {
        @Override public CharSequence filter(
            CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if (!(c >= 32 && c <= 126)) return "";
            }
            return null;
        }
    }

    public static class MobileNumberFilter implements InputFilter {
        @Override public CharSequence filter(
            CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if (!(c == '+' || c >= '0' && c <= '9')) return "";
            }
            return null;
        }
    }

    public static void log(String tag, String message, Object... args) {
        logHelper(tag, args.length > 0 ? Utils.format(message, args) : message, false);
    }

    public static void logRemote(String tag, String message, Object... args) {
        logHelper(tag, args.length > 0 ? Utils.format(message, args) : message, true);
    }

    private static void logHelper(String tag, String message, boolean remote) {
        Log.i(tag, message);
        String timestamp = Utils.formatUtcTimeSeconds(System.currentTimeMillis());
        String logLine = Utils.format("%s - %s: %s", timestamp, tag, message);
        if (remote) {
            Crashlytics.log(logLine);
            if (++sNumLogRemoteLines >= 100) {
                Utils.transmitLog();
            }
        }
        String filename = Utils.format("fleetreporter-%s.txt", timestamp.substring(0, 10));
        File file = new File(Environment.getExternalStorageDirectory(), filename);
        try {
            FileWriter writer = new FileWriter(file, true);
            try {
                writer.append(Utils.escapeString(logLine) + "\n");
            } finally {
                writer.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not write to " + file.getAbsolutePath());
        }
    }

    public static void transmitLog() {
        try {
            throw new RuntimeException("Diagnostic log");
        } catch (RuntimeException e) {
            Crashlytics.logException(e);
            Log.i(TAG, "Captured Crashlytics diagnostic log (events: " + sNumLogRemoteLines + ")");
            sNumLogRemoteLines = 0;
        }
    }


    // ==== CONTEXT-DEPENDENT ====

    public final Context context;
    public final Activity activity;

    public Utils(Context context) {
        this.context = context;
        this.activity = null;
    }

    public Utils(Activity activity) {
        this.activity = activity;
        this.context = activity;
    }

    public AlarmManager getAlarmManager() {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    public LocationManager getLocationManager() {
        return (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public NotificationManager getNotificationManager() {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public PowerManager getPowerManager() {
        return (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    }

    public TelephonyManager getTelephonyManager() {
        return (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    public boolean isAccessibilityServiceEnabled(Class cls) {
        ContentResolver resolver = context.getApplicationContext().getContentResolver();
        String expectedServiceName = context.getPackageName() + "/" + cls.getCanonicalName();
        String serviceNames = "";
        try {
            if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED) == 1) {
                serviceNames = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            }
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }
        if (serviceNames != null) {
            for (String name : serviceNames.split(":")) {
                if (name.equals(expectedServiceName)) return true;
            }
        }
        return false;
    }

    public void relaunchApp() {
        Utils.logRemote(TAG, "Relaunch");
        Utils.transmitLog();
        Intent intent = new Intent(context, MainActivity.class);
        getAlarmManager().set(AlarmManager.RTC, System.currentTimeMillis() + 300,
            PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_CANCEL_CURRENT));
        System.exit(0);
    }

    /** Sends a text message using the default SmsManager. */
    public void sendSms(int slot, String recipient, String body) {
        sendSms(slot, recipient, body, null);
    }

    /** Sends a text message using the default SmsManager. */
    public void sendSms(int slot, String recipient, String body, Intent sentBroadcastIntent) {
        PendingIntent sentIntent = sentBroadcastIntent == null ? null :
            PendingIntent.getBroadcast(context, 0, sentBroadcastIntent, PendingIntent.FLAG_ONE_SHOT);
        Utils.logRemote(TAG, "Sending SMS (slot %d) to %s: %s", slot, recipient, body);
        getSmsManager(slot).sendTextMessage(recipient, null, body, sentIntent, null);
    }

    /** Gets the IMSI for a given SIM slot; returns null if no such slot. */
    public String getImsi(int slot) {
        if (android.os.Build.VERSION.SDK_INT >= 22) {
            SubscriptionInfo sub = SubscriptionManager.from(context)
                .getActiveSubscriptionInfoForSimSlotIndex(slot);
            if (sub == null) return null;
            try {
                // The TelephonyManager.getSubscriberId(int) method is public but hidden.
                Class cls = Class.forName("android.telephony.TelephonyManager");
                Method method = cls.getMethod("getSubscriberId", new Class[] {int.class});
                Object result = method.invoke(getTelephonyManager(), new Object[] {sub.getSubscriptionId()});
                return (String) result;
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                Log.e(TAG, "Failed to look up subscriber ID for slot " + slot + ": " + e);
                return null;
            }
        }
        return slot == 0 ? getTelephonyManager().getSubscriberId() : null;
    }

    /** Gets the mobile number for a given SIM slot; can fail and return null. */
    public String getMobileNumber(int slot) {
        String number = null;
        if (android.os.Build.VERSION.SDK_INT >= 22) {
            SubscriptionInfo sub = SubscriptionManager.from(context)
                .getActiveSubscriptionInfoForSimSlotIndex(slot);
            if (sub != null) number = sub.getNumber();
        }
        if (number == null && slot == 0) {
            number = getTelephonyManager().getLine1Number();
        }
        return number != null ? "+" + number.replaceAll("^\\+*", "") : null;
    }

    public int getNumSimSlots() {
        int slots = 0;
        while (getSmsManager(slots) != null) slots++;
        return slots;
    }

    /** Gets the SmsManager for a given SIM slot; returns null if no such slot. */
    public SmsManager getSmsManager(int slot) {
        if (android.os.Build.VERSION.SDK_INT >= 22) {
            SubscriptionInfo sub = SubscriptionManager.from(context)
                .getActiveSubscriptionInfoForSimSlotIndex(slot);
            if (sub != null) {
                int subscriptionId = sub.getSubscriptionId();
                if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    return SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
                }
            }
        }
        return slot == 0 ? SmsManager.getDefault() : null;
    }

    public SharedPreferences getPrefs() {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public String getPref(String key) {
        return getPrefs().getString(key, "");
    }

    public String getPref(String key, String defaultValue) {
        return getPrefs().getString(key, defaultValue);
    }

    public boolean getBooleanPref(String key, boolean defaultValue) {
        return getPrefs().getBoolean(key, defaultValue);
    }

    public int getIntPref(String key, int defaultValue) {
        int value = defaultValue;
        try {
            value = Integer.parseInt(getPrefs().getString(key, "x"));
        } catch (NumberFormatException e) { }
        return value;
    }

    public long getMinutePrefInMillis(String key, double defaultMinutes) {
        double minutes = defaultMinutes;
        try {
            minutes = Double.valueOf(getPrefs().getString(key, "x"));
        } catch (NumberFormatException e) { }
        return Math.round(minutes * 60 * 1000);
    }

    public void setPref(String key, String value) {
        getPrefs().edit().putString(key, value).commit();
    }

    public void sendUssd(int slot, String code) {
        Utils.logRemote(TAG, "Sending USSD (slot %d): %s", slot, code);
        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);  // required to start an Activity from a non-Activity context
        intent.setData(Uri.parse("tel:" + Uri.encode(code)));
        intent.putExtra("simSlot", slot);  // understood by Samsung Duo phones only
        context.startActivity(intent);
    }


    // ==== UI ====

    interface StringCallback {
        void run(String str);
    }

    public void hide(int id) {
        show(id, false);
    }

    public void show(int id) {
        show(id, true);
    }

    public void show(int id, boolean visible) {
        if (activity == null) return;
        activity.findViewById(id).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void setText(int id, String text) {
        if (activity == null) return;
        TextView view = activity.findViewById(id);
        view.setText(text);
        view.setVisibility(View.VISIBLE);
    }

    public void setText(int id, String text, int textColor) {
        if (activity == null) return;
        TextView view = activity.findViewById(id);
        view.setText(text);
        view.setTextColor(textColor);
        view.setVisibility(View.VISIBLE);
    }

    public void setText(View view, int id, String text) {
        TextView child = ((TextView) view.findViewById(id));
        child.setText(text);
        child.setVisibility(View.VISIBLE);
    }

    public void setText(View view, int id, String text, int textColor) {
        TextView child = ((TextView) view.findViewById(id));
        child.setText(text);
        child.setTextColor(textColor);
        child.setVisibility(View.VISIBLE);
    }

    /** Shows a message box with a single button that invokes the given listener. */
    public void showMessageBox(String title, String message, String buttonLabel, DialogInterface.OnClickListener listener) {
        new AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(buttonLabel, listener)
            .show();
    }

    /** Shows a simple message box with an OK button. */
    public void showMessageBox(String title, String message) {
        showMessageBox(title, message, "OK", null);
    }

    /** Shows a simple prompt dialog with a single text entry field. */
    public void promptForString(String title, String message, String value, final StringCallback callback, InputFilter... filters) {
        FrameLayout frame = new FrameLayout(context);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = params.rightMargin = context.getResources().getDimensionPixelSize(R.dimen.dialog_margin);
        final EditText inputView = new EditText(context);
        if (value != null) inputView.setText(value);
        if (filters != null) inputView.setFilters(filters);
        inputView.setLayoutParams(params);
        frame.addView(inputView);

        AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(frame)
            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    callback.run(inputView.getText().toString());
                }
            })
            .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    callback.run(null);
                }
            })
            .create();
        dialog.getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
        inputView.setSelection(inputView.length());  // put cursor at end
    }

    public void showFrameChild(int id) {
        View selected = activity.findViewById(id);
        ViewGroup group = (ViewGroup) selected.getParent();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            child.setVisibility(child == selected ? View.VISIBLE : View.INVISIBLE);
        }
    }
}
