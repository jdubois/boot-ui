package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One relationship on the Live Flow service map, always anchored on the running application.
 *
 * <p>An {@code OUTBOUND} edge runs from the application to a dependency it calls. The single
 * {@code INBOUND} edge runs from the generic local HTTP client lane into the application. Incoming
 * messaging consumption is deliberately never modelled as an outbound edge.</p>
 *
 * <p>{@link #recentInteractions()} is a small, hard-capped tail of newest-first completed interactions.
 * It exists so the browser can animate genuinely new evidence after a refresh; it is not a log, and it
 * never carries payloads or identifying request detail.</p>
 *
 * @param id stable identifier of the form {@code <fromId>-><toId>}
 * @param fromId the originating node id
 * @param toId the target node id
 * @param protocol {@code HTTP_INBOUND}, {@code HTTP}, {@code JDBC}, {@code KAFKA}, or {@code RABBITMQ}
 * @param direction {@code INBOUND} or {@code OUTBOUND}
 * @param interactions number of retained completed interactions
 * @param failures number of retained interactions that failed; retained evidence, never live health
 * @param lastSeen epoch milliseconds of the most recent retained interaction, or {@code null} when none
 * @param outcome {@code NO_EVIDENCE}, {@code OBSERVED_OK}, or {@code RETAINED_FAILURES}
 * @param recentInteractions newest-first capped tail of retained interactions
 */
public record ServiceMapEdgeDto(
        String id,
        String fromId,
        String toId,
        String protocol,
        String direction,
        int interactions,
        int failures,
        Long lastSeen,
        String outcome,
        List<ServiceMapInteractionDto> recentInteractions) {

    public ServiceMapEdgeDto {
        recentInteractions = DtoCollections.immutableCopy(recentInteractions);
    }
}
