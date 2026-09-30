package io.github.jdubois.bootui.engine.journal;

/**
 * A SQL statement's payload: its fingerprint (the normalized statement, without literals), the application call site,
 * the datasource, and whether it failed.
 */
public record SqlPayload(String fingerprint, String callSite, String dataSource, boolean failed)
        implements RuntimeEventPayload {

    @Override
    public int estimatedBytes() {
        return 16
                + RuntimeEvent.stringBytes(fingerprint)
                + RuntimeEvent.stringBytes(callSite)
                + RuntimeEvent.stringBytes(dataSource);
    }
}
