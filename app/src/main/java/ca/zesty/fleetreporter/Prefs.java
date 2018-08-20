package ca.zesty.fleetreporter;

/** Shared preference keys. */
public class Prefs {
    static final String DAILY_POINT_SMS_LIMIT = "pref_daily_point_sms_limit";
    static final String DAILY_RELAUNCH_TIME = "pref_daily_relaunch_time";
    static final String DESTINATION_NUMBER = "pref_destination_number";
    static final String PLAY_STORE_REQUESTED = "pref_play_store_requested";
    static final String POINT_SMS_COUNT = "pref_point_sms_count";
    static final String POINT_SMS_COUNT_LOCAL_DATE = "pref_point_sms_count_local_date";
    static final String RECORDING_INTERVAL_AFTER_GO = "pref_recording_interval_after_go";
    static final String RECORDING_INTERVAL_MOVING = "pref_recording_interval_while_moving";
    static final String RECORDING_INTERVAL_RESTING = "pref_recording_interval_while_resting";
    static final String REPORTING_INTERVAL_GPS_OUTAGE = "pref_reporting_interval_gps_outage";
    static final String REPORTER_ID = "pref_reporter_id";
    static final String REPORTER_LABEL = "pref_reporter_label";
    static final String RUNNING = "pref_running";
    static final String RESTING_RADIUS = "pref_resting_radius";
    static final String SETTLING_PERIOD = "pref_settling_period";
    static final String SHOW_LOG = "pref_show_log";
    static final String SIMULATE_GPS_OUTAGE = "pref_simulate_gps_outage";
    static final String SLEEP_START = "pref_sleep_start";
    static final String SLEEP_END = "pref_sleep_end";
    static final String SMS_HISTORY_UPLOAD_TIMESTAMP = "pref_sms_history_upload_timestamp";
    static final String SMS_PURCHASE_INTERVAL = "pref_sms_purchase_interval";
    static final String STABLE_MAX_ACCURACY = "pref_stable_max_accuracy";
    static final String STABLE_MAX_SPEED = "pref_stable_max_speed";

    static final String[] KEYS = new String[] {
        DAILY_POINT_SMS_LIMIT,
        DAILY_RELAUNCH_TIME,
        DESTINATION_NUMBER,
        PLAY_STORE_REQUESTED,
        POINT_SMS_COUNT,
        POINT_SMS_COUNT_LOCAL_DATE,
        RECORDING_INTERVAL_AFTER_GO,
        RECORDING_INTERVAL_MOVING,
        RECORDING_INTERVAL_RESTING,
        REPORTING_INTERVAL_GPS_OUTAGE,
        REPORTER_ID,
        REPORTER_LABEL,
        RUNNING,
        RESTING_RADIUS,
        SETTLING_PERIOD,
        SHOW_LOG,
        SIMULATE_GPS_OUTAGE,
        SLEEP_START,
        SLEEP_END,
        SMS_HISTORY_UPLOAD_TIMESTAMP,
        SMS_PURCHASE_INTERVAL,
        STABLE_MAX_ACCURACY,
        STABLE_MAX_SPEED,
    };
}
