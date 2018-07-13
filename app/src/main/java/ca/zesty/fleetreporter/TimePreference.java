package ca.zesty.fleetreporter;

import android.content.Context;
import android.content.res.TypedArray;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TimePicker;

public class TimePreference extends DialogPreference {
    private int lastHour = 0;
    private int lastMinute = 0;
    private TimePicker picker = null;

    public TimePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPositiveButtonText("Set");
        setNegativeButtonText("Cancel");
    }

    @Override protected View onCreateDialogView() {
        picker = new TimePicker(getContext());
        picker.setIs24HourView(true);
        return picker;
    }

    @Override protected void onBindDialogView(View v) {
        super.onBindDialogView(v);
        picker.setCurrentHour(lastHour);
        picker.setCurrentMinute(lastMinute);
    }

    @Override protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        if (positiveResult) {
            lastHour = picker.getCurrentHour();
            lastMinute = picker.getCurrentMinute();
            String hourMinute = Utils.format("%02d:%02d", lastHour, lastMinute);
            if (callChangeListener(hourMinute)) {
                persistString(hourMinute);
            }
        }
    }

    @Override protected Object onGetDefaultValue(TypedArray array, int index) {
        return array.getString(index);
    }

    @Override protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
        String hourMinute = "" + defaultValue;
        if (restoreValue) {
            hourMinute = getPersistedString(defaultValue != null ? "" + defaultValue : "00:00");
        }
        String[] parts = hourMinute.split(":");
        lastHour = Integer.parseInt(parts[0]);
        lastMinute = Integer.parseInt(parts[1]);
    }
}
