package ca.zesty.fleetreporter;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
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
    public static final String ACTION_FLEET_REPORTER_LOG_MESSAGE = "FLEET_REPORTER_LOG_MESSAGE";
    public static final String EXTRA_LOG_MESSAGE = "LOG_MESSAGE";

    private LogMessageReceiver mLogMessageReceiver = new LogMessageReceiver();
    private ServiceChangedReceiver mServiceChangedReceiver = new ServiceChangedReceiver();
    private SmsAssignReceiver mSmsAssignReceiver = new SmsAssignReceiver();
    private String mLastDestinationNumber = "";

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
                    startService(new Intent(u.context, LocationService.class));
                }
            }
        );

        registerReceiver(mLogMessageReceiver, new IntentFilter(ACTION_FLEET_REPORTER_LOG_MESSAGE));
        registerReceiver(mServiceChangedReceiver, new IntentFilter(LocationService.ACTION_FLEET_REPORTER_SERVICE_CHANGED));
        registerReceiver(mSmsAssignReceiver, new IntentFilter(ACTION_SMS_RECEIVED));
        updateUiMode();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(mLogMessageReceiver);
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
            stopService(new Intent(this, LocationService.class));
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
            if (LocationService.isRunning) {
                u.setText(R.id.mode_label, "Reporting as:");
                u.show(R.id.reporting_frame);
                u.hide(R.id.unpause_button);
            } else {
                u.setText(R.id.mode_label, "Reporting is paused!");
                u.hide(R.id.reporting_frame);
                u.show(R.id.unpause_button);
            }
            u.show(R.id.reporter_label);
            u.setText(R.id.reporter_label, u.getPref(Prefs.REPORTER_LABEL));
            u.hide(R.id.register_button);
        } else {
            u.setText(R.id.mode_label, "Not yet registered");
            u.hide(R.id.reporter_label);
            u.show(R.id.register_button);
            u.hide(R.id.reporting_frame);
            u.hide(R.id.unpause_button);
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

    class LogMessageReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra(EXTRA_LOG_MESSAGE)) {
                String message = intent.getStringExtra(EXTRA_LOG_MESSAGE);
                ((TextView) findViewById(R.id.message_log)).append(message + "\n");
            }
        }
    }

    class ServiceChangedReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            updateUiMode();
        }
    }

    /** Handles incoming SMS messages for reported locations. */
    class SmsAssignReceiver extends BroadcastReceiver {
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
            startService(new Intent(u.context, LocationService.class));
        }
    }
}
