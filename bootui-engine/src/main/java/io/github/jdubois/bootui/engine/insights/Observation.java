package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.List;
import java.util.Set;

/**
 * One kind of Runtime Insights observation ({@code docs/PLAN-v2.md} §5.5): a pure projection of the snapshot, with
 * fixtures for a justified case, a plausible counterexample, and insufficient evidence. Its sentences name what was
 * counted, never a cause, a severity, or a patch.
 */
public interface Observation {

    /** The kind, such as {@code repeated-selects}, the first part of each finding's stable id. */
    String kind();

    /** A short title, such as {@code Repeated SELECTs}. */
    String title();

    /** The weakest correlation tier whose evidence it accepts. */
    CorrelationTier minimumTier();

    /** The journal sources it reads; it is not applicable when one is not recorded or its panel is disabled. */
    Set<JournalSource> reads();

    /** Projects the snapshot. */
    Evaluation evaluate(InsightsSnapshot snapshot);

    /**
     * What one evaluation examined and found.
     *
     * @param eligibleRequests the requests it examined
     * @param findings what it found
     */
    record Evaluation(long eligibleRequests, List<Finding> findings) {

        public Evaluation {
            findings = List.copyOf(findings);
        }
    }
}
