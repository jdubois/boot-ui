package io.github.jdubois.bootui.core.dto;

/**
 * One difference between two runs ({@code docs/PLAN-v2.md} §5.8): a behavior row, an edge of the runtime model, a
 * startup step, or a latency.
 *
 * @param kind what differs, such as {@code statements-per-request}, {@code new-statement}, {@code edge}, or
 *     {@code warm-p50}
 * @param subject what it differs for, such as a route like {@code GET /api/orders/{id}} or a bean
 * @param detail the item that is new or gone, such as a statement fingerprint, an exception signature, or an edge's
 *     target, or {@code null}
 * @param change {@code ADDED}, {@code REMOVED}, {@code INCREASED}, or {@code DECREASED}
 * @param before the previous run's value, or {@code null} when it had none
 * @param after the current run's value, or {@code null} when it has none
 * @param beforeSamples the requests or observations the previous value is over
 * @param afterSamples the requests or observations the current value is over
 * @param sentence the difference as one sentence, with code in backticks
 */
public record RuntimeRunChangeDto(
        String kind,
        String subject,
        String detail,
        String change,
        Double before,
        Double after,
        long beforeSamples,
        long afterSamples,
        String sentence) {}
