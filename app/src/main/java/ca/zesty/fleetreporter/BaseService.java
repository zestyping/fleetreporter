package ca.zesty.fleetreporter;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.Nullable;

/** Base class for Fleet Reporter and Fleet Receiver services. */
public class BaseService extends Service {
    Utils u = new Utils(this);
    @Nullable @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
