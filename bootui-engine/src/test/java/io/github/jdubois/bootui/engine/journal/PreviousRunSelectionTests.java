package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.AppEventCapture;
import io.github.jdubois.bootui.engine.insights.RunComparisonService;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * §5.8's {@code previous} always selects the newest kept run, including a listener-only or idle run.
 * The service lives in {@code insights}; this test sits here for {@link RunHistory}'s constructor.
 */
class PreviousRunSelectionTests {

    private static final RuntimeJournalSettings SETTINGS =
            new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all());

    @Test
    void previousSelectsTheImmediatelyPreviousRunEvenWhenItServedNoRequests() {
        RunHistory history = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);
        RunIdentity busy = RunIdentity.start();
        history.record(RunSummary.of(busy, served(3), 1));
        RunIdentity idle = RunIdentity.start();
        history.record(RunSummary.of(idle, new JournalAggregates().snapshot(), 2));
        RuntimeJournal journal = new RuntimeJournal(SETTINGS, RunIdentity.start());
        try {
            RunComparisonService service = new RunComparisonService(journal, new JournalAggregates(), history);

            RuntimeRunComparisonDto previous = service.compare(null);
            assertThat(previous.previous().runId()).isEqualTo(idle.id());
            assertThat(previous.status()).isEqualTo("INSUFFICIENT");
            assertThat(service.compare("previous").previous().runId()).isEqualTo(idle.id());
            assertThat(previous.runs()).extracting(run -> run.runId()).containsExactly(idle.id(), busy.id());

            RuntimeRunComparisonDto chosen = service.compare(idle.id());
            assertThat(chosen.previous().runId()).isEqualTo(idle.id());
            assertThat(chosen.limitations()).noneMatch(limitation -> limitation.contains("served none"));
        } finally {
            journal.close();
        }
    }

    @Test
    void aReloadableHolderIsUnavailableInsteadOfSuggestingAnotherRestartWillKeepHistory() {
        RuntimeJournal journal = new RuntimeJournal(SETTINGS, RunIdentity.start());
        try {
            RunHistory reloadable =
                    new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, "BootUI reloads with the application.");
            RuntimeRunComparisonDto comparison =
                    new RunComparisonService(journal, new JournalAggregates(), reloadable).compare(null);
            assertThat(comparison.status()).isEqualTo("UNAVAILABLE");
            assertThat(comparison.reason()).contains("baseline-file");
            assertThat(new RunComparisonService(
                                    journal,
                                    new JournalAggregates(),
                                    new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null))
                            .compare(null)
                            .status())
                    .isEqualTo("NO_PREVIOUS_RUN");
            RunIdentity previous = RunIdentity.start();
            reloadable.record(RunSummary.of(previous, served(3), 1));
            assertThat(new RunComparisonService(journal, new JournalAggregates(), reloadable)
                            .compare(null)
                            .status())
                    .isNotEqualTo("UNAVAILABLE");
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

    @Test
    void aComparisonSaysWhyItComparesNoApplicationEventEdgeWhenNoneIsRecorded() {
        RunHistory history = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);
        history.record(RunSummary.of(RunIdentity.start(), served(3), 1));
        RuntimeJournal journal = new RuntimeJournal(SETTINGS, RunIdentity.start());
        try {
            RunComparisonService service = new RunComparisonService(journal, new JournalAggregates(), history);
            service.setAppEventCapture(() -> AppEventCapture.notRecorded(AppEventCapture.CUSTOM_MULTICASTER));
            assertThat(service.compare(null).limitations())
                    .anySatisfy(limitation -> assertThat(limitation)
                            .contains(AppEventCapture.CUSTOM_MULTICASTER, "event edges are not compared"));

            service.setAppEventCapture(AppEventCapture::capturing);
            assertThat(service.compare(null).limitations())
                    .noneSatisfy(limitation -> assertThat(limitation).contains(AppEventCapture.CUSTOM_MULTICASTER));
        } finally {
            journal.close();
        }
    }

    @Test
    void sourcePanelPolicyIsReadOncePerComparisonAndFailsClosed() {
        RunHistory history = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);
        history.record(RunSummary.of(RunIdentity.start(), served(3), 1));
        RuntimeJournal journal = new RuntimeJournal(SETTINGS, RunIdentity.start());
        try {
            AtomicBoolean enabled = new AtomicBoolean(true);
            Map<String, Integer> reads = new HashMap<>();
            RunComparisonService service =
                    new RunComparisonService(journal, new JournalAggregates(), history, panel -> {
                        reads.merge(panel, 1, Integer::sum);
                        return enabled.get();
                    });
            assertThat(service.compare(null).previous().requests()).isEqualTo(3);
            assertThat(reads).hasSize(JournalSourcePanels.owningPanels().size());
            assertThat(reads.values()).allMatch(count -> count == 1);
            enabled.set(false);
            RuntimeRunComparisonDto hidden = service.compare(null);
            assertThat(hidden.previous().requests()).isZero();
            assertThat(hidden.limitations()).contains("Facts are not compared because http-exchanges is disabled.");
            assertThat(reads.values()).allMatch(count -> count == 2);

            RuntimeRunComparisonDto failed = new RunComparisonService(
                            journal, new JournalAggregates(), history, panel -> {
                                throw new IllegalStateException("Unreadable policy");
                            })
                    .compare(null);
            assertThat(failed.previous().requests()).isZero();
            assertThat(failed.limitations()).contains("Facts are not compared because http-exchanges is unavailable.");
            assertThat(failed.behavior()).isEmpty();
            assertThat(failed.edges()).isEmpty();
            RuntimeRunComparisonDto disabled = new RunComparisonService(
                            journal, new JournalAggregates(), history, panel -> false, panel -> {
                                throw new IllegalStateException("Unavailable capability");
                            })
                    .compare(null);
            assertThat(disabled.limitations()).contains("Facts are not compared because http-exchanges is disabled.");
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
