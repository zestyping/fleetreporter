package ca.zesty.fleetreporter;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class UssdDialogReaderService extends AccessibilityService {
    static final String TAG = "UssdDialogReaderService";
    static final String ACTION_USSD_RECEIVED = "FLEET_REPORTER_USSD_RECEIVED";
    static final String EXTRA_USSD_MESSAGE = "ussd_message";

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo source = event.getSource();
        String eventClass = String.valueOf(event.getClassName());
        String sourceClass = String.valueOf(source.getClassName());
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                if (sourceClass.endsWith(".TextView")) {
                    String text = "" + source.getText();
                    dismissDialog(source);
                    handleText(text);
                }
                break;
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                if (eventClass.endsWith(".AlertDialog") ||
                    eventClass.endsWith(".UssdAlertActivity")) {
                    String text = event.getText().isEmpty() ? "" : "" + event.getText().get(0);
                    dismissDialog(source);
                    handleText(text);
                }
                break;
        }
    }

    @Override public void onInterrupt() { }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.DEFAULT;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.packageNames = new String[] {"com.android.phone"};
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        setServiceInfo(info);
    }

    private void dismissDialog(AccessibilityNodeInfo node) {
        String nodeClass = String.valueOf(node.getClassName());
        if (nodeClass.endsWith(".FrameLayout")) {
            // The last two children are the "Cancel" and "Send" buttons.
            AccessibilityNodeInfo cancelButton = node.getChild(node.getChildCount() - 2);
            cancelButton.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } else {
            // These methods of closing the dialog don't work on Samsung, but
            // might work on other phones.
            performGlobalAction(GLOBAL_ACTION_BACK);
            sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        }
    }

    private void handleText(String text) {
        Log.i(TAG, "USSD reply: " + text);
        sendBroadcast(new Intent(ACTION_USSD_RECEIVED).putExtra(EXTRA_USSD_MESSAGE, text));
    }
}
