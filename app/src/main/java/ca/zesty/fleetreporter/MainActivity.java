package ca.zesty.fleetreporter;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    static final String TAG = "MainActivity";
    static final String ACTION_SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";
    public static final String ACTION_FLEET_REPORTER_LOG_MESSAGE = "FLEET_REPORTER_LOG_MESSAGE";
    public static final String EXTRA_LOG_MESSAGE = "LOG_MESSAGE";

    private LogMessageReceiver mLogMessageReceiver = new LogMessageReceiver();
    private SmsAssignReceiver mSmsAssignReceiver = new SmsAssignReceiver();

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

        final Intent serviceIntent = new Intent(getApplicationContext(), LocationService.class);

        findViewById(R.id.register_button).setOnClickListener(
            new View.OnClickListener() {
                @Override public void onClick(View v) {
                    registerReporter();
                }
            }
        );

        findViewById(R.id.start_button).setOnClickListener(
            new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (Prefs.getDestinationNumber(MainActivity.this).isEmpty()) {
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("No destination number")
                            .setMessage("Please enter a destination number in the Settings.")
                            .setPositiveButton("OK", null)
                            .show();
                    } else {
                        startService(serviceIntent);
                    }
                }
            }
        );

        findViewById(R.id.stop_button).setOnClickListener(
            new View.OnClickListener() {
                @Override public void onClick(View v) {
                    stopService(serviceIntent);
                }
            }
        );

        registerReceiver(mLogMessageReceiver, new IntentFilter(ACTION_FLEET_REPORTER_LOG_MESSAGE));
        registerReceiver(mSmsAssignReceiver, new IntentFilter(ACTION_SMS_RECEIVED));
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
        switch (item.getItemId()) {
            case R.id.action_settings: {
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            }
        }
        return false;
    }

    private void registerReporter() {
        final EditText numberView = new EditText(MainActivity.this);
        numberView.setHint("A mobile number starting with +");
        new android.app.AlertDialog.Builder(MainActivity.this)
            .setTitle("Registration")
            .setMessage("Fleet Receiver's mobile number:")
            .setView(numberView)
            .setPositiveButton("Register", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    String destination = numberView.getText().toString();
                    getSmsManager().sendTextMessage(destination, null, "fleet register", null, null);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();

    }

    /** Gets the appropriate SmsManager to use for sending text messages.
     From PataBasi by Kristen Tonga. */
    private SmsManager getSmsManager() {
        if (android.os.Build.VERSION.SDK_INT >= 22) {
            int subscriptionId = SmsManager.getDefaultSmsSubscriptionId();
            if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {  // dual-SIM phone
                SubscriptionManager subscriptionManager = SubscriptionManager.from(getApplicationContext());
                subscriptionId = subscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(0).getSubscriptionId();
                Log.d(TAG, "Dual SIM phone; selected subscriptionId: " + subscriptionId);
            }
            return SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
        }
        return SmsManager.getDefault();
    }

    class LogMessageReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra(EXTRA_LOG_MESSAGE)) {
                String message = intent.getStringExtra(EXTRA_LOG_MESSAGE);
                ((TextView) findViewById(R.id.message_log)).append(message + "\n");
            }
        }
    }

    /** Handles incoming SMS messages for reported locations. */
    class SmsAssignReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            final Pattern PATTERN_ASSIGN = Pattern.compile("^fleet assign ([0-9a-zA-Z]+) +(.*)");

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
            Prefs.setDestinationNumber(MainActivity.this, receiverNumber);
            Prefs.setReporterId(MainActivity.this, reporterId);
            Prefs.setReporterLabel(MainActivity.this, label);
            getSmsManager().sendTextMessage(receiverNumber, null, "fleet activate " + reporterId, null, null);
            String message = "Reporter assigned: \"" + label + "\" (id: " + reporterId + ")";
            ((TextView) findViewById(R.id.message_log)).append(message + "\n");
            ((TextView) findViewById(R.id.reporter_label)).setText(label);
        }
    }
}
