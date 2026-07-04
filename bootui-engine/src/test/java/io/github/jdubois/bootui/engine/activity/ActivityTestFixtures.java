package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;

/** Shared fixture builders for the activity-package test suite. */
final class ActivityTestFixtures {

    private ActivityTestFixtures() {}

    static ActivityEntryDto entry(String id, String type, long timestamp, String severity, String summary) {
        return new ActivityEntryDto(
                id, type, timestamp, severity, summary, null, null, null, null, null, null, null, false, null, null,
                false);
    }

    static ActivityEntryDto entry(
            String id, String type, long timestamp, String severity, String summary, String detail) {
        return new ActivityEntryDto(
                id, type, timestamp, severity, summary, detail, null, null, null, null, null, null, false, null, null,
                false);
    }

    /** Like {@link #entry(String, String, long, String, String)} but with an explicit {@code correlationId}. */
    static ActivityEntryDto entryWithCorrelation(
            String id, String type, long timestamp, String severity, String summary, String correlationId) {
        return new ActivityEntryDto(
                id,
                type,
                timestamp,
                severity,
                summary,
                null,
                null,
                correlationId,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                false);
    }

    static StoredActivityEntry stored(String instanceId, long seq, ActivityEntryDto entry) {
        return new StoredActivityEntry(instanceId, seq, entry);
    }
}
