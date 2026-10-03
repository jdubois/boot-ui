package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.RunComparisonService;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * §5.8's {@code previous}: DevTools may restart twice for one change, so the newest kept run can be one that served
 * nothing. Comparing with it would answer {@code INSUFFICIENT} and report every route as new, so {@code previous} skips
 * it and says so. The service lives in {@code insights}; this test sits here for {@link RunHistory}'s constructor.
 */
class PreviousRunSelectionTests {

    private static final RuntimeJournalSettings SETTINGS =
            new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all());

    @Test
    void previousSkipsNewerKeptRunsThatServedNoRequestAndSaysSo() {
        RunHistory history = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);
        RunIdentity busy = RunIdentity.start();
        history.record(RunSummary.of(busy, served(3), 1));
        RunIdentity idle = RunIdentity.start();
        history.record(RunSummary.of(idle, new JournalAggregates().snapshot(), 2));
        RuntimeJournal journal = new RuntimeJournal(SETTINGS, RunIdentity.start());
        try {
            RunComparisonService service = new RunComparisonService(journal, new JournalAggregates(), history);

            RuntimeRunComparisonDto previous = service.compare(null);
            assertThat(previous.previous().runId()).isEqualTo(busy.id());
            assertThat(previous.limitations().get(0))
                    .contains(busy.id())
                    .contains(idle.id())
                    .contains("served none");
            assertThat(previous.runs()).extracting(run -> run.runId()).containsExactly(idle.id(), busy.id());

            RuntimeRunComparisonDto chosen = service.compare(idle.id());
            assertThat(chosen.previous().runId()).isEqualTo(idle.id());
            assertThat(chosen.limitations()).noneMatch(limitation -> limitation.contains("served none"));
        } finally {
            journal.close();
        }
    }

    @Test
    void previousIsTheNewestKeptRunWhenNoneServedARequest() {
        RunHistory history = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);
        history.record(RunSummary.of(RunIdentity.start(), new JournalAggregates().snapshot(), 1));
        RunIdentity newest = RunIdentity.start();
        history.record(RunSummary.of(newest, new JournalAggregates().snapshot(), 2));
        RuntimeJournal journal = new RuntimeJournal(SETTINGS, RunIdentity.start());
        try {
            RuntimeRunComparisonDto comparison =
                    new RunComparisonService(journal, new JournalAggregates(), history).compare(" ");

            assertThat(comparison.previous().runId()).isEqualTo(newest.id());
            assertThat(comparison.limitations()).noneMatch(limitation -> limitation.contains("served none"));
        } finally {
            journal.close();
        }
    }

    private static JournalAggregates.AggregatesSnapshot served(int requests) {
        JournalAggregates aggregates = new JournalAggregates();
        for (int i = 0; i < requests; i++) {
            RuntimeEvent event = RuntimeEvent.of(
                    JournalSource.HTTP,
                    1_000,
                    2_000_000,
                    CorrelationContext.forRequest("r" + i),
                    "http-nio-8080-exec-1",
                    null,
                    false,
                    new HttpPayload(
                            "GET",
                            "/api/orders",
                            "/api/orders",
                            null,
                            200,
                            new ResourceUsage(1_000_000, 4_096, 1, 0, null, 1, List.of(), false)));
            aggregates.onEntries(List.of(new JournalEntry(i + 1, event, event.estimatedBytes())));
        }
        return aggregates.snapshot();
    }
}
