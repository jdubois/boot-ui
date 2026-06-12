package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Signals that could not be tied to any request in the Diagnostics dashboard, surfaced separately
 * rather than hidden so a user can still inspect them.
 *
 * @param sqlCount number of unattributed SQL executions
 * @param exceptionCount number of unattributed exceptions
 * @param securityCount number of unattributed security events
 * @param logCount number of unattributed log lines
 * @param entries a bounded, most-recent-first sample of the unattributed events
 */
public record DiagnosticsUnattributedDto(
        int sqlCount, int exceptionCount, int securityCount, int logCount, List<DiagnosticsTimelineEntryDto> entries) {

    public DiagnosticsUnattributedDto {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static DiagnosticsUnattributedDto empty() {
        return new DiagnosticsUnattributedDto(0, 0, 0, 0, List.of());
    }
}
