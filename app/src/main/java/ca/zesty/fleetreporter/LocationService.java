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
import android.os.BatteryManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;
import android.util.Log;

import com.crashlytics.android.Crashlytics;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.fabric.sdk.android.Fabric;

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
        |   transmitPoints() (~ once every TRANSMISSION_INTERVAL_MILLIS)
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
    static final long SECOND = 1000;
    static final long MINUTE = 60 * SECOND;
    static final long HOUR = 60 * MINUTE;
    static final long LOCATION_INTERVAL_MILLIS = SECOND;
    static final long LOOP_INTERVAL_MILLIS = 10 * SECOND;
    static final long TRANSMISSION_INTERVAL_MILLIS = 30 * SECOND;
    static final long DEFAULT_SETTLING_PERIOD_MILLIS = 2 * MINUTE;
    static final long ALARM_INTERVAL_MILLIS = 10 * SECOND;
    static final long VELOCITY_MIN_INTERVAL_MILLIS = 20 * SECOND;
    static final long VELOCITY_MAX_INTERVAL_MILLIS = 35 * SECOND;
    static final int VELOCITY_NUM_SAMPLES = 4;
    static final int POINTS_PER_SMS_MESSAGE = 2;
    static final int MAX_OUTBOX_SIZE = 48;
    static final String ACTION_POINT_RECEIVED = "FLEET_REPORTER_POINT_RECEIVED";
    static final String ACTION_SERVICE_CHANGED = "FLEET_REPORTER_SERVICE_CHANGED";
    static final String ACTION_SMS_SENT = "FLEET_REPORTER_SMS_SENT";
    static final String EXTRA_SENT_KEYS = "sent_keys";
    static final String EXTRA_SLOT = "slot";

    // TODO(ping): These constants all depend on the mobile network provider.
    static final long CREDIT_MANAGEMENT_INTERVAL_MILLIS = 2 * MINUTE;
    static final long SMS_LOW_THRESHOLD = 10;  // when balance falls this low, buy more
    static final long SMS_PURCHASE_TTL_MILLIS = 23 * HOUR;  // assume purchased credit expires after this duration
    static final String SMS_PURCHASE_USSD_CODE = "#100*2*1#";  // Orange 250-SMS "Kota Songo" bundle purchase
    static final Pattern SMS_PURCHASE_COMPLETED_PATTERN = Pattern.compile("Votre forfait.*est activ");
    static final long SMS_PURCHASE_QUANTITY = 50;  // number of SMS messages purchased in a bundle
    static final long BALANCE_CHECK_INTERVAL_MILLIS = 30 * MINUTE;  // check balance every 30 minutes
    static final String SMS_BALANCE_CHECK_USSD_CODE = "#100*2*2#";  // Orange 250-SMS "Kota Songo" balance check
    static final Pattern SMS_BALANCE_CHECK_PATTERN = Pattern.compile("Vous disposez .* ([0-9]+) SMS");
    static final Pattern SMS_BALANCE_EMPTY_PATTERN = Pattern.compile("pas de forfait en cours");
    static final long SMS_BALANCE_DEFAULT_TTL_MILLIS = HOUR;  // if no expiration time can be parsed, assume balance expires after this duration
    static final Pattern SMS_BALANCE_EXPIRATION_PATTERN = Pattern.compile("valable jusqu'au (\\d+-\\d+-\\d+ )\\D{0,6}(\\d+:\\d+:\\d+)");
    static final String SMS_BALANCE_EXPIRATION_FORMAT = "$1 $2 +0100";  // format for pattern groups above, to be parsed by parser below
    static final DateFormat SMS_BALANCE_EXPIRATION_PARSER = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss Z");
    static final String CFA_BALANCE_CHECK_USSD_CODE = "#111*1*1#";  // Orange main account balance in CFA
    static final String CFA_SLOT_1_BALANCE_CHECK_USSD_CODE = "*121#";  // Azur main account balance in CFA

    private Handler mHandler = null;
    private Runnable mRunnable = null;
    private SmsStatusReceiver mSmsStatusReceiver = new SmsStatusReceiver();
    private UssdReplyReceiver mUssdReplyReceiver = new UssdReplyReceiver();
    private PointRequestReceiver mPointRequestReceiver = new PointRequestReceiver();
    private LowCreditReceiver mLowCreditReceiver = new LowCreditReceiver();
    private UssdRequestReceiver mUssdRequestReceiver = new UssdRequestReceiver();
    private BatteryRequestReceiver mBatteryRequestReceiver = new BatteryRequestReceiver();

    private PowerManager.WakeLock mWakeLock = null;

    private LocationAdapter mLocationAdapter = null;
    private NmeaListener mNmeaListener = null;
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener;
    private List<Point> mVelocityPoints = new ArrayList<>();  // for calculating average velocity
    private Point mPoint = null;  // latest non-provisional point that hasn't been transmitted yet
    private LocationFix mLastFix = null;  // latest fix, possibly provisional, never null after first assigned
    private LocationFix mDistanceAnchor = null;
    private double mMetersTravelledSinceStop = 0;
    private Long mNoGpsSinceTimeMillis = null;
    private long mLastSmsPurchaseMillis = 0;
    private long mLastSmsBalanceCheckMillis = 0;
    private long mLastCfaBalanceCheckMillis = 0;
    private long mLastSlot1BalanceCheckMillis = 0;
    private long mLastCreditCheckMillis = 0;
    private String mTransmitNextUssdReplyDestination = null;

    private String mLastReporterId;
    private Point mLastRecordedPoint;
    private int mNumSimSlots;
    private long[] mLastFailedTransmissionMillis;
    private long[] mNextTransmissionAttemptMillis;
    private long mLastTransmittedGpsOutageMillis = 0;
    private Long mLastSmsSentMillis = null;
    private Long mSmsFailingSinceMillis = null;
    private int mNextSimSlot = 0;
    private SortedMap<Long, Point> mOutbox = new TreeMap<>(new Comparator<Long>() {
        @Override public int compare(Long a, Long b) {  // sort in descending order
            return b > a ? 1 : b < a ? -1 : 0;
        }
    });

    private long mLastLogTransmissionMillis = Utils.getTime();
    private int mLastRelaunchCheckMinutes = Utils.getLocalMinutesSinceMidnight();

    @Override public void onCreate() {
        super.onCreate();
        Utils.log(TAG, "onCreate");
        Fabric.with(this, new Crashlytics());
        mHandler = new Handler();
        mRunnable = new Runnable() {
            public void run() {
                checkWhetherToRecordPoint();
                checkWhetherToTransmitPoints();
                checkWhetherToPurchaseCredit(0);
                checkWhetherToRelaunchApp();
                mHandler.postDelayed(mRunnable, LOOP_INTERVAL_MILLIS);
            }
        };
        registerReceiver(mSmsStatusReceiver, new IntentFilter(ACTION_SMS_SENT));
        registerReceiver(mUssdReplyReceiver, new IntentFilter(UssdReceiverService.ACTION_USSD_RECEIVED));
        registerReceiver(mPointRequestReceiver, new IntentFilter(SmsReceiver.ACTION_POINT_REQUEST));
        registerReceiver(mUssdRequestReceiver, new IntentFilter(SmsReceiver.ACTION_USSD_REQUEST));
        registerReceiver(mBatteryRequestReceiver, new IntentFilter(SmsReceiver.ACTION_BATTERY_REQUEST));
        registerReceiver(mLowCreditReceiver, new IntentFilter(SmsReceiver.ACTION_LOW_CREDIT));
        mWakeLock = u.getPowerManager().newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "LocationService");
        mLocationAdapter = new LocationAdapter(
            this, new MotionListener(this, new SettlingPeriodGetter()));
        mNmeaListener = new NmeaListener();
        mPrefsListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override public void onSharedPreferenceChanged(SharedPreferences preferences, String s) {
                updateNotification();
            }
        };
    }

    /** Starts running the service. */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Crashlytics.setString("reporter_id", u.getPref(Prefs.REPORTER_ID));
        Crashlytics.setString("reporter_label", u.getPref(Prefs.REPORTER_LABEL));
        if (u.getBooleanPref(Prefs.RUNNING)) {
            // Set an alarm to restart this service, in case it crashes.
            setRestartAlarm();
            if (!isRunning) {
                Utils.logRemote(TAG, "Startup");
                mNumSimSlots = u.getNumSimSlots();
                mLastFailedTransmissionMillis = new long[mNumSimSlots];
                mNextTransmissionAttemptMillis = new long[mNumSimSlots];

                // Grab the CPU.
                isRunning = true;
                mWakeLock.acquire();
                startForeground(NOTIFICATION_ID, buildNotification());
                u.getPrefs().registerOnSharedPreferenceChangeListener(mPrefsListener);

                // Activate the GPS receiver.
                mNoGpsSinceTimeMillis = Utils.getTime();
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
        Utils.logRemote(TAG, "onDestroy");
        mHandler.removeCallbacks(mRunnable);
        u.getLocationManager().removeUpdates(mLocationAdapter);
        u.getLocationManager().removeNmeaListener(mNmeaListener);
        if (mWakeLock.isHeld()) mWakeLock.release();
        isRunning = false;
        unregisterReceiver(mSmsStatusReceiver);
        unregisterReceiver(mUssdReplyReceiver);
        unregisterReceiver(mPointRequestReceiver);
        unregisterReceiver(mUssdRequestReceiver);
        unregisterReceiver(mBatteryRequestReceiver);
        unregisterReceiver(mLowCreditReceiver);
        u.getPrefs().unregisterOnSharedPreferenceChangeListener(mPrefsListener);
        sendBroadcast(new Intent(ACTION_SERVICE_CHANGED));
    }

    private void setRestartAlarm() {
        Intent intent = new Intent(this, LocationService.class);
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent, 0);
        u.getAlarmManager().cancel(pendingIntent);
        u.getAlarmManager().set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + ALARM_INTERVAL_MILLIS,
            pendingIntent
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
        return mPoint != null ? Utils.getTime() - mPoint.lastTransitionMillis : null;
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
            0, Math.ceil((getNextRecordingMillis() - Utils.getTime()) / 60000));
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
        Utils.log(TAG, "onPoint" + (isProvisional ? " (provisional): " : ": ") + point);

        // Keep track of how long we haven't had a GPS fix.
        if (point == null) {
            if (mNoGpsSinceTimeMillis == null) {
                mNoGpsSinceTimeMillis =
                    mLastFix != null ? mLastFix.timeMillis : Utils.getTime();
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

        // Keep around some recent points to help us compute average velocity.
        mVelocityPoints.add(point);
        long now = Utils.getTime();
        while (!mVelocityPoints.isEmpty() &&
            mVelocityPoints.get(0).fix.timeMillis < now - VELOCITY_MAX_INTERVAL_MILLIS) {
            mVelocityPoints.remove(0);
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
            mNextTransmissionAttemptMillis = new long[mNumSimSlots];
            mLastSmsSentMillis = null;
        }
        long now = Utils.getTime();
        if (mNoGpsSinceTimeMillis != null && now >= getNextRecordingMillis() &&
            now >= mLastTransmittedGpsOutageMillis + u.getMinutePrefInMillis(Prefs.RECORDING_INTERVAL, 10)) {
            // If it's time to record a point and we have a GPS outage, notify the receiver.
            transmitGpsOutage();
            mLastTransmittedGpsOutageMillis = Utils.getTime();
        }
        if (mPoint != null) {
            // If we've just transitioned between resting and moving, record the
            // point immediately; otherwise wait until we're next scheduled to record.
            if (mPoint.isTransition() || Utils.getTime() >= getNextRecordingMillis()) {
                if (Utils.isLocalTimeOfDayBetween(u.getPref(Prefs.SLEEP_START), u.getPref(Prefs.SLEEP_END))) {
                    Utils.log(TAG, "Current time is within sleep period; not recording");
                    return;
                }
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
        if (mLastRecordedPoint == null) return Utils.getTime();
        return mLastRecordedPoint.fix.timeMillis + (
            mLastRecordedPoint.type == Point.Type.GO ?
                u.getMinutePrefInMillis(Prefs.RECORDING_INTERVAL_AFTER_GO, 1) :
                u.getMinutePrefInMillis(Prefs.RECORDING_INTERVAL, 10)
        );
    }

    /** Records a point in the outbox, to be sent out over SMS. */
    private void recordPoint(Point point) {
        point = adjustVelocity(point);
        mOutbox.put(point.getSeconds(), point);
        mLastRecordedPoint = point;
        Utils.log(TAG, "recordPoint: %s (%d queued)", point, mOutbox.size());
        limitOutboxSize();
        checkWhetherToTransmitPoints();
        updateNotification();

        // Show the point in the app's text box.
        MainActivity.postLogMessage(this, "Recorded:\n    " + point.format());
    }

    /** Replaces the point's speed and bearing with smoothed values, if possible. */
    private Point adjustVelocity(Point point) {
        int n = mVelocityPoints.size();
        if (n == 0) return point;
        LocationFix start = getAverageFix(mVelocityPoints.subList(0, Math.min(n, VELOCITY_NUM_SAMPLES)));
        LocationFix stop = getAverageFix(mVelocityPoints.subList(Math.max(0, n - VELOCITY_NUM_SAMPLES), n));
        double interval = stop.timeMillis - start.timeMillis;
        if (interval < VELOCITY_MIN_INTERVAL_MILLIS) return point;
        Utils.log(TAG, "Computing adjusted velocity: %.1f s from %s to %s", interval / SECOND, start, stop);
        double distance = start.distanceTo(stop);
        double speedKmh = (distance/1000.0)/(interval/HOUR);
        double bearing = start.bearingTo(stop);
        return point.withFix(point.fix.withSpeedAndBearing(speedKmh, bearing));
    }

    private LocationFix getAverageFix(List<Point> points) {
        double totalLat = 0;
        double totalLon = 0;
        long totalMillis = 0;
        int n = 0;
        for (Point point : points) {
            totalLat += point.fix.latitude;
            totalLon += point.fix.longitude;
            totalMillis += point.fix.timeMillis;
            n++;
        }
        return new LocationFix(totalMillis / n, totalLat / n, totalLon / n, 0, 0, 0, 0);
    }

    /** Transmits points in the outbox, if it's not too soon to do so. */
    private void checkWhetherToTransmitPoints() {
        long now = Utils.getTime();
        if (mOutbox.size() > 0 && now >= mNextTransmissionAttemptMillis[mNextSimSlot]) {
            Arrays.fill(mNextTransmissionAttemptMillis, now + TRANSMISSION_INTERVAL_MILLIS);
            transmitPoints(mNextSimSlot);
        }
    }

    /** Transmits some of the pending points in the outbox over SMS. */
    private void transmitPoints(int slot) {
        String destination = u.getPref(Prefs.DESTINATION_NUMBER);
        if (destination == null) return;
        String message = "";
        List<Long> sentKeys = new ArrayList<>();
        for (Long key : mOutbox.keySet()) {
            message += mOutbox.get(key).format() + "\n";
            sentKeys.add(key);
            if (sentKeys.size() >= POINTS_PER_SMS_MESSAGE) break;
        }
        Utils.logRemote(TAG, "transmitPoints: %d in queue; sending %s",
            mOutbox.size(), TextUtils.join(", ", sentKeys));
        u.sendSms(slot, destination, message.trim(), new Intent(ACTION_SMS_SENT)
            .putExtra(EXTRA_SENT_KEYS, Utils.toLongArray(sentKeys))
            .putExtra(EXTRA_SLOT, slot)
        );
        adjustBalance(u.getImsi(slot), -1, null);

        // Next time, try a different slot.  If a text is successfully dispatched,
        // SmsStatusReceiver will reset mNextSimSlot to 0.
        mNextSimSlot = (slot + 1) % mNumSimSlots;
    }

    private void transmitGpsOutage() {
        transmitOnAllSlots(
            "fleet gpsoutage " + Utils.formatUtcTimeSeconds(Utils.getTime()));
    }

    private void transmitOnAllSlots(String message) {
        String destination = u.getPref(Prefs.DESTINATION_NUMBER);
        if (destination == null) return;
        for (int slot = 0; slot < mNumSimSlots; slot++) {
            u.sendSms(slot, destination, message);
        }
    }

    private void checkWhetherToPurchaseCredit(int slot) {
        long now = Utils.getTime();
        if (now < mLastCreditCheckMillis + CREDIT_MANAGEMENT_INTERVAL_MILLIS) return;
        mLastCreditCheckMillis = now;

        if (!u.isAccessibilityServiceEnabled(UssdReceiverService.class)) {
            Utils.logRemote(TAG, "Accessibility service not enabled, skipping credit check");
            return;
        }
        String subscriberId = u.getImsi(slot);
        Long amount = getBalanceAmount(getBalance(subscriberId));
        if (amount != null && amount < SMS_LOW_THRESHOLD) {
            Utils.logRemote(TAG, "Subscriber %s balance is: %s", subscriberId, amount);
            long purchaseIntervalMillis = u.getIntPref(Prefs.SMS_PURCHASE_INTERVAL, 60) * MINUTE;
            long waitMillis = mLastSmsPurchaseMillis + purchaseIntervalMillis - now;
            if (waitMillis > 0) {
                Utils.logRemote(TAG, "Must wait %d min before purchasing another SMS package", waitMillis / MINUTE);
            } else {
                Utils.logRemote(TAG, "Purchasing an SMS package");
                u.sendUssd(slot, SMS_PURCHASE_USSD_CODE);
            }
        } else if (now > mLastSmsBalanceCheckMillis + BALANCE_CHECK_INTERVAL_MILLIS) {
            mLastSmsBalanceCheckMillis = now;
            u.sendUssd(slot, SMS_BALANCE_CHECK_USSD_CODE);
        } else if (now > mLastCfaBalanceCheckMillis + BALANCE_CHECK_INTERVAL_MILLIS) {
            mLastCfaBalanceCheckMillis = now;
            u.sendUssd(slot, CFA_BALANCE_CHECK_USSD_CODE);
        } else if (now > mLastSlot1BalanceCheckMillis + BALANCE_CHECK_INTERVAL_MILLIS) {
            mLastSlot1BalanceCheckMillis = now;
            u.sendUssd(1, CFA_SLOT_1_BALANCE_CHECK_USSD_CODE);
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
        return Utils.getTime() < balance.expirationMillis ? balance.amount : 0;
    }

    /** Sets the estimated balance amount and expiration time, for a given IMSI. */
    private void setBalance(String subscriberId, long amount, long expirationMillis) {
        AppDatabase db = AppDatabase.getDatabase(this);
        try {
            BalanceEntity balance = new BalanceEntity(subscriberId, amount, expirationMillis);
            if (db.getBalanceDao().update(balance) == 0) {
                db.getBalanceDao().insert(balance);
            }
            Utils.log(TAG, "Stored " + balance);
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
                Utils.getTime() + SMS_BALANCE_DEFAULT_TTL_MILLIS;
            setBalance(subscriberId, amount + deltaAmount, expirationMillis);
        }
    }

    /** Ensure the outbox contains no more than MAX_OUTBOX_SIZE entries. */
    private void limitOutboxSize() {
        while (mOutbox.size() > MAX_OUTBOX_SIZE) {
            mOutbox.remove(mOutbox.lastKey());
        }
    }

    private void checkWhetherToRelaunchApp() {
        // Relaunch daily at a configurable time (default midnight).
        String relaunchTime = u.getPref(Prefs.DAILY_RELAUNCH_TIME);
        int relaunchTimeMinutes = Utils.countMinutesSinceMidnight(relaunchTime);
        int localMinutes = Utils.getLocalMinutesSinceMidnight();
        if (mLastRelaunchCheckMinutes != relaunchTimeMinutes && localMinutes == relaunchTimeMinutes) {
            Utils.logRemote(TAG, "Relaunching app (local time of day is %s)", relaunchTime);
            u.relaunchApp();
        }
        mLastRelaunchCheckMinutes = localMinutes;
    }

    class SmsStatusReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra(EXTRA_SENT_KEYS) && intent.hasExtra(EXTRA_SLOT)) {
                // This is the status of an SMS that transmitPoints() sent.
                long[] keys = intent.getLongArrayExtra(EXTRA_SENT_KEYS);
                int slot = intent.getIntExtra(EXTRA_SLOT, 0);
                long now = Utils.getTime();
                if (getResultCode() == Activity.RESULT_OK) {
                    for (long key : keys) {
                        Utils.logRemote(TAG, "Sent %d on slot %d; removing from outbox", key, slot);
                        mOutbox.remove(key);
                        mLastSmsSentMillis = now;
                        mSmsFailingSinceMillis = null;
                        mNextSimSlot = 0;
                    }
                    updateNotification();
                } else {
                    if (mSmsFailingSinceMillis == null) {
                        mSmsFailingSinceMillis = now;
                    }
                    Utils.logRemote(TAG, "Failed to send SMS on slot %d", slot);
                    mLastFailedTransmissionMillis[slot] = now;
                    mNextSimSlot = (slot + 1) % mNumSimSlots;
                    if (now > mLastFailedTransmissionMillis[mNextSimSlot] + TRANSMISSION_INTERVAL_MILLIS) {
                        mNextTransmissionAttemptMillis[mNextSimSlot] = now;
                        Utils.logRemote(TAG, "Retrying on slot %d immediately", mNextSimSlot);
                        checkWhetherToTransmitPoints();
                    } else {
                        Utils.logRemote(TAG, "Retrying on slot %d eventually", mNextSimSlot);
                    }
                }
            }
        }
    }

    class UssdReplyReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(UssdReceiverService.EXTRA_USSD_MESSAGE);
            if (mTransmitNextUssdReplyDestination != null) {
                message = "fleet ussdreply " + message;
                for (int slot = 0; slot < mNumSimSlots; slot++) {
                    u.sendSms(slot, mTransmitNextUssdReplyDestination, message);
                }
                mTransmitNextUssdReplyDestination = null;
            }

            String subscriberId = u.getImsi(0);
            long now = Utils.getTime();
            long expirationMillis = now + SMS_BALANCE_DEFAULT_TTL_MILLIS;

            Matcher matcher = SMS_BALANCE_CHECK_PATTERN.matcher(message);
            if (matcher.find()) {
                long amount = Long.parseLong(matcher.group(1));
                matcher = SMS_BALANCE_EXPIRATION_PATTERN.matcher(message);
                if (matcher.find()) {
                    try {
                        String expirationStamp = SMS_BALANCE_EXPIRATION_PATTERN.matcher(matcher.group()).replaceAll(SMS_BALANCE_EXPIRATION_FORMAT);
                        expirationMillis = SMS_BALANCE_EXPIRATION_PARSER.parse(expirationStamp).getTime();
                    } catch (ParseException e) {
                        Utils.logRemote(TAG, "Could not parse expiration time: " + matcher.group());
                    }
                }
                setBalance(subscriberId, amount, expirationMillis);
            } else if (SMS_BALANCE_EMPTY_PATTERN.matcher(message).find()) {
                setBalance(subscriberId, 0, expirationMillis);
            } else if (SMS_PURCHASE_COMPLETED_PATTERN.matcher(message).find()) {
                mLastSmsPurchaseMillis = now;
                adjustBalance(subscriberId, SMS_PURCHASE_QUANTITY, now + SMS_PURCHASE_TTL_MILLIS);
            }
        }
    }

    class PointRequestReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            Utils.log(TAG, "Received request for current point: " + mPoint);
            if (mNoGpsSinceTimeMillis != null) {
                transmitGpsOutage();
                return;
            }
            if (mPoint == null) {
                if (mLastRecordedPoint != null) {
                    Utils.log(TAG, "Resending last recorded point: " + mLastRecordedPoint);
                    mPoint = mLastRecordedPoint;
                } else {
                    Utils.log(TAG, "No last recorded point available to send");
                }
            }
            if (mPoint != null) {
                recordPoint(mPoint);
                mPoint = null;
                Arrays.fill(mNextTransmissionAttemptMillis, 0);
                checkWhetherToTransmitPoints();
            }
        }
    }

    class LowCreditReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String destination = u.getPref(Prefs.DESTINATION_NUMBER);
            if (destination == null) return;
            String amount = intent.getStringExtra(SmsReceiver.EXTRA_AMOUNT);
            Utils.log(TAG, "Forwarding low-credit alert to receiver");
            u.sendSms(0, destination, "fleet balance main_xaf " + amount);
        }
    }

    class UssdRequestReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            int slot = intent.getIntExtra(SmsReceiver.EXTRA_SLOT, 1) - 1;
            String ussdCode = intent.getStringExtra(SmsReceiver.EXTRA_USSD_CODE);
            Utils.log(TAG, "Received request for USSD command: " + ussdCode);
            mTransmitNextUssdReplyDestination = intent.getStringExtra(SmsReceiver.EXTRA_SENDER);
            u.sendUssd(slot, ussdCode);
        }
    }

    class BatteryRequestReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            Intent status = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            try {
                int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int percent = 100*level/scale;
                boolean isPlugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0;
                transmitOnAllSlots("fleet battery " + percent + " " + (isPlugged ? "plugged" : "unplugged"));
            } catch (NullPointerException e) {
                transmitOnAllSlots("fleet battery unknown");
            }
        }
    }

    class NmeaListener implements GpsStatus.NmeaListener {
        public void onNmeaReceived(long timestamp, String nmeaMessage) {
            String[] fields = ("" + nmeaMessage).split(",");
            String type = fields[0];
            if (u.getBooleanPref(Prefs.SIMULATE_GPS_OUTAGE)) {
                if (type.endsWith("GSA") || type.endsWith("GGA")) {
                    Utils.log(TAG, "Simulating GPS outage");
                    mLocationAdapter.onGpsSignalLost();
                }
            } else if (fields.length > 6) {
                if (type.endsWith("GSA") && fields[2].equals("1") ||  // 3D fix type, 1 = "no fix"
                    type.endsWith("GGA") && fields[6].equals("0")) {  // fix quality, 0 = "invalid"
                    Log.i(TAG, "NMEA sentence indicates no fix: " + nmeaMessage);
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
