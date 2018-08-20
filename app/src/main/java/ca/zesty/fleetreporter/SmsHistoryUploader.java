package ca.zesty.fleetreporter;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Telephony;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SmsHistoryUploader {
    public static final String TAG = "SmsHistoryUploader";
    public static final Uri SMS_PROVIDER =  Uri.parse("content://sms");
    public static final HttpUrl UPLOAD_URL = HttpUrl.parse("http://zesty.ca/fleet/sms");
    public static final long SECOND = 1000;
    public static final long DAY = 24 * 3600 * 1000;
    public static final long MAX_AGE_TO_SEND = 100 * DAY;
    public static final int MAX_NUMBER_TO_SEND = 2000;
    public static final int BATCH_SIZE = 10;
    public static final long DELAY_BETWEEN_BATCHES_MILLIS = SECOND;
    public static final long LOOP_EXPIRED_MILLIS = 20 * SECOND;
    protected String deviceId;
    protected Utils u;
    protected ContentResolver resolver;
    protected OkHttpClient client;
    protected Handler handler;
    protected long lastConsideredSendingMillis = 0;

    public SmsHistoryUploader(String deviceId, Utils utils, ContentResolver resolver) {
        this.deviceId = deviceId;
        u = utils;
        this.resolver = resolver;
        client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
        client.dispatcher().setMaxRequests(1);

        HandlerThread thread = new HandlerThread("SmsHistoryUploader");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public long getLastUploadTimestamp() {
        return u.getLongPref(Prefs.SMS_HISTORY_UPLOAD_TIMESTAMP, System.currentTimeMillis() - MAX_AGE_TO_SEND);
    }

    public int countRemaining() {
        long lastDate = getLastUploadTimestamp();
        long now = System.currentTimeMillis();
        Cursor cursor = resolver.query(
            SMS_PROVIDER,
            new String[] {"count(*) as count"},
            "date > ? and date < ?",
            new String[] {"" + lastDate, "" + (now - 20 * 1000)},
            null
        );
        try {
            cursor.moveToFirst();
            return cursor.getInt(0);
        } finally {
            cursor.close();
        }
    }

    public void start() {
        if (client.dispatcher().runningCallsCount() + client.dispatcher().queuedCallsCount() > 0) {
            Log.w(TAG, "start: Requests are still pending; not starting a new loop");
            return;
        }
        if (lastConsideredSendingMillis > System.currentTimeMillis() - LOOP_EXPIRED_MILLIS) {
            Log.w(TAG, "start: Loop ran recently; not starting a new loop");
            return;
        }
        Log.i(TAG, "start: Starting a new loop");
        continueUploading();
    }

    public void uploadNextBatch() {
        if (client.dispatcher().runningCallsCount() + client.dispatcher().queuedCallsCount() > 0) {
            Log.w(TAG, "uploadNextBatch: Requests are still pending; doing nothing");
            return;
        }

        long lastDate = getLastUploadTimestamp();
        long now = System.currentTimeMillis();
        lastConsideredSendingMillis = now;

        if (countRemaining() > MAX_NUMBER_TO_SEND) {
            lastDate += DAY;
            Log.i(TAG, "uploadNextBatch: Too many messages to send; advancing timestamp to " + lastDate);
            u.setPref(Prefs.SMS_HISTORY_UPLOAD_TIMESTAMP, "" + lastDate);
            continueUploading();
            return;
        }

        Cursor cursor = resolver.query(
            SMS_PROVIDER,
            new String[] {
                "date",
                "address",
                "type",
                "body",
                "status",
                "error_code",
                "thread_id",
                "sub_id"
            },
            "date > ? and date < ?",
            new String[] {"" + lastDate, "" + (now - 20 * 1000)},
            "date limit " + BATCH_SIZE
        );
        try {
            String dataToSend = "";
            while (cursor.moveToNext()) {
                String record = formatSmsRecord(cursor);
                Log.i(TAG, "Retrieved: " + record);
                lastDate = getLong(cursor, "date");
                dataToSend += record + "\n";
            }
            if (!dataToSend.isEmpty()) sendRequest(dataToSend, lastDate);
        } finally {
            cursor.close();
        }
    }

    public void sendPrefs() {
        String data = "";
        for (String key : Prefs.KEYS) {
            data += key + ": " + u.getStringPref(key) + "\n";
        }
        sendRequest(data, getLastUploadTimestamp());
    }

    protected String formatSmsRecord(Cursor cursor) {
        long date = getLong(cursor, "date");
        String address = getString(cursor, "address");
        int type = getInt(cursor, "type");
        String body = getString(cursor, "body");
        int status = getInt(cursor, "status");
        int errorCode = getInt(cursor, "error_code");
        int threadId = getInt(cursor, "thread_id");
        String subId = getString(cursor, "sub_id");

        return Utils.format(
            "%s,%s,%s,type=%s,%s,status=%s,error=%s,thread=%d,slot=%d",
            deviceId,
            Utils.formatUtcTimeMillis(date),
            formatMobileNumber(address),
            formatType(type),
            Utils.quoteString(body),
            formatStatus(status),
            errorCode,
            threadId,
            u.getSlotWithImsi(subId)
        );
    }

    protected String formatMobileNumber(String address) {
        return address.replaceAll("[^+0-9]", "");
    }

    protected String formatType(int type) {
        switch (type) {
            case Telephony.TextBasedSmsColumns.MESSAGE_TYPE_INBOX:
                return "inbox";
            case Telephony.TextBasedSmsColumns.MESSAGE_TYPE_SENT:
                return "sent";
            case Telephony.TextBasedSmsColumns.MESSAGE_TYPE_DRAFT:
                return "draft";
            case Telephony.TextBasedSmsColumns.MESSAGE_TYPE_OUTBOX:
                return "outbox";
            case Telephony.TextBasedSmsColumns.MESSAGE_TYPE_FAILED:
                return "failed";
            case Telephony.TextBasedSmsColumns.MESSAGE_TYPE_QUEUED:
                return "queued";
        }
        return "" + type;
    }

    protected String formatStatus(int status) {
        switch (status) {
            case Telephony.TextBasedSmsColumns.STATUS_COMPLETE:
                return "complete";
            case Telephony.TextBasedSmsColumns.STATUS_FAILED:
                return "failed";
            case Telephony.TextBasedSmsColumns.STATUS_PENDING:
                return "pending";
            case Telephony.TextBasedSmsColumns.STATUS_NONE:
                return "none";
        }
        return "" + status;
    }

    protected String getString(Cursor cursor, String columnName) {
        return cursor.getString(cursor.getColumnIndex(columnName));
    }

    protected int getInt(Cursor cursor, String columnName) {
        return cursor.getInt(cursor.getColumnIndex(columnName));
    }

    protected long getLong(Cursor cursor, String columnName) {
        return cursor.getLong(cursor.getColumnIndex(columnName));
    }

    protected void sendRequest(String data, final long maxDate) {
        Log.i(TAG, "Sending: " + data);
        Request request = new Request.Builder()
            .url(UPLOAD_URL.newBuilder().addQueryParameter("data", data).build())
            .build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "sendRequest: Last request failed");
                continueUploading();
            }

            @Override public void onResponse(Call call, Response response) {
                int code = response.code();
                if (code == 200 || code == 404) {
                    Log.i(TAG, "sendRequest: Last request returned " + code + ", updating last upload timestamp to " + maxDate);
                    u.setPref(Prefs.SMS_HISTORY_UPLOAD_TIMESTAMP, "" + maxDate);
                } else {
                    Log.i(TAG, "sendRequest: Last request failed with code " + code);
                }
                continueUploading();
            }
        });
    }

    protected void continueUploading() {
        if (client.dispatcher().queuedCallsCount() == 0) {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    uploadNextBatch();
                }
            }, DELAY_BETWEEN_BATCHES_MILLIS);
        }
    }
}
