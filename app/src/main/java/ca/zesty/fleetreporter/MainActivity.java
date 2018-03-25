package ca.zesty.fleetreporter;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {
    public static final String ACTION_FLEET_REPORTER_LOG_MESSAGE = "FLEET_REPORTER_LOG_MESSAGE";
    public static final String EXTRA_LOG_MESSAGE = "LOG_MESSAGE";
    private LogMessageReceiver mLogMessageReceiver = new LogMessageReceiver();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActivityCompat.requestPermissions(this, new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.INTERNET,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.SEND_SMS,
            Manifest.permission.WAKE_LOCK
        }, 0);

        final Intent intent = new Intent(getApplicationContext(), LocationService.class);

        findViewById(R.id.start_button).setOnClickListener(
            new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startService(intent);
                }
            }
        );

        findViewById(R.id.stop_button).setOnClickListener(
            new View.OnClickListener() {
                @Override public void onClick(View v) {
                    stopService(intent);
                }
            }
        );

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_FLEET_REPORTER_LOG_MESSAGE);
        registerReceiver(mLogMessageReceiver, filter);
    }

    @Override protected void onDestroy() {
        unregisterReceiver(mLogMessageReceiver);
        super.onDestroy();
    }

    class LogMessageReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra(EXTRA_LOG_MESSAGE)) {
                String message = intent.getStringExtra(EXTRA_LOG_MESSAGE);
                ((TextView) findViewById(R.id.message_log)).append(message + "\n");
            }
        }
    }
}
