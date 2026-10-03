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

    /**
     * Sources it also reads when the journal records them; without one, it still runs and names what it cannot see.
     */
    default Set<JournalSource> optionalReads() {
        return Set.of();
    }

    /** Whether this observation examines request or execution units, rather than only unowned collection events. */
    default boolean readsUnits() {
        return true;
    }

    /**
     * Why it does not apply to this snapshot, such as a stack that has no event loop, or {@code null} when it applies.
     * Called after its {@linkplain #reads() sources} are known to be recorded and visible.
     */
    default String notApplicable(InsightsSnapshot snapshot) {
        return null;
    }

    /** Projects the snapshot. */
    Evaluation evaluate(InsightsSnapshot snapshot);

    /**
     * What one evaluation examined and found.
     *
     * @param eligibleRequests the requests it examined
     * @param findings what it found
     * @param uncounted a sentence for the check's reason naming what it left out of its findings: what it could not
     *     judge and left out of {@code eligibleRequests}, such as requests whose statements could not be placed
     *     against their transactions, or what it judged but does not report, such as methods whose remote calls were
     *     all fast; {@code null} when it left nothing out
     */
    record Evaluation(long eligibleRequests, List<Finding> findings, String uncounted) {

        public Evaluation {
            findings = List.copyOf(findings);
        }

        /** An evaluation that judged everything it read. */
        public Evaluation(long eligibleRequests, List<Finding> findings) {
            this(eligibleRequests, findings, null);
        }
    }
}
