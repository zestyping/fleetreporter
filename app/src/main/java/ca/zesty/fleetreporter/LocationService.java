package ca.zesty.fleetreporter;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.GpsStatus;
import android.location.LocationManager;
import android.os.Handler;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/** A foreground service that records the device's GPS location and periodically
    reports it via SMS to the Fleet Map device.

    Each Point passes through the following stages on the way to becoming
    an outgoing message to the server:
   
    LocationManager
        |
        |   mMotionListener.onLocation() (~ once every LOCATION_INTERVAL_MILLIS)
        |       LOCATION_INTERVAL_MILLIS should be short enough that we can
        |       quickly detect when the device starts or stops moving.
        v
    mPoint (the latest point)
        |
        |   recordPoint() (~ once every RECORDING_INTERVAL_MILLIS)
        |       RECORDING_INTERVAL_MILLIS is the length of time between points
        |       that will be recorded as a track or plotted on a map.
        |       RECORDING_INTERVAL_AFTER_GO_MILLIS should be a shorter interval
        |       used to send a point after a transition from resting to moving.
        v
    mOutbox (the queue of all points yet to be sent by SMS)
        |
        |   transmitMessages() (~ once every TRANSMISSION_INTERVAL_MILLIS)
        |       TRANSMISSION_INTERVAL_MILLIS should be long enough to receive an
        |       SMS delivery acknowledgement before attempting to retransmit,
        |       and shorter than MotionListener.SETTLING_PERIOD_MILLIS to ensure
        |       that the message sending rate keeps up with the generation rate.
        v
    SmsManager.sendTextMessage()
 */
public class LocationService extends BaseService implements PointListener {
    static final String TAG = "LocationService";
    static final int NOTIFICATION_ID = 1;
    static final long LOCATION_INTERVAL_MILLIS = 1;// * 1000;
    static final long CHECK_INTERVAL_MILLIS = 10 * 1000;
    static final long RECORDING_INTERVAL_MILLIS = 10 * 60 * 1000;
    static final long RECORDING_INTERVAL_AFTER_GO_MILLIS = 60 * 1000;
    static final long TRANSMISSION_INTERVAL_MILLIS = 30 * 1000;
    static final int POINTS_PER_SMS_MESSAGE = 2;
    static final int MAX_OUTBOX_SIZE = 48;
    static final String ACTION_FLEET_REPORTER_SMS_SENT = "FLEET_REPORTER_SMS_SENT";
    static final String EXTRA_SENT_KEYS = "SENT_KEYS";

    private SmsStatusReceiver mSmsStatusReceiver = new SmsStatusReceiver();
    private boolean mStarted = false;
    private PowerManager.WakeLock mWakeLock = null;
    private LocationAdapter mLocationAdapter = null;
    private Point mPoint = null;
    private Handler mHandler = null;
    private Runnable mRunnable = null;
    private long mNextRecordMillis = 0;
    private long mNextTransmitMillis = 0;
    private long mNumRecorded = 0;
    private long mNumSent = 0;
    private SortedMap<Long, Point> mOutbox = new TreeMap<>(new Comparator<Long>() {
        @Override public int compare(Long a, Long b) {  // sort in descending order
            return b > a ? 1 : b < a ? -1 : 0;
        }
    });

    @Override public void onCreate() {
        super.onCreate();
        registerReceiver(mSmsStatusReceiver, new IntentFilter(ACTION_FLEET_REPORTER_SMS_SENT));
    }

