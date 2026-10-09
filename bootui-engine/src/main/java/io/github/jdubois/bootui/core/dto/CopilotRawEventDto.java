package io.github.jdubois.bootui.core.dto;

/**
 * Raw JSON details for a single event. Returned only when explicitly requested
 * and only when {@code bootui.copilot.allow-raw-reveal=true}.
 */
public record CopilotRawEventDto(String sessionId, String eventId, String json) {}
