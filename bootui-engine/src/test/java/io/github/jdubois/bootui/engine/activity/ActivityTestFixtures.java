package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.web.RequestSlowThreshold;
import io.github.jdubois.bootui.engine.web.ReservedActivityEntries;
import java.util.function.Predicate;

/** Shared fixture builders for the activity-package test suite. */
final class ActivityTestFixtures {

    /** The reserved-entry rule every adapter uses, at the default request slow threshold. */
    static final Predicate<ActivityEntryDto> RESERVED =
            new ReservedActivityEntries(RequestSlowThreshold.DEFAULT_MILLIS);

    private ActivityTestFixtures() {}

    /** A {@code REQUEST} entry carrying the status and duration its exchange buffer classified it by. */
    static ActivityEntryDto request(String id, long timestamp, String severity, int status, Long durationMs) {
        return new ActivityEntryDto(
                id,
                "REQUEST",
                timestamp,
                severity,
                "GET /orders",
                null,
                durationMs,
                null,
                "GET",
                "/orders",
                status,
                null,
                false,
                null,
                null,
                false);
    }

    /** A {@code REST_CLIENT} entry carrying the status and duration its REST client buffer classified it by. */
    static ActivityEntryDto restCall(String id, long timestamp, String severity, Integer status, Long durationMs) {
        return new ActivityEntryDto(
                id,
                "REST_CLIENT",
                timestamp,
                severity,
                "GET api.example.com/items",
                null,
                durationMs,
                null,
                "GET",
                "/items",
                status,
                null,
                false,
                null,
                null,
                false);
    }

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

    static StoredActivityEntry stored(String instanceId, long seq, ActivityEntryDto entry) {
        return new StoredActivityEntry(instanceId, seq, entry);
    }
}
