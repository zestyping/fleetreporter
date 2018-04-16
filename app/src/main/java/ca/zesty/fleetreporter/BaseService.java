package ca.zesty.fleetreporter;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.annotation.Nullable;

/** Base class for Fleet Reporter and Fleet Receiver services. */
public class BaseService extends Service {
    Utils u = new Utils(this);
    private final IBinder mBinder = new LocalBinder();

    @Nullable @Override public IBinder onBind(Intent intent) {
        return mBinder;
    }

    class LocalBinder extends Binder {
        BaseService getService() {
            return BaseService.this;
        }
    }
}
