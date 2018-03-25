package ca.zesty.fleetreporter;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/** A foreground service that records the device's GPS location and periodically
    reports it via SMS to the Fleet Map device.

    Each LocationFix passes through the following stages on the way to becoming
    an outgoing message to the server:
   
    LocationManager
        |
        |   mMotionListener.onLocation() (~ once every LOCATION_INTERVAL_MILLIS)
        |       LOCATION_INTERVAL_MILLIS should be short enough that we can
        |       quickly detect when the device starts or stops moving.
        v
    mLastFix (the latest location fix)
        |
        |   recordLocationFix() (~ once every RECORDING_INTERVAL_MILLIS)
        |       RECORDING_INTERVAL_MILLIS is the length of time between fixes
        |       that will be recorded as a track or plotted on a map.
        v
    mOutbox (the queue of all location fixes yet to be sent by SMS)
        |
        |   transmitLocationFixes() (~ once every TRANSMIT_INTERVAL_MILLIS)
        |       TRANSMIT_INTERVAL_MILLIS should be long enough to receive an
        |       SMS delivery acknowledgement before attempting to retransmit,
        |       and shorter than MotionListener.STABLE_MIN_MILLIS to ensure
        |       that the message sending rate keeps up with the generation rate.
        v
    SmsManager.sendTextMessage()
 */
public class LocationService extends Service implements LocationFixListener {
    private static final String TAG = "LocationService";
    private static final int NOTIFICATION_ID = 1;
    private static final String DESTINATION_NUMBER = "+15103978793";

    private static final long LOCATION_INTERVAL_MILLIS = 10 * 1000;
    private static final long CHECK_INTERVAL_MILLIS = 10 * 1000;
    private static final long RECORDING_INTERVAL_MILLIS = 10 * 60 * 1000;
    private static final long TRANSMIT_INTERVAL_MILLIS = 30 * 1000;
    private static final int LOCATION_FIXES_PER_MESSAGE = 2;
    private static final int MAX_OUTBOX_SIZE = 48;
    private static final String ACTION_FLEET_REPORTER_SMS_SENT = "FLEET_REPORTER_SMS_SENT";
    private static final String EXTRA_SENT_KEYS = "SENT_KEYS";

    private SmsStatusReceiver mSmsStatusReceiver = new SmsStatusReceiver();
    private boolean mStarted = false;
    private PowerManager.WakeLock mWakeLock = null;
    private MotionListener mMotionListener;
    private LocationFix mLastFix = null;
    private Handler mHandler = null;
    private Runnable mRunnable = null;
    private long mClockOffset = 0;  // GPS time minus System.currentTimeMillis()
    private long mNextRecordMillis = 0;
    private long mNextTransmitMillis = 0;
    private long mNumRecorded = 0;
    private long mNumSent = 0;
    private SortedMap<Long, LocationFix> mOutbox = new TreeMap<>(new Comparator<Long>() {
        @Override public int compare(Long a, Long b) {  // sort in descending order
            return b > a ? 1 : b < a ? -1 : 0;
        }
    });

