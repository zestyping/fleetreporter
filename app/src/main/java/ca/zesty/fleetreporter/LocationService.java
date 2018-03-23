package ca.zesty.fleetreporter;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A foreground service that records the device's GPS location and periodically
 * reports it via SMS to the Fleet Map device.
 */
public class LocationService extends Service implements LocationListener {
    private static final String TAG = "LocationService";
    private static final int NOTIFICATION_ID = 1;
    private static final String DESTINATION_NUMBER = "+15103978793";
    private static final long LOCATION_INTERVAL_MILLIS = 5000; // 5 seconds
    private static final long RECORDING_INTERVAL_MILLIS = 10000; // 10 seconds
    private static final long REPORTING_INTERVAL_MILLIS = 60000; // 1 minute

    private boolean mStarted = false;
    private PowerManager.WakeLock mWakeLock = null;
    private Location mLastLocation = null;
    private Handler mHandler;
    private Runnable mRunnable;
    private int mNumReports = 0;
    private long mNextReportMillis = 0;

    /** Starts running the service. */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!mStarted) {
            // Grab the CPU.
            mStarted = true;
            mWakeLock = getPowerManager().newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "LocationService");
            mWakeLock.acquire();
            startForeground(NOTIFICATION_ID, buildNotification());

            // Activate the GPS receiver.
            mLastLocation = getLocationManager().getLastKnownLocation(
                LocationManager.GPS_PROVIDER);
            getLocationManager().requestLocationUpdates(
                LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MILLIS, 0, this);

            // Start periodically recording and reporting location fixes.
            mNextReportMillis = System.currentTimeMillis() + REPORTING_INTERVAL_MILLIS;
            mHandler = new Handler();
            mRunnable = new Runnable() {
                public void run() {
                    recordLocation();
                    mHandler.postDelayed(mRunnable, RECORDING_INTERVAL_MILLIS);
                }
            };
            mHandler.postDelayed(mRunnable, 0);
        }
        return START_STICKY;
    }

    /** Creates the notification to show while the service is running. */
    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        long minutes = (long) Math.ceil(
            (mNextReportMillis - System.currentTimeMillis()) / 60000.0);
        String message = (
            "Sent " + mNumReports + " report" + Utils.plural(mNumReports) +
            "; next in " + minutes + " minute" + Utils.plural(minutes) + "."
        );

        return new NotificationCompat.Builder(this)
            .setContentTitle("Fleet Reporter")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(pendingIntent)
            .build();
    }

    /** Examines the last acquired location, and reports it if necessary. */
    private void recordLocation() {
        Log.i(TAG, "recordLocation: " + mLastLocation);
        if (System.currentTimeMillis() >= mNextReportMillis) {
            sendReport();
            mNextReportMillis += REPORTING_INTERVAL_MILLIS;
        }
        getNotificationManager().notify(NOTIFICATION_ID, buildNotification());
    }

    /** Sends a location report over SMS. */
    private void sendReport() {
        sendSms(DESTINATION_NUMBER, formatSms(mLastLocation));
        mNumReports += 1;
        Log.i(TAG, "sendReport: " + mNumReports);
    }

    /** Formats a location into a text message. */
    private String formatSms(Location location) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        if (location == null) {
            return format.format(new Date()) + ";null";
        }
        return String.format(Locale.US, "%s;%.5f;%.5f;%d;%d;%d;%d",
            format.format(new Date()),
            location.getLatitude(),  // degrees
            location.getLongitude(),  // degrees
            (int) Math.round(location.getAltitude()),  // meters
            (int) Math.round(location.getSpeed() * 3.6),  // km/h
            (int) Math.round(location.getBearing()),  // degrees
            (int) Math.round(location.getAccuracy())  // meters
        );
    }

    /** Sends an SMS message. */
    private void sendSms(String destination, String message) {
        Log.i(TAG, "SMS to " + destination + ": " + message);
        // TODO(ping): Use sentIntent and deliveryIntent to detect failure and queue for resending.
        getSmsManager().sendTextMessage(destination, null, message, null, null);
    }

    /** Cleans up when the service is about to stop. */
    @Override public void onDestroy() {
        mHandler.removeCallbacks(mRunnable);
        getLocationManager().removeUpdates(this);
        if (mWakeLock != null) mWakeLock.release();
        mStarted = false;
    }

    @Nullable @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onLocationChanged(Location location) {
        mLastLocation = location;
        Log.i(TAG, "onLocationChanged: " + location);
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override public void onProviderEnabled(String provider) { }

    @Override public void onProviderDisabled(String provider) { }

    private LocationManager getLocationManager() {
        return (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    private NotificationManager getNotificationManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /**
     * Gets the appropriate SmsManager to use for sending text messages.
     * From PataBasi by Kristen Tonga.
     */
    private SmsManager getSmsManager() {
        if (android.os.Build.VERSION.SDK_INT >= 22) {
            int subscriptionId = SmsManager.getDefaultSmsSubscriptionId();
            if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {  // dual-SIM phone
                SubscriptionManager subscriptionManager = SubscriptionManager.from(getApplicationContext());
                subscriptionId = subscriptionManager.getActiveSubscriptionInfoList().get(0).getSubscriptionId();
                Log.d(TAG, "Dual SIM phone; selected subscriptionId: " + subscriptionId);
            }
            return SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
        }
        return SmsManager.getDefault();
    }

    private PowerManager getPowerManager() {
        return (PowerManager) getSystemService(Context.POWER_SERVICE);
    }
}
