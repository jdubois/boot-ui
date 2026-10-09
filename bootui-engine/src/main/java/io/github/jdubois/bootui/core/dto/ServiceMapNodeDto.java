package io.github.jdubois.bootui.core.dto;

/**
 * One node of the Live Flow service map: the running application itself, the generic local HTTP client
 * lane that reaches it, or a single outbound dependency grouped by safe identity.
 *
 * <p>Identity is deliberately coarse and non-identifying. An HTTP dependency is only ever a
 * {@code scheme://host[:port]} origin — never a path, query string, fragment, or user-info component. A
 * JDBC dependency reuses the Connection Pools panel's already-masked target. Messaging dependencies carry
 * only a topic or an exchange/routing destination. No payload, message body, message key, SQL text, header,
 * or credential reaches this record.</p>
 *
 * <p>{@link #configured()} and {@link #observed()} are reported separately and never collapsed, so absence
 * of traffic is never mistaken for absence of a dependency, and observed traffic is never mistaken for a
 * declared one. {@link #outcome()} describes retained evidence only — it is not a health check of the
 * remote system.</p>
 *
 * @param id stable identifier, unique within one report (for example {@code http:https://api.example.com})
 * @param kind {@code APPLICATION}, {@code INBOUND}, or {@code DEPENDENCY}
 * @param protocol {@code APPLICATION}, {@code HTTP_INBOUND}, {@code HTTP}, {@code JDBC}, {@code KAFKA}, or
 *     {@code RABBITMQ}
 * @param label the safe display identity
 * @param detail optional secondary, already-masked description, or {@code null}
 * @param configured whether the dependency is declared by the application's configuration
 * @param observed whether BootUI retains at least one completed interaction for it
 * @param interactions number of retained completed interactions
 * @param failures number of retained interactions that failed; retained evidence, never live health
 * @param distinctOperations number of distinct safe operations observed, or {@code null} when the source
 *     cannot report one honestly
 * @param lastSeen epoch milliseconds of the most recent retained interaction, or {@code null} when none
 * @param outcome {@code NO_EVIDENCE}, {@code OBSERVED_OK}, or {@code RETAINED_FAILURES}
 * @param sourcePanelId the BootUI panel id that owns this evidence, or {@code null} for the application node
 * @param sourceRoute the UI route that shows the underlying evidence, or {@code null}
 * @param sourceLabel human-readable name of the evidence panel, or {@code null}
 * @param note an honest caveat about what this node does and does not prove, or {@code null}
 */
public record ServiceMapNodeDto(
        String id,
        String kind,
        String protocol,
        String label,
        String detail,
        boolean configured,
        boolean observed,
        int interactions,
        int failures,
        Integer distinctOperations,
        Long lastSeen,
        String outcome,
        String sourcePanelId,
        String sourceRoute,
        String sourceLabel,
        String note) {}
