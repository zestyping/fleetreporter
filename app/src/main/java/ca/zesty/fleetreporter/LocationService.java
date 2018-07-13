package ca.zesty.fleetreporter;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.GpsStatus;
import android.location.LocationManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;
import android.util.Log;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        |   recordPoint() (~ once every pref_recording_interval minutes)
        |       pref_recording_interval sets the length of time between points
        |       that will be recorded as a track or plotted on a map.
        |       pref_recording_interval_after_go should be a shorter interval
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
    public static boolean isRunning = false;

    static final String TAG = "LocationService";
    static final int NOTIFICATION_ID = 1;
    static final long LOCATION_INTERVAL_MILLIS = 1000;
    static final long CHECK_INTERVAL_MILLIS = 10 * 1000;
    static final long TRANSMISSION_INTERVAL_MILLIS = 30 * 1000;
    static final long DEFAULT_SETTLING_PERIOD_MILLIS = 2 * 60 * 1000;
    static final long ALARM_INTERVAL_MILLIS = 10 * 1000;
    static final int POINTS_PER_SMS_MESSAGE = 2;
    static final int MAX_OUTBOX_SIZE = 48;
    static final String ACTION_POINT_RECEIVED = "FLEET_REPORTER_POINT_RECEIVED";
    static final String ACTION_SERVICE_CHANGED = "FLEET_REPORTER_SERVICE_CHANGED";
    static final String ACTION_SMS_SENT = "FLEET_REPORTER_SMS_SENT";
    static final String EXTRA_SENT_KEYS = "sent_keys";

    // TODO(ping): These constants all depend on the mobile network provider.
    static final long CREDIT_LOW_THRESHOLD = 10;  // when balance falls this low, buy more
    static final long CREDIT_PURCHASE_TTL_MILLIS = 23 * 60 * 60 * 1000;  // assume purchased credit expires after this duration
    static final String CREDIT_PURCHASE_USSD_CODE = "#100*2*1#";  // Orange 250-SMS "Kota Songo" bundle purchase
    static final Pattern CREDIT_PURCHASE_COMPLETED_PATTERN = Pattern.compile("Votre forfait.*est activ");
    static final long CREDIT_PURCHASE_SMS_COUNT = 50;  // number of SMS messages purchased in a bundle
    static final long CREDIT_BALANCE_CHECK_INTERVAL_MILLIS = 10 * 60 * 1000;  // check balance every 10 minutes
    static final String CREDIT_BALANCE_CHECK_USSD_CODE = "#100*2*2#";  // Orange 250-SMS "Kota Songo" balance check
    static final Pattern CREDIT_BALANCE_CHECK_PATTERN = Pattern.compile("Vous disposez .* ([0-9]+) SMS");
    static final Pattern CREDIT_BALANCE_EMPTY_PATTERN = Pattern.compile("pas de forfait en cours");
    static final long CREDIT_BALANCE_DEFAULT_TTL_MILLIS = 60 * 60 * 1000;  // if no expiration time can be parsed, assume balance expires after this duration
    static final Pattern CREDIT_BALANCE_EXPIRATION_PATTERN = Pattern.compile("valable jusqu'au (\\d+-\\d+-\\d+ )\\D{0,6}(\\d+:\\d+:\\d+)");
    static final String CREDIT_BALANCE_EXPIRATION_FORMAT = "$1 $2 +0100";  // format for pattern groups above, to be parsed by parser below
    static final DateFormat CREDIT_BALANCE_EXPIRATION_PARSER = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss Z");

    private Handler mHandler = null;
    private Runnable mRunnable = null;
    private SmsStatusReceiver mSmsStatusReceiver = new SmsStatusReceiver();
    private UssdReplyReceiver mUssdReplyReceiver = new UssdReplyReceiver();
    private PowerManager.WakeLock mWakeLock = null;

    private LocationAdapter mLocationAdapter = null;
    private NmeaListener mNmeaListener = null;
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener;
    private Point mPoint = null;  // non-provisional, null means no GPS
    private LocationFix mLastFix = null;  // possibly provisional, never null after first assigned
    private LocationFix mDistanceAnchor = null;
    private double mMetersTravelledSinceStop = 0;
    private Long mNoGpsSinceTimeMillis = null;
    private long mLastBalanceCheckMillis = 0;

    private String mLastReporterId;
    private Point mLastRecordedPoint;
    private long mLastTransmissionAttemptMillis = 0;
    private Long mLastSmsSentMillis = null;
    private Long mSmsFailingSinceMillis = null;
    private SortedMap<Long, Point> mOutbox = new TreeMap<>(new Comparator<Long>() {
        @Override public int compare(Long a, Long b) {  // sort in descending order
            return b > a ? 1 : b < a ? -1 : 0;
        }
    });

    @Override public void onCreate() {
        super.onCreate();
        mHandler = new Handler();
        mRunnable = new Runnable() {
            public void run() {
                checkWhetherToRecordPoint();
                checkWhetherToTransmitMessages();
                checkWhetherToPurchaseCredit(0);
                mHandler.postDelayed(mRunnable, CHECK_INTERVAL_MILLIS);
            }
        };
        registerReceiver(mSmsStatusReceiver, new IntentFilter(ACTION_SMS_SENT));
        registerReceiver(mUssdReplyReceiver, new IntentFilter(UssdDialogReaderService.ACTION_USSD_RECEIVED));
        mWakeLock = u.getPowerManager().newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "LocationService");
        mLocationAdapter = new LocationAdapter(new MotionListener(this, new SettlingPeriodGetter()));
        mNmeaListener = new NmeaListener();
        mPrefsListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override public void onSharedPreferenceChanged(SharedPreferences preferences, String s) {
                updateNotification();
            }
        };
    }

    /** Starts running the service. */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand: flags = " + flags);
        if (u.getIntPref(Prefs.RUNNING, 0) == 1) {
            // Set an alarm to restart this service, in case it crashes.
            setRestartAlarm();
            if (!isRunning) {
                // Grab the CPU.
                isRunning = true;
                mWakeLock.acquire();
                startForeground(NOTIFICATION_ID, buildNotification());
                u.getPrefs().registerOnSharedPreferenceChangeListener(mPrefsListener);

                // Activate the GPS receiver.
                mNoGpsSinceTimeMillis = getGpsTimeMillis();
                u.getLocationManager().requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MILLIS, 0, mLocationAdapter);
                u.getLocationManager().addNmeaListener(new NmeaListener());

                // Start periodically recording and transmitting points.
                mHandler.postDelayed(mRunnable, 0);
                sendBroadcast(new Intent(ACTION_SERVICE_CHANGED));
            }
        }
        return START_STICKY;
    }

    /** Cleans up when the service is about to stop. */
    @Override public void onDestroy() {
        mHandler.removeCallbacks(mRunnable);
        u.getLocationManager().removeUpdates(mLocationAdapter);
        u.getLocationManager().removeNmeaListener(mNmeaListener);
        if (mWakeLock.isHeld()) mWakeLock.release();
        isRunning = false;
        unregisterReceiver(mSmsStatusReceiver);
        unregisterReceiver(mUssdReplyReceiver);
        u.getPrefs().unregisterOnSharedPreferenceChangeListener(mPrefsListener);
        sendBroadcast(new Intent(ACTION_SERVICE_CHANGED));
    }

    private void setRestartAlarm() {
        Log.i(TAG, "setRestartAlarm");
        Intent intent = new Intent(this, LocationService.class);
        PendingIntent pi = PendingIntent.getService(this, 0, intent, 0);
        u.getAlarmManager().cancel(pi);
        u.getAlarmManager().set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + ALARM_INTERVAL_MILLIS,
            pi
        );
    }

    public Long getNoGpsSinceTimeMillis() {
        return mNoGpsSinceTimeMillis;
    }

    public LocationFix getLastLocationFix() {
        return mLastFix;
    }

    public boolean isResting() {
        return mPoint != null && (
            mPoint.type == Point.Type.STOP || mPoint.type == Point.Type.RESTING
        );
    }

    public Long getMillisSinceLastTransition() {
        return mPoint != null ? getGpsTimeMillis() - mPoint.lastTransitionMillis : null;
    }

    public double getMetersTravelledSinceStop() {
        return mMetersTravelledSinceStop;
    }

    public Long getSmsFailingSinceMillis() {
        return mSmsFailingSinceMillis;
    }

    public Long getLastSmsSentMillis() {
        return mLastSmsSentMillis;
    }

    /** Creates the notification to show while the service is running. */
    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        String message =
            (mNoGpsSinceTimeMillis != null ? "GPS signal lost.  " : "") +
            (mSmsFailingSinceMillis != null ? "Unable to send SMS.  " : "");
        if (message.isEmpty()) message = "Reporting your location.  ";
        if (mLastSmsSentMillis != null) message += Utils.format(
            "Last SMS sent %s.  ", Utils.describeTime(mLastSmsSentMillis));
        int minutes = (int) Math.max(
            0, Math.ceil((getNextRecordingMillis() - getGpsTimeMillis()) / 60000));
        message += minutes == 0 ?
            "Next report in < 1 min." :
            Utils.format("Next report in %d min.", minutes);

        return new NotificationCompat.Builder(this)
            .setContentTitle("Fleet Reporter")
            .setContentText(message.trim())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .build();
    }

    private void updateNotification() {
        u.getNotificationManager().notify(NOTIFICATION_ID, buildNotification());
    }

    /** Receives a new Point from the MotionListener. */
    public void onPoint(Point point, boolean isProvisional) {
        Log.i(TAG, "onPoint" + (isProvisional ? " (provisional): " : ": ") + point);

        // Keep track of how long we haven't had a GPS fix.
        if (point == null) {
            if (mNoGpsSinceTimeMillis == null) {
                mNoGpsSinceTimeMillis =
                    mLastFix != null ? mLastFix.timeMillis : getGpsTimeMillis();
            }
            return;
        }
        mNoGpsSinceTimeMillis = null;
        mLastFix = point.fix;

        // Keep track of how far we've travelled.
        if (point.type == Point.Type.GO || point.type == Point.Type.MOVING) {
            if (mDistanceAnchor == null) {
                mDistanceAnchor = point.fix;
                mMetersTravelledSinceStop = 0;
            } else {
                double distance = mDistanceAnchor.distanceTo(point.fix);
                if (distance > 2*point.fix.latLonSd && distance > 20) {  // meters
                    mMetersTravelledSinceStop += distance;
                    mDistanceAnchor = point.fix;
                }
            }
        } else {
            mDistanceAnchor = null;
        }

        // Record the point (but don't record provisional points).
        if (!isProvisional) {
            mPoint = point;
            checkWhetherToRecordPoint();
        }

        updateNotification();
        sendBroadcast(new Intent(ACTION_POINT_RECEIVED));
    }

    /** Examines the last acquired point, and moves it to the outbox if necessary. */
    private void checkWhetherToRecordPoint() {
        String reporterId = u.getPref(Prefs.REPORTER_ID);
        if (!reporterId.equals(mLastReporterId)) {
            // If registration has changed, we should restart the recording clock.
            mLastReporterId = reporterId;
            mLastRecordedPoint = null;
            mLastTransmissionAttemptMillis = 0;
            mLastSmsSentMillis = null;
        }
        if (Utils.isLocalTimeOfDayBetween(u.getPref(Prefs.SLEEP_START), u.getPref(Prefs.SLEEP_END))) {
            Log.i(TAG, "Current time is within sleep period; not recording");
            return;
        }
        if (mPoint != null) {
            // If we've just transitioned between resting and moving, record the
            // point immediately; otherwise wait until we're next scheduled to record.
            if (mPoint.isTransition() || getGpsTimeMillis() >= getNextRecordingMillis()) {
                recordPoint(mPoint);
                mPoint = null;
            }
        }
    }

    /** Returns the time that we're next scheduled to record a point. */
    private long getNextRecordingMillis() {
        // We want pref_recording_interval to be the maximum interval
        // between fix times, so schedule the next time based on the time
        // elapsed after the fix time, not after when the point was sent.
        if (mLastRecordedPoint == null) return getGpsTimeMillis();
        return mLastRecordedPoint.fix.timeMillis + (
            mLastRecordedPoint.type == Point.Type.GO ?
                u.getMinutePrefInMillis(Prefs.RECORDING_INTERVAL_AFTER_GO, 1) :
                u.getMinutePrefInMillis(Prefs.RECORDING_INTERVAL, 10)
        );
    }

    /** Records a point in the outbox, to be sent out over SMS. */
    private void recordPoint(Point point) {
        mOutbox.put(point.getSeconds(), point);
        mLastRecordedPoint = point;
        Log.i(TAG, "recordPoint: " + point + " (" + mOutbox.size() + " queued)");
        limitOutboxSize();
        checkWhetherToTransmitMessages();
        updateNotification();

        // Show the point in the app's text box.
        MainActivity.postLogMessage(this, "Recorded:\n    " + point.format());
    }

    /** Transmits points in the outbox, if it's not too soon to do so. */
    private void checkWhetherToTransmitMessages() {
        if (mOutbox.size() > 0 &&
            getGpsTimeMillis() >= mLastTransmissionAttemptMillis + TRANSMISSION_INTERVAL_MILLIS) {
            mLastTransmissionAttemptMillis = getGpsTimeMillis();
            transmitMessages();
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
        Log.i(TAG, "SMS to " + destination + ": " + message);
        u.sendSms(destination, message.trim(), new Intent(ACTION_SMS_SENT).putExtra(
            EXTRA_SENT_KEYS, Utils.toLongArray(sentKeys)
        ));

        // TODO(ping): Handle SIM slot selection.
        int slot = 0;
        String subscriberId = u.getSubscriberId(slot);
        adjustBalance(subscriberId, -1, null);
    }

    private void checkWhetherToPurchaseCredit(int slot) {
        if (!u.isAccessibilityServiceEnabled(UssdDialogReaderService.class)) {
            Log.w(TAG, "Accessibility service not enabled, skipping credit check");
            return;
        }
        if (getGpsTimeMillis() > mLastBalanceCheckMillis + CREDIT_BALANCE_CHECK_INTERVAL_MILLIS) {
            mLastBalanceCheckMillis = getGpsTimeMillis();
            u.sendUssd(slot, CREDIT_BALANCE_CHECK_USSD_CODE);
        }
        String subscriberId = u.getSubscriberId(slot);
        Long amount = getBalanceAmount(getBalance(subscriberId));
        Log.i(TAG, "Subscriber " + subscriberId + " balance is: " + amount);
        if (amount != null && amount < CREDIT_LOW_THRESHOLD) {
            u.sendUssd(slot, CREDIT_PURCHASE_USSD_CODE);
        }
    }

    /** Gets the estimated balance record for a given IMSI, returning null if unknown. */
    private BalanceEntity getBalance(String subscriberId) {
        AppDatabase db = AppDatabase.getDatabase(this);
        try {
            return db.getBalanceDao().get(subscriberId);
        } finally {
            db.close();
        }
    }

    /** Gets the amount from the estimated balance record, returning 0 if expired and null if unknown. */
    private Long getBalanceAmount(BalanceEntity balance) {
        if (balance == null) return null;
        return getGpsTimeMillis() < balance.expirationMillis ? balance.amount : 0;
    }

    /** Sets the estimated balance amount and expiration time, for a given IMSI. */
    private void setBalance(String subscriberId, long amount, long expirationMillis) {
        AppDatabase db = AppDatabase.getDatabase(this);
        try {
            BalanceEntity balance = new BalanceEntity(subscriberId, amount, expirationMillis);
            if (db.getBalanceDao().update(balance) == 0) {
                db.getBalanceDao().insert(balance);
            }
            Log.i(TAG, "Stored " + balance);
        } finally {
            db.close();
        }
    }

    /**
     * Adjusts the estimated balance for a given IMSI up or down by a given amount,
     * and updating the expiration time if optExpirationMillis is non-null.  The stored
     * balance is intended to be a minimum estimate; if the balance is unknown to
     * begin with, then decrementing it leaves it unknown, whereas incrementing it
     * sets the estimate to the increment (i.e. the true balance is now known to be
     * at least as much as the increment).
     */
    private void adjustBalance(String subscriberId, long deltaAmount, Long optExpirationMillis) {
        BalanceEntity balance = getBalance(subscriberId);
        if (balance != null || deltaAmount > 0) {
            long amount = balance != null ? getBalanceAmount(balance) : 0;
            long expirationMillis =
                optExpirationMillis != null ? optExpirationMillis :
                balance != null ? balance.expirationMillis :
                getGpsTimeMillis() + CREDIT_BALANCE_DEFAULT_TTL_MILLIS;
            setBalance(subscriberId, amount + deltaAmount, expirationMillis);
        }
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

    class SmsStatusReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (getResultCode() == Activity.RESULT_OK &&
                intent.hasExtra(EXTRA_SENT_KEYS)) {  // confirmation that SMS was sent
                for (long key : intent.getLongArrayExtra(EXTRA_SENT_KEYS)) {
                    Log.i(TAG, "sent " + key + "; removing from outbox");
                    mOutbox.remove(key);
                    mLastSmsSentMillis = getGpsTimeMillis();
                    mSmsFailingSinceMillis = null;
                }
                updateNotification();
            } else {
                if (mSmsFailingSinceMillis == null) {
                    mSmsFailingSinceMillis = getGpsTimeMillis();
                }
                Log.i(TAG, "failed to send SMS message");
            }
        }
    }

    class UssdReplyReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String subscriberId = u.getSubscriberId(0);
            String message = intent.getStringExtra(UssdDialogReaderService.EXTRA_USSD_MESSAGE);
            Matcher matcher = CREDIT_BALANCE_CHECK_PATTERN.matcher(message);
            long expirationMillis = getGpsTimeMillis() + CREDIT_BALANCE_DEFAULT_TTL_MILLIS;
            if (matcher.find()) {
                long amount = Long.parseLong(matcher.group(1));
                matcher = CREDIT_BALANCE_EXPIRATION_PATTERN.matcher(message);
                if (matcher.find()) {
                    try {
                        String expirationStamp = CREDIT_BALANCE_EXPIRATION_PATTERN.matcher(matcher.group()).replaceAll(CREDIT_BALANCE_EXPIRATION_FORMAT);
                        expirationMillis = CREDIT_BALANCE_EXPIRATION_PARSER.parse(expirationStamp).getTime();
                    } catch (ParseException e) {
                        Log.e(TAG, "Could not parse expiration time from: " + matcher.group());
                    }
                }
                setBalance(subscriberId, amount, expirationMillis);
            } else if (CREDIT_BALANCE_EMPTY_PATTERN.matcher(message).find()) {
                setBalance(subscriberId, 0, expirationMillis);
            } else if (CREDIT_PURCHASE_COMPLETED_PATTERN.matcher(message).find()) {
                adjustBalance(subscriberId, CREDIT_PURCHASE_SMS_COUNT, getGpsTimeMillis() + CREDIT_PURCHASE_TTL_MILLIS);
            }
        }
    }

    class NmeaListener implements GpsStatus.NmeaListener {
        public void onNmeaReceived(long timestamp, String nmeaMessage) {
            if (nmeaMessage.contains("GSA")) {
                if (nmeaMessage.split(",")[2].equals("1")) {  // GPS signal lost
                    mLocationAdapter.onGpsSignalLost();
                }
            }
        }
    }

    class SettlingPeriodGetter implements Getter<Long> {
        public Long get() {
            double minutes;
            try {
                minutes = Double.parseDouble(u.getPref(Prefs.SETTLING_PERIOD));
            } catch (NumberFormatException e) {
                return DEFAULT_SETTLING_PERIOD_MILLIS;
            }
            return Math.round(minutes * 60 * 1000);
        }
    }
}
