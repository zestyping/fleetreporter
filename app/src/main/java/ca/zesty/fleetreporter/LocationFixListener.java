package ca.zesty.fleetreporter;

/** An interface for things that receive LocationFixes. */
public interface LocationFixListener {
    public void onLocationFix(LocationFix fix);
}
