package ca.zesty.fleetreporter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Restarts the LocationService if necessary after a reboot. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (new Utils(context).getBooleanPref(Prefs.RUNNING, false)) {
            if (Build.VERSION.SDK_INT >= 26) {
                // In API levels 26 and up, startService fails with "Not allowed
                // to start service ... app is in background".
                context.startForegroundService(new Intent(context, LocationService.class));
            } else {
                context.startService(new Intent(context, LocationService.class));
            }
        }
    }
}
