package io.github.jdubois.bootui.core.dto;

/**
 * The runtime switch of one opt-in agent sensor ({@code docs/PLAN-v2.md} M5-14): whether the application's
 * {@code bootui.agent.sensors} asks for it, whether its claim uses it now, and why it is off by default. A switch lasts
 * until the JVM ends, across DevTools restarts and Quarkus live reloads, and is never written to any file.
 *
 * @param id the sensor's id, such as {@code environment}
 * @param configured whether {@code bootui.agent.sensors} asks for it
 * @param enabled whether this application's claim uses it now, the runtime switch applied
 * @param overridden whether a runtime switch makes {@code enabled} differ from {@code configured}
 * @param state the agent's state for it: {@code off}, {@code installing}, {@code testing}, {@code installed},
 *     {@code self-test-failed}, or {@code failed}
 * @param optInReason why it is off by default
 * @param available whether it can be switched now
 * @param unavailableReason why it cannot, or {@code null}
 */
public record JavaAgentSensorToggleDto(
        String id,
        boolean configured,
        boolean enabled,
        boolean overridden,
        String state,
        String optInReason,
        boolean available,
        String unavailableReason) {}