    /** Starts running the service. */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!mStarted) {
            // Grab the CPU.
            mStarted = true;
            mWakeLock = u.getPowerManager().newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "LocationService");
            mWakeLock.acquire();
            startForeground(NOTIFICATION_ID, buildNotification());

            // Activate the GPS receiver.
            mLocationAdapter = new LocationAdapter(new MotionListener(this));
            u.getLocationManager().requestLocationUpdates(
                LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MILLIS, 0, mLocationAdapter);

            u.getLocationManager().addNmeaListener(new NmeaMessageListener());
            u.getLocationManager().addGpsStatusListener(new GpsStatusListener());

            // Start periodically recording and transmitting points.
            mHandler = new Handler();
            mRunnable = new Runnable() {
                public void run() {
                    // Sometimes the GPS provider stops calling onLocationChanged()
                    // for a long time, if the device is stationary.  To ensure
                    // that points keep getting recorded regularly, we need to
                    // emit extra fixes to fill in these gaps.
                    mLocationAdapter.ensureFixEmittedWithinLast(
                        LOCATION_INTERVAL_MILLIS * 2);
                    checkWhetherToRecordPoint();
                    checkWhetherToTransmitMessages();
                    mHandler.postDelayed(mRunnable, CHECK_INTERVAL_MILLIS);
                }
            };
            mHandler.postDelayed(mRunnable, 0);
        }
        return START_STICKY;
    }

    /** Cleans up when the service is about to stop. */
    @Override public void onDestroy() {
        mHandler.removeCallbacks(mRunnable);
        u.getLocationManager().removeUpdates(mLocationAdapter);
        unregisterReceiver(mSmsStatusReceiver);
        if (mWakeLock != null) mWakeLock.release();
        mStarted = false;
    }

    class SmsStatusReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (getResultCode() == Activity.RESULT_OK &&
                intent.hasExtra(EXTRA_SENT_KEYS)) {  // confirmation that SMS was sent
                for (long key : intent.getLongArrayExtra(EXTRA_SENT_KEYS)) {
                    Log.i(TAG, "sent " + key + "; removing from outbox");
                    mNumSent += 1;
                    mOutbox.remove(key);
                }
                u.getNotificationManager().notify(NOTIFICATION_ID, buildNotification());
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
            mNumRecorded + " " + Utils.plural(mNumRecorded, "point", "points") +
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

    /** Receives a new Point from the MotionListener. */
    public void onPoint(Point point) {
        Log.i(TAG, "onPoint: " + point);
        mPoint = point;
        checkWhetherToRecordPoint();
    }

    /** Examines the last acquired point, and moves it to the outbox if necessary. */
    private void checkWhetherToRecordPoint() {
        if (mPoint != null) {
            // If we've just transitioned between resting and moving, record the
            // point immediately; otherwise wait until we're next scheduled to record.
            if (mPoint.isTransition() || getGpsTimeMillis() >= mNextRecordMillis) {
                recordPoint(mPoint);
                // We want RECORDING_INTERVAL_MILLIS to be the maximum interval
                // between fix times, so schedule the next time based on the
                // time elapsed after the fix time, not time elapsed after now.
                mNextRecordMillis = mPoint.fix.timeMillis + (
                    mPoint.type == Point.Type.GO ?
                    RECORDING_INTERVAL_AFTER_GO_MILLIS : RECORDING_INTERVAL_MILLIS
                );
                mPoint = null;
            }
        }
    }

    /** Records a point in the outbox, to be sent out over SMS. */
    private void recordPoint(Point point) {
        mOutbox.put(point.getSeconds(), point);
        Log.i(TAG, "recordPoint: " + point + " (" + mOutbox.size() + " queued)");
        mNumRecorded += 1;
        limitOutboxSize();
        checkWhetherToTransmitMessages();
        u.getNotificationManager().notify(NOTIFICATION_ID, buildNotification());

        // Show the point in the app's text box.
        postLogMessage("Recorded:\n    " + point.format());
    }

    private void postLogMessage(String message) {
        Intent intent = new Intent(MainActivity.ACTION_FLEET_REPORTER_LOG_MESSAGE);
        intent.putExtra(MainActivity.EXTRA_LOG_MESSAGE,
            Utils.formatUtcTimeSeconds(System.currentTimeMillis()) + " - " + message);
        sendBroadcast(intent);
    }

    /** Transmits points in the outbox, if it's not too soon to do so. */
    private void checkWhetherToTransmitMessages() {
        if (getGpsTimeMillis() >= mNextTransmitMillis && mOutbox.size() > 0) {
            transmitMessages();
            mNextTransmitMillis = getGpsTimeMillis() + TRANSMISSION_INTERVAL_MILLIS;
        }
    }

    /** Transmits some of the pending points in the outbox over SMS. */
    private void transmitMessages() {
        String destination = u.getPref(Prefs.DESTINATION_NUMBER);
        if (destination == null) return;
        String message = "";
        List<Long> sentKeys = new ArrayList<>();
        for (Long key : mOutbox.keySet()) {
            message += mOutbox.get(key).format() + "\n";
            sentKeys.add(key);
            if (sentKeys.size() >= POINTS_PER_SMS_MESSAGE) break;
        }
        Log.i(TAG, "transmitMessages: " + mOutbox.size() + " in queue; " +
                   "sending " + TextUtils.join(", ", sentKeys));
        Intent intent = new Intent(ACTION_FLEET_REPORTER_SMS_SENT);
        intent.putExtra(EXTRA_SENT_KEYS, Utils.toLongArray(sentKeys));
        Log.i(TAG, "SMS to " + destination + ": " + message);
        u.sendSms(destination, message.trim(), intent);
    }

    /** Ensure the outbox contains no more than MAX_OUTBOX_SIZE entries. */
    private void limitOutboxSize() {
        while (mOutbox.size() > MAX_OUTBOX_SIZE) {
            mOutbox.remove(mOutbox.lastKey());
        }
    }

    private long getGpsTimeMillis() {
        return mLocationAdapter == null ?
            System.currentTimeMillis() : mLocationAdapter.getGpsTimeMillis();
    }

    class NmeaMessageListener implements GpsStatus.NmeaListener {
        public void onNmeaReceived(long timestamp, String nmeaMessage) {
            if (nmeaMessage.startsWith("$GLGSV")) return;
            if (nmeaMessage.startsWith("$GPGSV")) return;
            if (nmeaMessage.startsWith("$GPVTG")) return;
            //postLogMessage("NMEA at " + Utils.formatUtcTimeSeconds(timestamp) + ":\n    " +
            //    nmeaMessage);
        }
    }

    class GpsStatusListener implements GpsStatus.Listener {
        public void onGpsStatusChanged(int event) {
            switch (event) {
                case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
                    // postLogMessage("GPS event: Satellite status");
                    break;
                case GpsStatus.GPS_EVENT_FIRST_FIX:
                    // postLogMessage("GPS event: First fix");
                    break;
            }
        }
    }
}
