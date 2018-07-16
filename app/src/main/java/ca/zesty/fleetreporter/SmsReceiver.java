package ca.zesty.fleetreporter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Receives incoming SMS messages from Fleet Receiver instances. */
public class SmsReceiver extends BroadcastReceiver {
    static final String TAG = "SmsReceiver";
    static final Pattern PATTERN_REQPOINT = Pattern.compile("^fleet reqpoint");
    static final String ACTION_POINT_REQUESTED = "FLEET_RECEIVER_POINT_REQUESTED";
    static final Pattern PATTERN_ASSIGN = Pattern.compile("^fleet assign ([0-9a-zA-Z]+) +(.*)");
    static final String ACTION_REPORTER_ASSIGNED = "FLEET_RECEIVER_REPORTER_ASSIGNED";
    static final String EXTRA_RECEIVER_NUMBER = "receiver_number";
    static final String EXTRA_REPORTER_ID = "reporter_id";
    static final String EXTRA_REPORTER_LABEL = "reporter_label";

    @Override public void onReceive(Context context, Intent intent) {
        SmsMessage sms = Utils.getSmsFromIntent(intent);
        if (sms == null) return;

        String sender = sms.getDisplayOriginatingAddress();
        String body = sms.getMessageBody().trim();
        Log.i(TAG, "Received SMS from " + sender + ": " + body);

        if (PATTERN_REQPOINT.matcher(body).find()) {
            context.sendBroadcast(new Intent(ACTION_POINT_REQUESTED));
        }

        Matcher matcher = PATTERN_ASSIGN.matcher(body);
        if (matcher.matches()) {
            context.sendBroadcast(new Intent(ACTION_REPORTER_ASSIGNED)
                .putExtra(EXTRA_RECEIVER_NUMBER, sender)
                .putExtra(EXTRA_REPORTER_ID, matcher.group(1))
                .putExtra(EXTRA_REPORTER_LABEL, matcher.group(2))
            );
        }
    }
}
