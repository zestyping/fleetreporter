package ca.zesty.fleetreporter;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

public class MainActivity extends BaseActivity {
    static final String TAG = "MainActivity";
    static final String ACTION_SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";
    static final long DISPLAY_INTERVAL_MILLIS = 5 * 1000;
    public static final String ACTION_LOG_MESSAGE = "FLEET_REPORTER_LOG_MESSAGE";
    public static final String EXTRA_LOG_MESSAGE = "LOG_MESSAGE";

    private ServiceConnection mServiceConnection = new LocationServiceConnection();
    private LocationService mLocationService = null;
    private PointReceiver mPointReceiver = new PointReceiver();
    private LogMessageReceiver mLogMessageReceiver = new LogMessageReceiver();
    private ServiceChangedReceiver mServiceChangedReceiver = new ServiceChangedReceiver();
    private AssignmentReceiver mAssignmentReceiver = new AssignmentReceiver();
    private String mLastDestinationNumber = "";
    private AlertDialog mAccessibilityServicePrompt = null;
    private int mLastAccessibilityServiceCheckMinutes = Utils.getLocalMinutesSinceMidnight();
    private Handler mHandler = null;
    private Runnable mRunnable = null;
    private SmsHistoryUploader mSmsUploader = null;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Utils.log(TAG, "onCreate");
        Utils.initializeCrashlytics(this);
        setContentView(R.layout.activity_main);
        setTitle(u.str(R.string.app_name) + " " + BuildConfig.VERSION_NAME);

        ActivityCompat.requestPermissions(this, new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.INTERNET,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, 0);

        populateReporterId();
        promptUserToEnableAccessibilityService();

        findViewById(R.id.register_button).setOnClickListener(
            new View.OnClickListener() {
                @Override public void onClick(View v) {
                    registerReporter();
                }
            }
        );

