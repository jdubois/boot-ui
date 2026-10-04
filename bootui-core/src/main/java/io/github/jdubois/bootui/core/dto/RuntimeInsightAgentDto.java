package io.github.jdubois.bootui.core.dto;

/**
 * One Runtime Insights observation, compacted for an agent ({@code docs/PLAN-v2.md} §5.6): a stable fact it can diff and
 * refuse to act on, never a dashboard row. {@code INSUFFICIENT} and {@code PARTIAL} are not successes.
 *
 * @param id its stable id, for {@code get_runtime_insight}
 * @param kind the check that found it, such as {@code repeated-selects}
 * @param status {@code OBSERVED}, {@code PARTIAL}, or {@code INSUFFICIENT}
 * @param subject the route, job, or listener it is about
 * @param sentence what was counted, in one sentence
 * @param eligible the requests that could have shown it
 * @param affected the requests that did
 * @param tier the weakest correlation tier its evidence accepts, such as {@code REQUEST_ID}
 * @param exemplarRequestId one request to open with {@code get_request_profile}, or {@code null}
 * @param verify the first check to run before editing anything, worded as a condition
 * @param listed whether the default list, the empty query, shows it; a row it leaves out is listed by the query
 *     {@code all}, its kind, or its route, and {@code get_runtime_insight} says why it is left out
 */
public record RuntimeInsightAgentDto(
        String id,
        String kind,
        String status,
        String subject,
        String sentence,
        long eligible,
        long affected,
        String tier,
        String exemplarRequestId,
        String verify,
        boolean listed) {}
