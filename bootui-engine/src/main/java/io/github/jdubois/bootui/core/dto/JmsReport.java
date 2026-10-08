package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the JMS panel.
 *
 * @param available whether Spring JMS capture is supported and a matching integration is present
 * @param unavailableReason why Spring JMS capture is unavailable, or {@code null}
 * @param capturing whether capture is currently enabled
 * @param captureMessageIdEnabled whether provider message IDs are retained as short hashes
 * @param maxEntries the configured capture buffer bound
 * @param totalCaptured total messages captured since startup
 * @param total number of messages currently retained
 * @param messages retained messages, most recent first
 */
public record JmsReport(
        boolean available,
        String unavailableReason,
        boolean capturing,
        boolean captureMessageIdEnabled,
        int maxEntries,
        long totalCaptured,
        int total,
        List<JmsMessageDto> messages) {

    public JmsReport {
        messages = DtoCollections.immutableCopy(messages);
    }

    public static JmsReport unavailable(String reason, int maxEntries) {
        return new JmsReport(false, reason, false, false, maxEntries, 0, 0, List.of());
    }
}