        findViewById(R.id.unpause_button).setOnClickListener(
            new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startLocationService();
                }
            }
        );

        registerReceiver(mLogMessageReceiver, new IntentFilter(ACTION_LOG_MESSAGE));
        registerReceiver(mPointReceiver, new IntentFilter(LocationService.ACTION_POINT_RECEIVED));
        registerReceiver(mServiceChangedReceiver, new IntentFilter(LocationService.ACTION_SERVICE_CHANGED));
        registerReceiver(mAssignmentReceiver, new IntentFilter(SmsReceiver.ACTION_REPORTER_ASSIGNED));

        mSmsUploader = new SmsHistoryUploader(
            u.getPref(Prefs.REPORTER_ID) + "/" + u.getPref(Prefs.REPORTER_LABEL),
            u, getContentResolver());

        // Some elements of the display show elapsed time, so we need to
        // periodically update the display even if there are no new events.
        mHandler = new Handler();
        mRunnable = new Runnable() {
            public void run() {
                updateUiMode();
                updateReportingFrame();
                checkWhetherToPromptUserToEnableAccessibilityService();
                mSmsUploader.start();
                mHandler.postDelayed(mRunnable, DISPLAY_INTERVAL_MILLIS);
            }
        };

        if (u.getBooleanPref(Prefs.PLAY_STORE_REQUESTED)) {
            u.setPref(Prefs.RUNNING, false);
            u.setPref(Prefs.PLAY_STORE_REQUESTED, false);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                "market://details?id=ca.zesty.fleetreporter")));
        } else {
            bindService(new Intent(this, LocationService.class), mServiceConnection, BIND_AUTO_CREATE);
            if (u.getBooleanPref(Prefs.RUNNING)) {
                startLocationService();
            }
        }
    }

    @Override protected void onStart() {
        super.onStart();
        mSmsUploader.sendPrefs();
    }

    @Override protected void onResume() {
        super.onResume();
        mHandler.postDelayed(mRunnable, 0);
        u.show(R.id.message_log, u.getBooleanPref(Prefs.SHOW_LOG));
    }

    @Override protected void onPause() {
        mHandler.removeCallbacks(mRunnable);
        super.onPause();
    }

    @Override protected void onDestroy() {
        try {
            unbindService(mServiceConnection);
        } catch (IllegalArgumentException e) {
            // Ignore the error we get when there was nothing to unbind.
        }
        unregisterReceiver(mLogMessageReceiver);
        unregisterReceiver(mPointReceiver);
        unregisterReceiver(mServiceChangedReceiver);
        unregisterReceiver(mAssignmentReceiver);
        super.onDestroy();
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu, menu);
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_register_additional_numbers).setEnabled(isRegistered());
        menu.findItem(R.id.action_send_diagnostics).setTitle(
            u.str(R.string.fmt_send_diagnostics_n_left, mSmsUploader.countRemaining())
        );
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_register) {
            registerReporter();
        }
        if (item.getItemId() == R.id.action_register_additional_numbers) {
            String destination = u.getPref(Prefs.DESTINATION_NUMBER);
            String reporterId = u.getPref(Prefs.REPORTER_ID);
            if (!destination.isEmpty() && !reporterId.isEmpty()) {
                String simList = "";
                for (int slot = 0; slot < u.getNumSimSlots(); slot++) {
                    u.sendSms(slot, destination, "fleet activate " + reporterId);
                    simList += "\n  \u2022 SIM " + (slot + 1) + ": " + u.getCarrierName(slot);
                }
                u.showMessageBox(
                    u.str(R.string.register_additional_numbers),
                    u.str(R.string.sent_registration_messages_to) + "\n" + simList
                );
            }
        }
        if (item.getItemId() == R.id.action_pause) {
            stopLocationService();
        }
        if (item.getItemId() == R.id.action_send_diagnostics) {
            u.showConfirmBox(u.str(R.string.send_diagnostics),
                u.str(R.string.ensure_internet_instructions),
                u.str(R.string.ready_to_proceed),
                new Utils.Callback() {
                    @Override public void run() {
                        u.relaunchApp();
                    }
                }
            );
        }
        if (item.getItemId() == R.id.action_update) {
            u.showConfirmBox(u.str(R.string.update_app),
                u.str(R.string.ensure_internet_instructions),
                u.str(R.string.ready_to_proceed),
                new Utils.Callback() {
                    @Override public void run() {
                        u.setPref(Prefs.PLAY_STORE_REQUESTED, true);
                        u.relaunchApp();
                    }
                }
            );
        }
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        }
        return false;
    }

    void populateReporterId() {
        if (u.getPref(Prefs.REPORTER_ID).isEmpty()) {
            u.setPref(Prefs.REPORTER_ID, Utils.generateReporterId());
        }
    }

    private boolean isRegistered() {
        return !u.getPref(Prefs.DESTINATION_NUMBER).isEmpty() &&
            !u.getPref(Prefs.REPORTER_ID).isEmpty() &&
            !u.getPref(Prefs.REPORTER_LABEL).isEmpty();
    }

    private void updateUiMode() {
        if (isRegistered()) {
            u.setText(R.id.reporter_label, u.getPref(Prefs.REPORTER_LABEL));
            if (LocationService.isRunning) {
                String sleepStart = u.getPref(Prefs.SLEEP_START);
                String sleepEnd = u.getPref(Prefs.SLEEP_END);
                if (Utils.isLocalTimeOfDayBetween(sleepStart, sleepEnd)) {
                    u.setText(R.id.sleep_mode_label, u.str(R.string.sleep_mode) + Utils.format("\n\n%s \u2013 %s", sleepStart, sleepEnd));
                    u.showFrameChild(R.id.sleep_mode_label);
                } else {
                    u.setText(R.id.mode_label, u.str(R.string.reporting_as_colon));
                    u.showFrameChild(R.id.reporting_frame);
                }
            } else {
                u.setText(R.id.mode_label, u.str(R.string.reporting_is_paused));
                u.showFrameChild(R.id.unpause_button);
            }
        } else {
            u.setText(R.id.mode_label, u.str(R.string.not_yet_registered));
            u.hide(R.id.reporter_label);
            u.showFrameChild(R.id.register_button);
        }
    }

    private void updateReportingFrame() {
        LocationService s = mLocationService;  // mLocationService field is volatile, save it
        if (s == null) return;

        // Note all TextViews are initialized in activity_main.xml and set here to
        // have a constant number of lines of text, so that their height stays fixed.

        Long noGpsMillis = s.getNoGpsSinceTimeMillis();
        LocationFix fix = s.getLastLocationFix();
        Long segmentMillis = s.getMillisSinceLastTransition();
        String distance = Utils.describeDistance(s.getMetersTravelledSinceStop());
        if (noGpsMillis != null || fix == null) {
            u.setText(R.id.speed, u.str(R.string.no_gps), 0xffe04020);
            u.setText(R.id.speed_details, noGpsMillis == null ? "\n" : u.str(R.string.no_gps_signal_since) + "\n" + u.describeTime(noGpsMillis));
        } else {
            u.setText(R.id.speed, Utils.format("%.0f km/h", fix.speedKmh), 0xff00a020);
            if (segmentMillis != null && segmentMillis >= 60 * 1000) {
                String segmentPeriod = u.describePeriod(segmentMillis);
                u.setText(R.id.speed_details, s.isResting() ?
                    u.str(R.string.stopped_here_for) + "\n" + segmentPeriod :
                    u.str(R.string.fmt_dist_in_dur, distance, segmentPeriod) + "\n" + u.str(R.string.since_last_stop)
                );
            } else {
                u.setText(R.id.speed_details, s.isResting() ?
                    u.str(R.string.stopped) + "\n" : u.str(R.string.fmt_travelled_dist, distance) + "\n" + u.str(R.string.since_last_stop));
            }
        }

        Long smsFailMillis = s.getSmsFailingSinceMillis();
        Long smsSentMillis = s.getLastSmsSentMillis();
        if (smsFailMillis != null) {
            u.setText(R.id.sms, u.str(R.string.no_sms), 0xffe04020);
            u.setText(R.id.sms_details,
                smsSentMillis != null ?
                u.str(R.string.sent_last_report) + "\n" + u.describeTime(smsSentMillis) :
                u.str(R.string.unable_to_send_since) + "\n" + u.describeTime(smsFailMillis));
        } else if (smsSentMillis != null) {
            u.setText(R.id.sms, "SMS \u2713", 0xff00a020);
            u.setText(R.id.sms_details, u.str(R.string.sent_last_report) + "\n" + u.describeTime(smsSentMillis));
        } else {
            u.setText(R.id.sms, u.str(R.string.no_sms), 0xff808080);
            u.setText(R.id.sms_details, u.str(R.string.nothing_to_send_yet) + "\n");
        }

        Intent status = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        try {
            int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int percent = 100 * level / scale;
            boolean isPlugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0;
            u.setText(R.id.battery, u.str(R.string.fmt_n_percent_battery, percent), isPlugged ? 0xff00a020 : 0xffe04020);
            u.setText(R.id.battery_details, isPlugged ? u.str(R.string.power_is_connected) : u.str(R.string.no_power_source));
        } catch (NullPointerException e) {
            u.setText(R.id.battery, u.str(R.string.battery_state_unknown), 0xffe04020);
            u.setText(R.id.battery_details, "");
        }
    }

    private void registerReporter() {
        String destinationNumber = u.getPref(Prefs.DESTINATION_NUMBER).trim();
        if (destinationNumber.isEmpty()) destinationNumber = mLastDestinationNumber;
        if (destinationNumber.isEmpty()) destinationNumber = "+236";
        u.promptForString(
            u.str(R.string.registration),
            u.str(R.string.receivers_mobile_number_colon),
            destinationNumber,
            new Utils.StringCallback() {
                public void run(String mobileNumber) {
                    if (mobileNumber == null) return;
                    mLastDestinationNumber = mobileNumber;
                    try {
                        for (int slot = 0; slot < u.getNumSimSlots(); slot++) {
                            u.sendSms(slot, mobileNumber, "fleet register");
                        }
                    } catch (IllegalArgumentException e) {
                        u.showMessageBox(u.str(R.string.error), u.str(R.string.invalid_number));
                    }
                }
            }
        );
    }

    private void startLocationService() {
        if (!isRegistered()) return;
        u.setPref(Prefs.RUNNING, true);
        bindService(new Intent(this, LocationService.class), mServiceConnection, BIND_AUTO_CREATE);
        startService(new Intent(this, LocationService.class));
    }

    private void stopLocationService() {
        u.setPref(Prefs.RUNNING, false);
        try {
            unbindService(mServiceConnection);
        } catch (IllegalArgumentException e) {
            // Ignore the error we get when there was nothing to unbind.
        }
        stopService(new Intent(this, LocationService.class));
    }

    /** Defines callbacks for service binding, passed to bindService() */
    class LocationServiceConnection implements ServiceConnection {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            mLocationService = (LocationService) ((BaseService.LocalBinder) binder).getService();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            mLocationService = null;
        }
    };

    public static void postLogMessage(Context context, String message) {
        context.sendBroadcast(new Intent(ACTION_LOG_MESSAGE).putExtra(EXTRA_LOG_MESSAGE,
            Utils.formatUtcTimeSeconds(Utils.getTime()) + " - " + message
        ));
    }

    void checkWhetherToPromptUserToEnableAccessibilityService() {
        int promptTimeMinutes = 12 * 60;
        int localMinutes = Utils.getLocalMinutesSinceMidnight();
        if (localMinutes != mLastAccessibilityServiceCheckMinutes &&
            localMinutes == promptTimeMinutes) {
            promptUserToEnableAccessibilityService();
        }
        mLastAccessibilityServiceCheckMinutes = localMinutes;
    }

    void promptUserToEnableAccessibilityService() {
        if (!u.isAccessibilityServiceEnabled(UssdReceiverService.class)) {
            if (mAccessibilityServicePrompt != null) {
                mAccessibilityServicePrompt.dismiss();
            }
            mAccessibilityServicePrompt = u.showConfirmBox(
                u.str(R.string.settings_change_needed),
                u.str(R.string.accessibility_service_instructions),
                u.str(R.string.open_settings_button),
                new Utils.Callback() {
                    @Override public void run() {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                }
            );
        }
    }

    class LogMessageReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(EXTRA_LOG_MESSAGE);
            ((TextView) findViewById(R.id.message_log)).append(message + "\n");
        }
    }

    class PointReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            updateReportingFrame();
        }
    }

    class ServiceChangedReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (LocationService.isRunning) {
                Intent service = new Intent(MainActivity.this, LocationService.class);
                bindService(service, mServiceConnection, BIND_AUTO_CREATE);
            }
            updateUiMode();
            updateReportingFrame();
        }
    }

    class AssignmentReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String receiverNumber = intent.getStringExtra(SmsReceiver.EXTRA_SENDER);
            String reporterId = intent.getStringExtra(SmsReceiver.EXTRA_REPORTER_ID);
            String label = intent.getStringExtra(SmsReceiver.EXTRA_REPORTER_LABEL);
            u.setPref(Prefs.DESTINATION_NUMBER, receiverNumber);
            u.setPref(Prefs.REPORTER_ID, reporterId);
            u.setPref(Prefs.REPORTER_LABEL, label);
            u.sendSms(0, receiverNumber, "fleet activate " + reporterId);
            if (u.getSmsManager(1) != null) {
                u.sendSms(1, receiverNumber, "fleet activate " + reporterId);
            }
            startLocationService();
        }
    }
}
