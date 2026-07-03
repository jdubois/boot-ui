package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;

/** Shared fixture builders for the activity-package test suite. */
final class ActivityTestFixtures {

    private ActivityTestFixtures() {}

    static ActivityEntryDto entry(String id, String type, long timestamp, String severity, String summary) {
        return new ActivityEntryDto(
                id, type, timestamp, severity, summary, null, null, null, null, null, null, null, false, null, null);
    }

    static ActivityEntryDto entry(
            String id, String type, long timestamp, String severity, String summary, String detail) {
        return new ActivityEntryDto(
                id, type, timestamp, severity, summary, detail, null, null, null, null, null, null, false, null, null);
    }

    static StoredActivityEntry stored(String instanceId, long seq, ActivityEntryDto entry) {
        return new StoredActivityEntry(instanceId, seq, entry);
    }
}
