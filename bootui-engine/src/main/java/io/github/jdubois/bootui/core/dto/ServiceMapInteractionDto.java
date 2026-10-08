package io.github.jdubois.bootui.core.dto;

/**
 * One retained, already-completed interaction with a mapped dependency, reduced to the smallest safe
 * shape the Live Flow map needs.
 *
 * <p>Every field is deliberately non-identifying. No remote path, query string, SQL text, message key,
 * payload, header, or credential is ever carried here: {@link #operation()} is a coarse, safe verb
 * (an HTTP method, a SQL category, a cache operation, or a publish direction) and nothing more.</p>
 *
 * <p>{@link #id()} is stable across refreshes because it is derived from the originating bounded
 * buffer's monotonic sequence number. That stability is what lets the browser tell a genuinely new
 * completed interaction apart from one it has already drawn, so the map can animate only new evidence
 * instead of looping perpetually.</p>
 *
 * <p>{@link #flowId()} lets the browser recognize when several interactions on different edges — an
 * inbound request, a cache access, a SQL statement, an outbound call — are evidence of the same causal
 * flow through this application, so their motion can be sequenced instead of drawn as unrelated events.
 * It is derived one-way from whatever distributed-trace id was active when the interaction completed
 * (see {@code ServiceMapIdentities#flowId}); the raw trace id itself never reaches this contract, and a
 * blank trace id yields a {@code null} flowId rather than a synthetic one. Messaging interactions (Kafka,
 * RabbitMQ) never carry a trace id at capture time, so they never carry a flowId either — they remain
 * uncorrelated, exactly as they are everywhere else in BootUI.</p>
 *
 * @param id stable identifier of the form {@code <protocol>:<sequence>}, unique within one report
 * @param timestamp epoch milliseconds when the interaction completed
 * @param operation coarse, safe operation label such as {@code GET}, {@code SELECT}, or {@code PUBLISH}
 * @param outcome {@code OK} when the interaction completed successfully, {@code FAILED} otherwise
 * @param durationMs wall-clock duration in milliseconds, or {@code null} when the source cannot report one
 * @param flowId opaque, one-way identifier shared by interactions observed under the same distributed
 *     trace, or {@code null} when no trace was active (or none applies, as with messaging)
 */
public record ServiceMapInteractionDto(
        String id, long timestamp, String operation, String outcome, Long durationMs, String flowId) {

    /** Binary/source-compatible constructor for callers compiled against the pre-correlation contract. */
    public ServiceMapInteractionDto(String id, long timestamp, String operation, String outcome, Long durationMs) {
        this(id, timestamp, operation, outcome, durationMs, null);
    }
}
