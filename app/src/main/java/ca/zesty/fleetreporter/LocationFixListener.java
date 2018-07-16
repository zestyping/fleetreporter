package ca.zesty.fleetreporter;

/** An interface for things that receive LocationFixes. */
public interface LocationFixListener {
    /** Receives a LocationFix, or null to mean that the location is unknown. */
    void onFix(LocationFix fix);
}