    @Override public void onCreate() {
        super.onCreate();

        // Receive broadcasts of SMS sent notifications.
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_FLEET_REPORTER_SMS_SENT);
        registerReceiver(mSmsStatusReceiver, filter);
    }

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
            mMotionListener = new MotionListener(this);
            getLocationManager().requestLocationUpdates(
                LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MILLIS, 0, mMotionListener);

            // Start periodically recording and transmitting location fixes.
            mHandler = new Handler();
            mRunnable = new Runnable() {
                public void run() {
                    checkWhetherToRecordLocationFix();
                    checkWhetherToTransmitLocationFixes();
                    mHandler.postDelayed(mRunnable, CHECK_INTERVAL_MILLIS);
                }
            };
            mHandler.postDelayed(mRunnable, 0);
        }
        return START_STICKY;
    }

    class SmsStatusReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (getResultCode() == Activity.RESULT_OK &&
                intent.hasExtra(EXTRA_SENT_KEYS)) {  // confirmation that SMS was sent
                for (long fixTime : intent.getLongArrayExtra(EXTRA_SENT_KEYS)) {
                    Log.i(TAG, "sent " + fixTime + "; removing from outbox");
                    mNumSent += 1;
                    mOutbox.remove(fixTime);
                }
                getNotificationManager().notify(NOTIFICATION_ID, buildNotification());
            } else {
                Log.i(TAG, "failed to send SMS message");
            }
        }
    }

    /** Creates the notification to show while the service is running. */
    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        long minutes = Math.max(0, (long) Math.ceil(
            (mNextRecordMillis - getGpsTimeMillis()) / 60000));
        String message = (
            mNumRecorded + " " + Utils.plural(mNumRecorded, "fix", "fixes") +
            " recorded; next in " + minutes + " minute" + Utils.plural(minutes) +
            "; " + mNumSent + " sent; " + mOutbox.size() + " queued."
        );

        return new NotificationCompat.Builder(this)
            .setContentTitle("Fleet Reporter")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .build();
    }

    /** Receives a new LocationFix from the MotionListener. */
    public void onLocationFix(LocationFix fix) {
        // The phone's clock could be inaccurate.  Whenever we get a Location,
        // we can estimate the offset between the phone's clock and GPS time,
        // and this allows us to use estimated GPS time for all stored times and
        // for scheduling all timed actions (see all uses of getGpsTimeMillis()).
        mClockOffset = fix.timeMillis - System.currentTimeMillis();
        Log.i(TAG, "onLocationFix: " + fix + " (clock offset " + mClockOffset + ")");
        mLastFix = fix;
        checkWhetherToRecordLocationFix();
    }

    /** Examines the last acquired fix, and moves it to the outbox if necessary. */
    private void checkWhetherToRecordLocationFix() {
        if (mLastFix != null) {
            // If we've just transitioned between resting and moving, record the
            // fix immediately; otherwise wait until we're next scheduled to record.
            if (mLastFix.isSegmentEnd || getGpsTimeMillis() >= mNextRecordMillis) {
                recordLocationFix(mLastFix);
                // We want RECORDING_INTERVAL_MILLIS to be the maximum interval
                // between fix times, so schedule the next time based on the
                // time elapsed after the fix time, not time elapsed after now.
                mNextRecordMillis = mLastFix.timeMillis + RECORDING_INTERVAL_MILLIS;
                mLastFix = null;
            }
        }
    }

    /** Records a location fix in the outbox, to be sent out over SMS. */
    private void recordLocationFix(LocationFix fix) {
        mOutbox.put(fix.getSeconds(), fix);
        Log.i(TAG, "recordLocationFix: " + fix + " (" + mOutbox.size() + " queued)");
        mNumRecorded += 1;
        limitOutboxSize();
        checkWhetherToTransmitLocationFixes();
        getNotificationManager().notify(NOTIFICATION_ID, buildNotification());

        // Show the location fix in the app's text box.
        Intent intent = new Intent(MainActivity.ACTION_FLEET_REPORTER_LOG_MESSAGE);
        intent.putExtra(MainActivity.EXTRA_LOG_MESSAGE, fix.format());
        sendBroadcast(intent);
    }

    /** Transmits location fixes in the outbox, if it's not too soon to do so. */
    private void checkWhetherToTransmitLocationFixes() {
        if (getGpsTimeMillis() >= mNextTransmitMillis && mOutbox.size() > 0) {
            transmitLocationFixes();
            mNextTransmitMillis = getGpsTimeMillis() + TRANSMIT_INTERVAL_MILLIS;
        }
    }

    /** Transmits some of the pending location fixes in the outbox over SMS. */
    private void transmitLocationFixes() {
        String message = "";
        List<Long> sentKeys = new ArrayList<>();
        for (Long key : mOutbox.keySet()) {
            message += mOutbox.get(key).format() + "\n";
            sentKeys.add(key);
            if (sentKeys.size() >= LOCATION_FIXES_PER_MESSAGE) break;
        }
        Log.i(TAG, "transmitLocationFixes: " + mOutbox.size() + " in queue; " +
                   "sending " + TextUtils.join(", ", sentKeys));
        Intent intent = new Intent(ACTION_FLEET_REPORTER_SMS_SENT);
        intent.putExtra(EXTRA_SENT_KEYS, Utils.toLongArray(sentKeys));
        sendSms(DESTINATION_NUMBER, message.trim(), PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_ONE_SHOT));
    }

    /** Sends an SMS message. */
    private void sendSms(String destination, String message, PendingIntent sentIntent) {
        Log.i(TAG, "SMS to " + destination + ": " + message);
        getSmsManager().sendTextMessage(destination, null, message, sentIntent, null);
    }

    /** Ensure the outbox contains no more than MAX_OUTBOX_SIZE entries. */
    private void limitOutboxSize() {
        while (mOutbox.size() > MAX_OUTBOX_SIZE) {
            mOutbox.remove(mOutbox.lastKey());
        }
    }

    /** Cleans up when the service is about to stop. */
    @Override public void onDestroy() {
        mHandler.removeCallbacks(mRunnable);
        getLocationManager().removeUpdates(mMotionListener);
        unregisterReceiver(mSmsStatusReceiver);
        if (mWakeLock != null) mWakeLock.release();
        mStarted = false;
    }

    @Nullable @Override public IBinder onBind(Intent intent) {
        return null;
    }

    /** Estimates the current GPS time. */
    private long getGpsTimeMillis() {
        return System.currentTimeMillis() + mClockOffset;
    }

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
