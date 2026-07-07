package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the Email Viewer panel: the captured outgoing messages plus enough context
 * for the UI to explain an empty or unavailable state.
 *
 * @param available whether a mail sender is present and captured messages can be listed
 * @param unavailableReason the reason no mail sender was found, or {@code null} when {@code available}
 * @param devTrapEnabled whether dev-trap mode is on (captured messages are not actually sent)
 * @param maxEntries the configured capture buffer bound
 * @param total total number of messages currently captured (before any paging)
 * @param messages the captured messages, newest-first
 */
public record EmailsReport(
        boolean available,
        String unavailableReason,
        boolean devTrapEnabled,
        int maxEntries,
        int total,
        List<EmailMessageDto> messages) {

    public EmailsReport {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static EmailsReport unavailable(String reason, int maxEntries) {
        return new EmailsReport(false, reason, false, maxEntries, 0, List.of());
    }
}
