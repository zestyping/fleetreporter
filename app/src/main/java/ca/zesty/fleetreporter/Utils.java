package ca.zesty.fleetreporter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.InputFilter;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Utils {
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

    /** Calls String.format with the US locale. */
    public static String format(String template, Object... args) {
        return String.format(Locale.US, template, args);
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

    /** Describes a time in the past using a short phrase like "15 h ago". */
    public static String describeTime(long timeMillis) {
        long elapsedSec = (System.currentTimeMillis() - timeMillis)/1000;
        if (elapsedSec < 60) return "just now";
        if (elapsedSec < 3600) return format("%d m ago", elapsedSec/60);
        if (elapsedSec < 36000)
            return format("%.1f h ago", (float) elapsedSec/3600);
        if (elapsedSec < 24*3600) return format("%d h ago", elapsedSec/3600);
        if (elapsedSec < 7*24*3600)
            return format("%.1f d ago", (float) elapsedSec/24/3600);
        return format("%d d ago", elapsedSec/24/3600);
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

    /** Sends a text message using the default SmsManager. */
    public void sendSms(String recipient, String body) {
        sendSms(recipient, body, null);
    }

    /** Sends a text message using the default SmsManager. */
    public void sendSms(String recipient, String body, Intent sentBroadcastIntent) {
        PendingIntent sentIntent = sentBroadcastIntent == null ? null :
            PendingIntent.getBroadcast(context, 0, sentBroadcastIntent, PendingIntent.FLAG_ONE_SHOT);
        getSmsManager().sendTextMessage(recipient, null, body, sentIntent, null);
    }

    /** Gets the mobile number from which this device sends text messages. */
    public String getSmsNumber() {
        String number;
        if (android.os.Build.VERSION.SDK_INT >= 22) {
            number = SubscriptionManager.from(context).getActiveSubscriptionInfo(
                getSmsManager().getSubscriptionId()).getNumber();
        } else {
            number = getTelephonyManager().getLine1Number();
        }
        return "+" + number.replaceAll("^\\+*", "");
    }

    /** Gets the appropriate SmsManager to use for sending text messages.
     From PataBasi by Kristen Tonga. */
    public SmsManager getSmsManager() {
        if (android.os.Build.VERSION.SDK_INT >= 22) {
            int subscriptionId = SmsManager.getDefaultSmsSubscriptionId();
            if (subscriptionId == SubscriptionManager
                .INVALID_SUBSCRIPTION_ID) {  // dual-SIM phone
                subscriptionId = SubscriptionManager.from(context)
                    .getActiveSubscriptionInfoForSimSlotIndex(0)
                    .getSubscriptionId();
            }
            return SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
        }
        return SmsManager.getDefault();
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


    // ==== UI ====

    interface StringCallback {
        void run(String str);
    }

    public void hide(int id) {
        if (activity == null) return;
        activity.findViewById(id).setVisibility(View.GONE);
    }

    public void show(int id) {
        if (activity == null) return;
        activity.findViewById(id).setVisibility(View.VISIBLE);
    }

    public void setText(int id, String text) {
        if (activity == null) return;
        ((TextView) activity.findViewById(id)).setText(text);
    }

    public void setText(View view, int id, String text) {
        ((TextView) view.findViewById(id)).setText(text);
    }

    /** Shows a simple message box with an OK button. */
    public void showMessageBox(String title, String message) {
        new AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show();
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
}
