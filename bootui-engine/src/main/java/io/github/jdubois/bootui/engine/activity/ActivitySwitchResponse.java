package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivitySwitchResult;

/**
 * Framework-neutral outcome of an {@link ActivitySwitchService} action: the HTTP status the adapter
 * should render together with the {@link ActivitySwitchResult} body, mirroring {@code
 * FlywayActionResponse}. On a successful switch, {@link #newSettings()} carries the persistence settings
 * (see {@link ActivityPersistenceSettings#withEnabledSharedMode()}) the caller should start a capture
 * poller with (see {@code ActivityCaptureFactory#start}); it is {@code null} for every other outcome.
 *
 * @param status the HTTP status code (200, 400, 404 or 500)
 * @param body the result body to serialize
 * @param newSettings the settings to start capturing with on success, otherwise {@code null}
 */
public record ActivitySwitchResponse(int status, ActivitySwitchResult body, ActivityPersistenceSettings newSettings) {}
