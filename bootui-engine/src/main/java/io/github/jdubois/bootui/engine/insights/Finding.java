package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.List;

/**
 * One finding of an observation, before it becomes a report row ({@code docs/PLAN-v2.md} §5.5).
 *
 * @param key what makes it stable across refreshes and runs, after the kind, such as a route and a statement hash
 * @param subject what it is about, as shown
 * @param sufficient whether the evidence reaches the kind's minimum; otherwise it is reported {@code INSUFFICIENT}
 * @param sentence one sentence naming what was counted, or what is missing when insufficient
 * @param eligible the requests that could have shown it
 * @param affected the requests that did
 * @param whatToCheck one to three conditional checks
 * @param exemplarRequestIds request ids to open, most telling first
 * @param columns the evidence columns
 * @param rows the evidence rows, most telling first
 * @param limitations what it cannot see
 * @param tier the weakest correlation tier its evidence used, when it is known to be stronger than the kind's {@link
 *     Observation#minimumTier()}, or {@code null} for the kind's
 * @param unlisted why the default list leaves it out ({@code docs/PLAN-v2.md} M4-19), such as a route under the
 *     prominence threshold, or {@code null} when it is listed
 */
public record Finding(
        String key,
        String subject,
        boolean sufficient,
        String sentence,
        long eligible,
        long affected,
        List<String> whatToCheck,
        List<String> exemplarRequestIds,
        List<String> columns,
        List<List<String>> rows,
        List<String> limitations,
        CorrelationTier tier,
        String unlisted) {

    public Finding {
        whatToCheck = List.copyOf(whatToCheck);
        exemplarRequestIds = List.copyOf(exemplarRequestIds);
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
        limitations = List.copyOf(limitations);
    }

    /** A listed finding at {@code tier}. */
    public Finding(
            String key,
            String subject,
            boolean sufficient,
            String sentence,
            long eligible,
            long affected,
            List<String> whatToCheck,
            List<String> exemplarRequestIds,
            List<String> columns,
            List<List<String>> rows,
            List<String> limitations,
            CorrelationTier tier) {
        this(
                key,
                subject,
                sufficient,
                sentence,
                eligible,
                affected,
                whatToCheck,
                exemplarRequestIds,
                columns,
                rows,
                limitations,
                tier,
                null);
    }

    /** A listed finding at its kind's minimum tier. */
    public Finding(
            String key,
            String subject,
            boolean sufficient,
            String sentence,
            long eligible,
            long affected,
            List<String> whatToCheck,
            List<String> exemplarRequestIds,
            List<String> columns,
            List<List<String>> rows,
            List<String> limitations) {
        this(
                key,
                subject,
                sufficient,
                sentence,
                eligible,
                affected,
                whatToCheck,
                exemplarRequestIds,
                columns,
                rows,
                limitations,
                null);
    }

    /** Whether the default list shows it. */
    public boolean listed() {
        return unlisted == null;
    }

    /** This finding, left out of the default list for {@code reason}; a finding already left out keeps its reason. */
    public Finding unlisted(String reason) {
        if (unlisted != null || reason == null) {
            return this;
        }
        return new Finding(
                key,
                subject,
                sufficient,
                sentence,
                eligible,
                affected,
                whatToCheck,
                exemplarRequestIds,
                columns,
                rows,
                limitations,
                tier,
                reason);
    }

    /** This finding, left out of the default list for {@code reason}, which replaces any reason it already had. */
    public Finding unlistedWhole(String reason) {
        return new Finding(
                key,
                subject,
                sufficient,
                sentence,
                eligible,
                affected,
                whatToCheck,
                exemplarRequestIds,
                columns,
                rows,
                limitations,
                tier,
                reason);
    }
}
