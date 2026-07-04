package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityForwardResult;

/**
 * Framework-neutral outcome of an {@link ActivityForwardService#receive} call: the HTTP status the
 * adapter should render together with the {@link ActivityForwardResult} body, mirroring {@code
 * ActivitySwitchResponse}. Unlike a switch action, a forward never changes which store the receiver
 * itself is using, so there is no {@code newSettings}-equivalent field here.
 *
 * @param status the HTTP status code (200, 400, 401 or 500)
 * @param body the result body to serialize
 */
public record ActivityForwardResponse(int status, ActivityForwardResult body) {}
