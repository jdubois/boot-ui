package io.github.jdubois.bootui.console.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;

/** Shared fixture builders for the console activity-package test suite, mirroring the engine's own
 * {@code ActivityTestFixtures} shape so the two suites read consistently. */
final class ConsoleActivityTestFixtures {

    private ConsoleActivityTestFixtures() {}

    static ActivityEntryDto entry(String id, String type, long timestamp, String severity, String summary) {
        return new ActivityEntryDto(
                id, type, timestamp, severity, summary, null, null, null, null, null, null, null, false, null, null,
                false);
    }

    static ActivityEntryDto requestEntry(
            String id, long timestamp, String summary, long durationMs, int status, String correlationId) {
        return new ActivityEntryDto(
                id,
                "REQUEST",
                timestamp,
                status >= 400 ? "ERROR" : "OK",
                summary,
                null,
                durationMs,
                correlationId,
                "GET",
                summary,
                status,
                "http-thread-1",
                true,
                null,
                null,
                false);
    }

    static ActivityEntryDto sqlEntry(String id, long timestamp, String summary, long durationMs, String correlationId) {
        return new ActivityEntryDto(
                id,
                "SQL",
                timestamp,
                "OK",
                summary,
                null,
                durationMs,
                correlationId,
                null,
                null,
                null,
                "db-thread-1",
                false,
                null,
                null,
                false);
    }

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
