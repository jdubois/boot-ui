package io.github.jdubois.bootui.core.dto;

/**
 * The body of {@code POST {api}/java-agent/sensors/{id}}, which switches an opt-in agent sensor on or off at run time
 * ({@code docs/PLAN-v2.md} M5-14).
 *
 * @param enabled whether the sensor should record; required
 */
public record JavaAgentSensorSwitchRequest(Boolean enabled) {}
