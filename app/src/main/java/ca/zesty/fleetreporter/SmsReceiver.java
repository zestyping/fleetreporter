package ca.zesty.fleetreporter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsMessage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Receives incoming SMS messages from Fleet Receiver instances. */
public class SmsReceiver extends BroadcastReceiver {
    static final String TAG = "SmsReceiver";
    static final Pattern PATTERN_REQPOINT = Pattern.compile("^fleet reqpoint");
    static final String ACTION_POINT_REQUEST = "FLEET_REPORTER_POINT_REQUEST";
    static final Pattern PATTERN_ASSIGN = Pattern.compile("^fleet assign ([0-9a-zA-Z]+) +(.*)");
    static final String ACTION_REPORTER_ASSIGNED = "FLEET_REPORTER_REPORTER_ASSIGNED";
    static final String EXTRA_SENDER = "sender";
    static final String EXTRA_REPORTER_ID = "reporter_id";
    static final String EXTRA_REPORTER_LABEL = "reporter_label";
    static final Pattern PATTERN_CREDIT = Pattern.compile("Votre credit est de +(\\d+)");
    static final String ACTION_CREDIT = "FLEET_REPORTER_CREDIT";
    static final String EXTRA_AMOUNT = "amount";
    static final Pattern PATTERN_LOW_CREDIT = Pattern.compile("Votre credit est seulement de +(\\d+)");
    static final String ACTION_LOW_CREDIT = "FLEET_REPORTER_LOW_CREDIT";
    static final Pattern PATTERN_USSD = Pattern.compile("^fleet ussd +(\\d+) +(.*)");
    static final String ACTION_USSD_REQUEST = "FLEET_REPORTER_USSD_REQUEST";
    static final String EXTRA_SLOT = "slot";
    static final String EXTRA_USSD_CODE = "ussd_code";
    static final Pattern PATTERN_BATTERY_REQUEST = Pattern.compile("^fleet reqbattery");
    static final String ACTION_BATTERY_REQUEST = "FLEET_REPORTER_BATTERY_REQUEST";

    @Override public void onReceive(Context context, Intent intent) {
        SmsMessage sms = Utils.getSmsFromIntent(intent);
        if (sms == null) return;

        String sender = sms.getDisplayOriginatingAddress();
        String body = sms.getMessageBody().trim();
        Utils.log(TAG, "Received SMS from %s: %s", sender, body);

        if (PATTERN_REQPOINT.matcher(body).find()) {
            context.sendBroadcast(new Intent(ACTION_POINT_REQUEST));
        }

        Matcher matcher = PATTERN_ASSIGN.matcher(body);
        if (matcher.find()) {
            context.sendBroadcast(new Intent(ACTION_REPORTER_ASSIGNED)
                .putExtra(EXTRA_SENDER, sender)
                .putExtra(EXTRA_REPORTER_ID, matcher.group(1))
                .putExtra(EXTRA_REPORTER_LABEL, matcher.group(2))
            );
        }

        matcher = PATTERN_LOW_CREDIT.matcher(body);
        if (matcher.find()) {
            Utils.logRemote(TAG, "SMS received: " + body);
            context.sendBroadcast(new Intent(ACTION_LOW_CREDIT)
                .putExtra(EXTRA_SENDER, sender)
                .putExtra(EXTRA_SLOT, 0)
                .putExtra(EXTRA_AMOUNT, matcher.group(1))
            );
        }

        matcher = PATTERN_CREDIT.matcher(body);
        if (matcher.find()) {
            Utils.logRemote(TAG, "SMS received: " + body);
            context.sendBroadcast(new Intent(ACTION_CREDIT)
                .putExtra(EXTRA_SENDER, sender)
                .putExtra(EXTRA_AMOUNT, matcher.group(1))
            );
        }

        matcher = PATTERN_USSD.matcher(body);
        if (matcher.find()) {
            Utils.logRemote(TAG, "USSD request: " + body);
            context.sendBroadcast(new Intent(ACTION_USSD_REQUEST)
                .putExtra(EXTRA_SENDER, sender)
                .putExtra(EXTRA_SLOT, Integer.valueOf(matcher.group(1)))
                .putExtra(EXTRA_USSD_CODE, matcher.group(2).trim()));
        }

        matcher = PATTERN_BATTERY_REQUEST.matcher(body);
        if (matcher.find()) {
            Utils.logRemote(TAG, "Battery request: " + body);
            context.sendBroadcast(new Intent(ACTION_BATTERY_REQUEST));
        }
    }
}
