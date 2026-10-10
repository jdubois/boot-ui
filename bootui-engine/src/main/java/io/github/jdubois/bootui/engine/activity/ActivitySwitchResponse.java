package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivitySwitchResult;

/**
 * Framework-neutral outcome of an {@link ActivitySwitchService} action: the HTTP status the adapter
 * should render together with the {@link ActivitySwitchResult} body, mirroring {@code
 * FlywayActionResponse}. On success the capture is already running against the published durable store.
 * The adapter owns closing {@link #capture()}; both it and {@link #newSettings()} are null for other outcomes.
 *
 * @param status the HTTP status code (200, 400, 404, 409 or 500)
 * @param body the result body to serialize
 * @param newSettings the settings used by the running capture on success, otherwise {@code null}
 * @param capture the running capture to close at shutdown, otherwise {@code null}
 */
public record ActivitySwitchResponse(
        int status, ActivitySwitchResult body, ActivityPersistenceSettings newSettings, ActivityCapture capture) {}
