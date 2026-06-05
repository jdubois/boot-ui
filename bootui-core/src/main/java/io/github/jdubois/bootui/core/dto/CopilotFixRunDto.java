package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Snapshot of a single "Fix it with Copilot" run.
 *
 * <p>The agent's edits are always isolated on a dedicated branch and surfaced as a diff for the
 * developer to review; nothing is committed to the developer's current branch or pushed
 * automatically.
 *
 * @param id opaque run identifier
 * @param findingId the finding this run targets
 * @param status one of {@code PENDING}, {@code RUNNING}, {@code SUCCEEDED}, {@code NO_CHANGES},
 *     {@code FAILED} or {@code SDK_UNAVAILABLE}
 * @param branch the isolated branch the edits were applied to, or {@code null}
 * @param message short human-readable status message
 * @param diff unified diff of the proposed edits, or {@code null} when there are none
 * @param filesChanged number of files touched by the proposed edits
 * @param events ordered progress events accumulated so far
 * @param startedAt epoch milliseconds when the run started
 * @param finishedAt epoch milliseconds when the run finished, or {@code null} while running
 */
public record CopilotFixRunDto(
        String id,
        String findingId,
        String status,
        String branch,
        String message,
        String diff,
        int filesChanged,
        List<CopilotFixEventDto> events,
        long startedAt,
        Long finishedAt) {

    public CopilotFixRunDto {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
