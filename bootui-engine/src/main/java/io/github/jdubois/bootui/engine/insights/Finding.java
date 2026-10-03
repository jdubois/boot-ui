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
        CorrelationTier tier) {

    public Finding {
        whatToCheck = List.copyOf(whatToCheck);
        exemplarRequestIds = List.copyOf(exemplarRequestIds);
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
        limitations = List.copyOf(limitations);
    }

    /** A finding at its kind's minimum tier. */
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
}
