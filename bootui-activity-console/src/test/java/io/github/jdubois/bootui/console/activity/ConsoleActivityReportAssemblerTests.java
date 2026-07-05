package io.github.jdubois.bootui.console.activity;

import static io.github.jdubois.bootui.console.activity.ConsoleActivityTestFixtures.entry;
import static io.github.jdubois.bootui.console.activity.ConsoleActivityTestFixtures.requestEntry;
import static io.github.jdubois.bootui.console.activity.ConsoleActivityTestFixtures.sqlEntry;
import static io.github.jdubois.bootui.console.activity.ConsoleActivityTestFixtures.stored;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ConsoleActivityReportAssembler}: the console's main-feed builder. Pins the
 * instance-prefixing convention, the KPI computation (mirroring {@code LiveActivityAssembler}'s
 * algorithms), and that {@code pageInfo.persistent} is always {@code true} (the UI gate that decides
 * whether filters/cursor are even sent, see {@code LiveActivity.vue}).
 */
class ConsoleActivityReportAssemblerTests {

    @Test
    void emptyPageRendersAvailableWithAWarningAndNoSources() {
        LiveActivityReport report = ConsoleActivityReportAssembler.assemble(ActivityPage.EMPTY, true, "activity");

        assertThat(report.available()).isTrue();
        assertThat(report.entries()).isEmpty();
        assertThat(report.typeCounts()).isEmpty();
        assertThat(report.sources()).isEmpty();
        assertThat(report.warnings()).hasSize(1);
        assertThat(report.warnings().get(0)).contains("No activity has been received yet");
        assertThat(report.pageInfo().persistent()).isTrue();
        assertThat(report.pageInfo().nextCursor()).isNull();
        assertThat(report.pageInfo().hasMore()).isFalse();
        assertThat(report.persistenceOption().active()).isTrue();
        assertThat(report.persistenceOption().dataSourceAvailable()).isTrue();
        assertThat(report.persistenceOption().tableName()).isEqualTo("activity");
    }

    @Test
    void entriesAreSummaryPrefixedWithTheirOwningInstanceId() {
        StoredActivityEntry e1 = stored("spring-sample--8080", 1, entry("e1", "REQUEST", 1_000, "OK", "GET /hello"));
        ActivityPage page = new ActivityPage(List.of(e1), null, false);

        LiveActivityReport report = ConsoleActivityReportAssembler.assemble(page, true, "activity");

        assertThat(report.entries()).hasSize(1);
        assertThat(report.entries().get(0).summary()).isEqualTo("[spring-sample--8080] GET /hello");
        // Every other field is passed through unchanged.
        assertThat(report.entries().get(0).id()).isEqualTo("e1");
        assertThat(report.entries().get(0).type()).isEqualTo("REQUEST");
    }

    @Test
    void aNullSummaryPrefixesCleanlyWithNoTrailingGarbage() {
        StoredActivityEntry e1 = stored("sender-1", 1, entry("e1", "SECURITY", 1_000, "WARN", null));
        ActivityPage page = new ActivityPage(List.of(e1), null, false);

        LiveActivityReport report = ConsoleActivityReportAssembler.assemble(page, true, "activity");

        assertThat(report.entries().get(0).summary()).isEqualTo("[sender-1] ");
    }

