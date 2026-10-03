package io.github.jdubois.bootui.core.dto;

/**
 * One observation kind and whether it could run ({@code docs/PLAN-v2.md} §5.5).
 *
 * @param kind the observation kind, such as {@code repeated-selects}
 * @param title its title
 * @param status {@code EVALUATED}, {@code PARTIAL} when a source it reads dropped events, {@code NOT_APPLICABLE}
 *     when a source it needs is not recorded, or {@code UNAVAILABLE} when the application's own activity it reads
 *     cannot be captured, such as SQL over R2DBC
 * @param eligibleRequests the requests it examined
 * @param findings the observations it produced
 * @param reason why it is partial, not applicable, or unavailable, or what it could not count, or {@code null}
 */
public record RuntimeInsightCheckDto(
        String kind, String title, String status, long eligibleRequests, int findings, String reason) {}
