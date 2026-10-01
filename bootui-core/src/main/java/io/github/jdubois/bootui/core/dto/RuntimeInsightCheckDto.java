package io.github.jdubois.bootui.core.dto;

/**
 * One observation kind and whether it could run ({@code docs/PLAN-v2.md} §5.5).
 *
 * @param kind the observation kind, such as {@code repeated-selects}
 * @param title its title
 * @param status {@code EVALUATED}, {@code PARTIAL} when a source it reads dropped events, or {@code NOT_APPLICABLE}
 *     when a source it needs is not recorded
 * @param eligibleRequests the requests it examined
 * @param findings the observations it produced
 * @param reason why it is partial or not applicable, or {@code null}
 */
public record RuntimeInsightCheckDto(
        String kind, String title, String status, long eligibleRequests, int findings, String reason) {}
