package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.RuntimeRestartCostDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.RunHistory;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import java.util.ArrayList;
import java.util.List;

/**
 * Serves {@code GET /runtime-insights/comparison} on every stack ({@code docs/PLAN-v2.md} §5.8): the current run
 * compared with the newest kept run, or with a chosen one. It reads the aggregates and the run history in memory: it
 * starts no capture, scan, or network call.
 */
public final class RunComparisonService {

    static final String NO_PREVIOUS_RUN = "No previous run is kept yet: restart the application, as DevTools or a"
            + " Quarkus live reload does, or set bootui.runtime-journal.baseline-file to keep the last run across a"
            + " full JVM restart.";

    private final RuntimeJournal journal;
    private final JournalAggregates aggregates;
    private final RunHistory history;

    /**
     * @param journal the journal, or {@code null} when the adapter created none
     * @param aggregates its aggregates, or {@code null}
     * @param history the JVM's run history, usually {@link RunHistory#shared()}
     */
    public RunComparisonService(RuntimeJournal journal, JournalAggregates aggregates, RunHistory history) {
        this.journal = journal;
        this.aggregates = aggregates;
        this.history = history;
    }

    /**
     * Compares the current run with the kept run {@code runId}, or with the newest kept run when it is {@code null} or
     * blank.
     */
    public RuntimeRunComparisonDto compare(String runId) {
        if (journal == null || aggregates == null || !journal.settings().enabled()) {
            return new RuntimeRunComparisonDto(
                    RunComparison.UNAVAILABLE,
                    "The runtime journal is disabled: set bootui.runtime-journal.enabled=true.",
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    new RuntimeRestartCostDto(RunComparison.UNAVAILABLE, "The journal is disabled.", null, null, null),
                    List.of(),
                    List.of());
        }
        String current = journal.run().id();
        List<RunSummary> kept = new ArrayList<>();
        List<RunSummary.Header> headers = new ArrayList<>();
        String reason = NO_PREVIOUS_RUN;
        if (history != null) {
            try {
                for (RunSummary summary : history.summaries()) {
                    if (!summary.header().runId().equals(current)) {
                        kept.add(summary);
                        headers.add(summary.header());
                    }
                }
            } catch (RuntimeException ex) {
                reason = "The kept runs could not be read: " + ex.getClass().getSimpleName() + ".";
            }
            if (history.unavailableReason() != null) {
                reason = history.unavailableReason() + " Set bootui.runtime-journal.baseline-file to keep the last run"
                        + " in a file instead.";
            } else if (history.baselineRunId() == null && history.baselineNote() != null) {
                reason = history.baselineNote() + " " + NO_PREVIOUS_RUN;
            }
        }
        RunSummary previous = null;
        if (runId == null || runId.isBlank()) {
            previous = kept.isEmpty() ? null : kept.get(0);
        } else {
            for (RunSummary summary : kept) {
                if (summary.header().runId().equals(runId)) {
                    previous = summary;
                    break;
                }
            }
            if (previous == null) {
                reason = "No kept run has the id " + runId + ": it may have been dropped, as only the "
                        + RunHistory.MAX_RUNS + " most recent runs are kept.";
            }
        }
        return RunComparison.compare(
                journal.run(),
                aggregates.snapshot(),
                aggregates.runStart(),
                previous,
                headers,
                reason,
                history == null ? null : history.baselineRunId());
    }
}
