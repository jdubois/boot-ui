package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Detailed view of a Copilot session: summary, counts, turn story, and recent events.
 */
public record CopilotSessionDetail(
        CopilotSessionSummary summary,
        CopilotInsightCounts counts,
        List<CopilotTurn> turns,
        List<CopilotActivityEvent> recentEvents,
        List<CopilotActivityEvent> failureEvents,
        List<String> warnings) {

    public CopilotSessionDetail {
        turns = DtoCollections.immutableCopy(turns);
        recentEvents = DtoCollections.immutableCopy(recentEvents);
        failureEvents = DtoCollections.immutableCopy(failureEvents);
        warnings = DtoCollections.immutableCopy(warnings);
    }
}
