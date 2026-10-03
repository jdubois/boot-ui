package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.JournalActivityFeed.Filter;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.web.ExecutionProfileAssembler;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JournalActivityReportsTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
            RunIdentity.start(),
            false);

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void rowsOfADisabledPanelAreLeftOutAsThe1xFeedLeavesThemOut() {
        offer("r1", JournalSource.SQL, new SqlPayload("select 1", null, "db", false));
        offer("r1", JournalSource.MESSAGING, new MessagingPayload("jms", true, "orders", false, null));
        offer("r1", JournalSource.MESSAGING, new MessagingPayload("kafka", true, "orders", false, null));
        offer("r1", JournalSource.HTTP, new HttpPayload("GET", "/a", "/a", null, 200));
        journal.dispatchPending();

        LiveActivityReport report = new JournalActivityReports(
                        journal,
                        1_000,
                        5,
                        null,
                        panel -> !panel.equals(BootUiPanels.SQL_TRACE) && !panel.equals(BootUiPanels.JMS))
                .report(Filter.NONE, 0, "UP");

        assertThat(report.available()).isTrue();
        assertThat(report.sources()).containsExactly(JournalActivityReports.SOURCE);
        assertThat(report.entries())
                .extracting(ActivityEntryDto::type, ActivityEntryDto::detail)
                .containsExactlyInAnyOrder(tuple("REQUEST", null), tuple("MESSAGING", "kafka"));
        assertThat(report.kpis().sqlPerMinute())
                .as("a disabled panel's events feed no KPI")
                .isZero();
        assertThat(report.kpis().healthStatus()).isEqualTo("UP");
    }

    @Test
    void rowsOfTheSourcesV2AddedAreLeftOutWhenTheirPanelIsDisabled() {
        offer("r1", JournalSource.WEBSOCKET, new WebSocketPayload("/ws", "MESSAGE", true, "/topic", 10, null, false));
        offer("r1", JournalSource.HTTP, new HttpPayload("GET", "/a", "/a", null, 200));
        journal.dispatchPending();

        LiveActivityReport all =
                new JournalActivityReports(journal, 1_000, 5, null, panel -> true).report(Filter.NONE, 0, "UP");
        LiveActivityReport gated = new JournalActivityReports(
                        journal, 1_000, 5, null, panel -> !panel.equals(BootUiPanels.WEBSOCKETS))
                .report(Filter.NONE, 0, "UP");

        assertThat(all.entries()).extracting(ActivityEntryDto::type).containsExactly("REQUEST", "WEBSOCKET");
        assertThat(gated.entries())
                .as("the websockets panel owns the WebSocket row, so disabling it leaves the row out")
                .extracting(ActivityEntryDto::type)
                .containsExactly("REQUEST");
    }

    @Test
    void aReportReturnsTheDefaultNumberOfEntriesAndNeverMoreThanTheMaximum() {
        for (int i = 0; i < JournalActivityReports.DEFAULT_LIMIT + 5; i++) {
            offer("r" + i, JournalSource.HTTP, new HttpPayload("GET", "/a", "/a", null, 200));
        }
        journal.dispatchPending();
        JournalActivityReports reports = new JournalActivityReports(journal, 1_000, 5, null, null);

        assertThat(reports.report(Filter.NONE, 0, null).entries()).hasSize(JournalActivityReports.DEFAULT_LIMIT);
        assertThat(reports.report(Filter.NONE, 3, null).entries()).hasSize(3);
        assertThat(reports.report(Filter.NONE, 1_000_000, null).entries())
                .hasSize(JournalActivityReports.DEFAULT_LIMIT + 5);
    }

    @Test
    void aDisabledJournalReportsItselfUnavailable() {
        LiveActivityReport report = new JournalActivityReports(null, 1_000, 5, null, null).report(Filter.NONE, 0, null);

        assertThat(report.available()).isFalse();
        assertThat(report.warnings()).containsExactly(JournalActivityReports.DISABLED);
    }

    @Test
    void journalOnlyFiltersAreRecognizedAndTheFeedSourceParsesStrictly() {
        assertThat(JournalActivityReports.hasJournalOnlyFilter(Filter.NONE)).isFalse();
        assertThat(JournalActivityReports.hasJournalOnlyFilter(new Filter("SQL", "ERROR", 5, null, null, null, false)))
                .isFalse();
        assertThat(JournalActivityReports.hasJournalOnlyFilter(new Filter(null, null, 0, null, null, null, true)))
                .isTrue();
        assertThat(ActivityFeedSource.parse(" Journal ", ActivityFeedSource.BUFFERS))
                .isEqualTo(ActivityFeedSource.JOURNAL);
        assertThat(ActivityFeedSource.parse("", ActivityFeedSource.JOURNAL)).isEqualTo(ActivityFeedSource.JOURNAL);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ActivityFeedSource.parse("disk", ActivityFeedSource.BUFFERS))
                .withMessageContaining("buffers, journal");
    }

    @Test
    void aProfileIdNamesAnExchangeByItsExchangeIdOrItsRequestId() {
        HttpExchangeDto exchange = new HttpExchangeDto(
                "exchange-7",
                Instant.EPOCH,
                "GET",
                "/a",
                null,
                null,
                200,
                null,
                1L,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                "0a1b2c3d4e5f6a7b");

        assertThat(ExecutionProfileAssembler.identifies(exchange, "exchange-7")).isTrue();
        assertThat(ExecutionProfileAssembler.identifies(exchange, "0a1b2c3d4e5f6a7b"))
                .isTrue();
        assertThat(ExecutionProfileAssembler.identifies(exchange, "other")).isFalse();
        assertThat(ExecutionProfileAssembler.identifies(exchange, null)).isFalse();
    }

    private void offer(String requestId, JournalSource source, RuntimeEventPayload payload) {
        journal.offer(RuntimeEvent.of(
                source, 1_000, 1_000_000, CorrelationContext.forRequest(requestId), "t", null, false, payload));
    }
}