    @Test
    void nonEmptyPageHasNoWarnings() {
        StoredActivityEntry e1 = stored("sender-1", 1, entry("e1", "REQUEST", 1_000, "OK", "hi"));
        ActivityPage page = new ActivityPage(List.of(e1), null, false);

        LiveActivityReport report = ConsoleActivityReportAssembler.assemble(page, true, "activity");

        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void typeCountsAggregateAcrossAllEntries() {
        ActivityPage page = new ActivityPage(
                List.of(
                        stored("a", 1, entry("e1", "REQUEST", 1_000, "OK", "r1")),
                        stored("a", 2, entry("e2", "REQUEST", 1_001, "OK", "r2")),
                        stored("b", 1, entry("e3", "SQL", 1_002, "OK", "s1"))),
                null,
                false);

        LiveActivityReport report = ConsoleActivityReportAssembler.assemble(page, true, "activity");

        assertThat(report.typeCounts()).containsEntry("REQUEST", 2).containsEntry("SQL", 1);
    }

    @Test
    void sourcesAreDistinctAndSortedAcrossContributingInstances() {
        ActivityPage page = new ActivityPage(
                List.of(
                        stored("quarkus-sample--8081", 1, entry("e1", "SQL", 1_000, "OK", "s1")),
                        stored("spring-sample--8080", 1, entry("e2", "REQUEST", 1_001, "OK", "r1")),
                        stored("spring-sample--8080", 2, entry("e3", "REQUEST", 1_002, "OK", "r2"))),
                null,
                false);

        LiveActivityReport report = ConsoleActivityReportAssembler.assemble(page, true, "activity");

        assertThat(report.sources()).containsExactly("quarkus-sample--8081", "spring-sample--8080");
    }

    @Test
    void errorRateCountsOnlyRequestEntriesWithA4xxOr5xxStatus() {
        ActivityPage page = new ActivityPage(
                List.of(
                        stored("a", 1, requestEntry("e1", 1_000, "GET /ok", 10, 200, null)),
                        stored("a", 2, requestEntry("e2", 1_001, "GET /bad", 5, 500, null)),
                        stored("a", 3, requestEntry("e3", 1_002, "GET /missing", 5, 404, null))),
                null,
                false);

        LiveActivityReport report = ConsoleActivityReportAssembler.assemble(page, true, "activity");

        assertThat(report.kpis().errorRatePercent()).isCloseTo(66.66, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void latencyPercentilesAndSlowestEndpointAreComputedFromRequestEntriesOnly() {
        ActivityPage page = new ActivityPage(
                List.of(
                        stored("a", 1, requestEntry("e1", 1_000, "GET /fast", 10, 200, null)),
                        stored("a", 2, requestEntry("e2", 1_001, "GET /slow", 100, 200, null)),
                        stored("a", 3, sqlEntry("e3", 1_002, "SELECT 1", 500, null))),
                null,
                false);

        LiveActivityReport report = ConsoleActivityReportAssembler.assemble(page, true, "activity");

        assertThat(report.kpis().slowestEndpoint()).isEqualTo("GET /slow");
        assertThat(report.kpis().slowestEndpointMs()).isEqualTo(100L);
        assertThat(report.kpis().p50LatencyMs()).isNotNull();
        assertThat(report.kpis().p95LatencyMs()).isNotNull();
        // The SQL entry's own (much larger) duration must never leak into request latency KPIs.
        assertThat(report.kpis().p95LatencyMs()).isLessThan(500L);
    }

    @Test
    void slowestQueryMsTracksOnlySqlEntries() {
        ActivityPage page = new ActivityPage(
                List.of(
                        stored("a", 1, requestEntry("e1", 1_000, "GET /x", 999, 200, null)),
                        stored("a", 2, sqlEntry("e2", 1_001, "SELECT 1", 5, null)),
                        stored("a", 3, sqlEntry("e3", 1_002, "SELECT 2", 42, null))),
                null,
                false);

        LiveActivityReport report = ConsoleActivityReportAssembler.assemble(page, true, "activity");

        assertThat(report.kpis().slowestQueryMs()).isEqualTo(42L);
    }

    @Test
    void exceptionEntriesAreCountedInActiveExceptionCount() {
        ActivityPage page = new ActivityPage(
                List.of(
                        stored("a", 1, entry("e1", "EXCEPTION", 1_000, "ERROR", "NPE")),
                        stored("a", 2, entry("e2", "EXCEPTION", 1_001, "ERROR", "IOException"))),
                null,
                false);

        LiveActivityReport report = ConsoleActivityReportAssembler.assemble(page, true, "activity");

        assertThat(report.kpis().activeExceptionCount()).isEqualTo(2);
    }

    @Test
    void requestsAndSqlPerMinuteAreAlwaysZeroSinceAPagesTimeSpanIsArbitrary() {
        ActivityPage page =
                new ActivityPage(List.of(stored("a", 1, entry("e1", "REQUEST", 1_000, "OK", "hi"))), null, false);

        LiveActivityReport report = ConsoleActivityReportAssembler.assemble(page, true, "activity");

        assertThat(report.kpis().requestsPerMinute()).isZero();
        assertThat(report.kpis().sqlPerMinute()).isZero();
    }

    @Test
    void healthAndHeapKpisAreAlwaysNullSinceTheConsoleHasNoHostApplicationOfItsOwn() {
        ActivityPage page =
                new ActivityPage(List.of(stored("a", 1, entry("e1", "REQUEST", 1_000, "OK", "hi"))), null, false);

        LiveActivityReport report = ConsoleActivityReportAssembler.assemble(page, true, "activity");

        assertThat(report.kpis().healthStatus()).isNull();
        assertThat(report.kpis().heapUsedBytes()).isNull();
        assertThat(report.kpis().heapMaxBytes()).isNull();
    }

    @Test
    void pageInfoCarriesTheUnderlyingCursorAndHasMoreFlagThrough() {
        ActivityPage page = new ActivityPage(
                List.of(stored("a", 1, entry("e1", "REQUEST", 1_000, "OK", "hi"))), "cursor-abc", true);

        LiveActivityReport report = ConsoleActivityReportAssembler.assemble(page, true, "activity");

        assertThat(report.pageInfo().persistent()).isTrue();
        assertThat(report.pageInfo().nextCursor()).isEqualTo("cursor-abc");
        assertThat(report.pageInfo().hasMore()).isTrue();
    }

    @Test
    void dataSourceAvailableFlagAndTableNameArePassedThroughToThePersistenceOption() {
        ActivityPage page = ActivityPage.EMPTY;

        LiveActivityReport reportUnavailable = ConsoleActivityReportAssembler.assemble(page, false, "custom_table");

        assertThat(reportUnavailable.persistenceOption().dataSourceAvailable()).isFalse();
        assertThat(reportUnavailable.persistenceOption().tableName()).isEqualTo("custom_table");
    }
}
