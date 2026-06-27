package io.github.jdubois.bootui.engine.telemetry;

/**
 * Framework-neutral snapshot of the {@code bootui.ai.*} and {@code bootui.telemetry.enabled}
 * settings the {@link AiUsageService} needs. The adapter supplies a fresh instance per request
 * (through a {@code Supplier}) so a runtime override is honored without rebinding the engine.
 *
 * @param telemetryEnabled whether trace capture is enabled (drives the AI overview banner gating)
 * @param maxRecentChats how many recent chats the overview embeds
 * @param tokenSeriesMinutes the default token time-series window in minutes
 * @param showContentCaptureBanner whether to surface the "content not captured" guidance banner
 */
public record AiUsageSettings(
        boolean telemetryEnabled, int maxRecentChats, int tokenSeriesMinutes, boolean showContentCaptureBanner) {}
