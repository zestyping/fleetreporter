package ca.zesty.fleetreporter;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.View;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
    }
}
