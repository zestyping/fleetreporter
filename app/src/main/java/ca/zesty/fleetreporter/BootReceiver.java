package ca.zesty.fleetreporter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the LocationService if necessary after a reboot. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (new Utils(context).getIntPref(Prefs.RUNNING, 0) == 1) {
            context.startService(new Intent(context, LocationService.class));
        }
    }
}
