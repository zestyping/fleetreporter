package ca.zesty.fleetreporter;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.ActivityCompat;
import android.telephony.SmsMessage;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private SmsReceiver mSmsReceiver = new SmsReceiver();
    private String mLastDestinationNumber = "";
    private Handler mHandler = null;
    private Runnable mRunnable = null;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle("Fleet Reporter " + BuildConfig.VERSION_NAME);

        ActivityCompat.requestPermissions(this, new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.INTERNET,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.WAKE_LOCK
        }, 0);

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
        registerReceiver(mSmsReceiver, new IntentFilter(ACTION_SMS_RECEIVED));

        // Some elements of the display show elapsed time, so we need to
        // periodically update the display even if there are no new events.
        mHandler = new Handler();
        mRunnable = new Runnable() {
            public void run() {
                updateUiMode();
                updateReportingFrame();
                mHandler.postDelayed(mRunnable, DISPLAY_INTERVAL_MILLIS);
            }
        };

        bindService(new Intent(this, LocationService.class), mServiceConnection, BIND_AUTO_CREATE);
        if (u.getIntPref(Prefs.PAUSED, 0) == 0) {
            startLocationService();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        mHandler.postDelayed(mRunnable, 0);
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
        unregisterReceiver(mSmsReceiver);
        super.onDestroy();
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_register) {
            registerReporter();
        }
        if (item.getItemId() == R.id.action_pause) {
            stopLocationService();
        }
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        }
        return false;
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
                u.setText(R.id.mode_label, "Reporting as:");
                u.showFrameChild(R.id.reporting_frame);
            } else {
                u.setText(R.id.mode_label, "Reporting is paused!");
                u.showFrameChild(R.id.unpause_button);
            }
        } else {
            u.setText(R.id.mode_label, "Not yet registered");
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
            u.setText(R.id.speed, "no GPS", 0xffe04020);
            u.setText(R.id.speed_details, noGpsMillis == null ? "\n" : "no signal since\n" + Utils.describeTime(noGpsMillis));
        } else {
            u.setText(R.id.speed, Utils.format("%.0f km/h", fix.speedKmh), 0xff00a020);
            if (segmentMillis != null && segmentMillis >= 60 * 1000) {
                String segmentPeriod = Utils.describePeriod(segmentMillis);
                u.setText(R.id.speed_details, s.isResting() ?
                    "stopped here for\n" + segmentPeriod :
                    distance + " in " + segmentPeriod + "\nsince last stop"
                );
            } else {
                u.setText(R.id.speed_details, s.isResting() ?
                    "stopped\n" : "travelled " + distance + "\nsince last stop");
            }
        }

        Long smsFailMillis = s.getSmsFailingSinceMillis();
        Long smsSentMillis = s.getLastSmsSentMillis();
        if (smsFailMillis != null) {
            u.setText(R.id.sms, "no SMS", 0xffe04020);
            u.setText(R.id.sms_details,
                smsSentMillis != null ?
                "sent last report\n" + Utils.describeTime(smsSentMillis) :
                "unable to send since\n" + Utils.describeTime(smsFailMillis));
        } else if (smsSentMillis != null) {
            u.setText(R.id.sms, "\u2714", 0xff00a020);
            u.setText(R.id.sms_details, "sent last report\n" + Utils.describeTime(smsSentMillis));
        } else {
            u.setText(R.id.sms, "no SMS", 0xff808080);
            u.setText(R.id.sms_details, "nothing sent yet\n");
        }

        Intent status = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        try {
            int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int percent = 100 * level / scale;
            boolean isPlugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0;
            u.setText(R.id.battery, percent + "% battery", isPlugged ? 0xff00a020 : 0xffe04020);
            u.setText(R.id.battery_details, isPlugged ? "power is connected" : "no power source ");
        } catch (NullPointerException e) {
            u.setText(R.id.battery, "battery state unknown", 0xffe04020);
            u.setText(R.id.battery_details, "x");
        }
    }

    private void registerReporter() {
        String destinationNumber = u.getPref(Prefs.DESTINATION_NUMBER).trim();
        if (destinationNumber.isEmpty()) destinationNumber = mLastDestinationNumber;
        if (destinationNumber.isEmpty()) destinationNumber = "+";
        u.promptForString(
            "Registration",
            "Receiver's mobile number:",
            destinationNumber,
            new Utils.StringCallback() {
                public void run(String mobileNumber) {
                    if (mobileNumber == null) return;
                    mLastDestinationNumber = mobileNumber;
                    u.sendSms(mobileNumber, "fleet register");
                }
            }
        );
    }

    private void startLocationService() {
        u.setPref(Prefs.PAUSED, "0");
        bindService(new Intent(this, LocationService.class), mServiceConnection, BIND_AUTO_CREATE);
        startService(new Intent(this, LocationService.class));
    }

    private void stopLocationService() {
        u.setPref(Prefs.PAUSED, "1");
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
            Utils.formatUtcTimeSeconds(System.currentTimeMillis()) + " - " + message
        ));
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

    class SmsReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            final Pattern PATTERN_ASSIGN = Pattern.compile(
                "^fleet assign ([0-9a-zA-Z]+) +(.*)");

            SmsMessage sms = Utils.getSmsFromIntent(intent);
            if (sms == null) return;

            String sender = sms.getDisplayOriginatingAddress();
            String body = sms.getMessageBody();

            Matcher matcher = PATTERN_ASSIGN.matcher(body);
            if (matcher.matches()) {
                abortBroadcast();
                assignReporter(sender, matcher.group(1), matcher.group(2));
            }
        }

        private void assignReporter(String receiverNumber, String reporterId, String label) {
            u.setPref(Prefs.DESTINATION_NUMBER, receiverNumber);
            u.setPref(Prefs.REPORTER_ID, reporterId);
            u.setPref(Prefs.REPORTER_LABEL, label);
            u.sendSms(receiverNumber, "fleet activate " + reporterId);
            startLocationService();
        }
    }
}
