package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.ScheduledPayload;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class EventLoopBlockingTests {

    private RuntimeJournal journal;

    @AfterEach
    void close() {
        if (journal != null) {
            journal.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"RestClient", "RestTemplate"})
    void nativeBlockingClientTypesOnAnIssuingEventLoopAreEvidenceWithoutSql(String client) throws Exception {
        start(Set.of(JournalSource.HTTP, JournalSource.REST_CLIENT));
        call("r1", "/loop", client, ThreadKind.EVENT_LOOP);
        complete("r1", "/loop");

        RuntimeInsightsReportDto report = service(null).report();

        assertThat(report.observations())
                .filteredOn(row -> row.kind().equals(EventLoopBlocking.KIND))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.sentence()).contains(client, "event-loop", "1 of 1");
                    assertThat(row.affected()).isEqualTo(1);
                    assertThat(row.exemplarRequestIds()).containsExactly("r1");
                });
        assertThat(check(report).eligibleRequests()).isEqualTo(1);
        assertThat(check(report).reason()).contains("sql");
    }

    @Test
    void reactiveCompletionNamesAndWorkerOffloadsCannotBecomeBlockingHttpEvidence() throws Exception {
        start(JournalSource.all());
        call("r1", "/worker", "RestClient", ThreadKind.WORKER);
        complete("r1", "/worker");
        call("r2", "/reactive", "WebClient", ThreadKind.EVENT_LOOP);
        complete("r2", "/reactive");
        call("r3", "/reactive-worker", "WebClient", ThreadKind.WORKER);
        complete("r3", "/reactive-worker");
        call("r4", "/unknown", "unknown", ThreadKind.EVENT_LOOP);
        complete("r4", "/unknown");

        RuntimeInsightsReportDto report = service(null).report();

        assertThat(report.observations()).noneMatch(row -> row.kind().equals(EventLoopBlocking.KIND));
        assertThat(report.window().requests()).isEqualTo(4);
    }

    @Test
    void eitherHiddenChannelLeavesTheOtherChannelsFactsAndEligibilityIntact() throws Exception {
        start(JournalSource.all());
        call("r1", "/remote", "RestClient", ThreadKind.EVENT_LOOP);
        complete("r1", "/remote");
        sql("r2");
        complete("r2", "/jdbc");
        complete("r3", "/no-calls");

        for (String hidden : Set.of(BootUiPanels.SQL_TRACE, BootUiPanels.REST_CLIENT_TRACE)) {
            RuntimeInsightsReportDto report = service(hidden).report();
            assertThat(report.observations())
                    .filteredOn(row -> row.kind().equals(EventLoopBlocking.KIND))
                    .singleElement()
                    .satisfies(row -> assertThat(row.subject())
                            .isEqualTo(hidden.equals(BootUiPanels.SQL_TRACE) ? "GET /remote" : "GET /jdbc"));
            assertThat(check(report).eligibleRequests()).isEqualTo(1);
            assertThat(check(report).reason()).contains("disabled");
        }
    }

    @Test
    void missingRestSourceDoesNotDiscardExecutedJdbcAndRequestsWithoutCallsAreNotEligible() throws Exception {
        start(Set.of(JournalSource.HTTP, JournalSource.SQL));
        sql("r1");
        complete("r1", "/jdbc");
        complete("r2", "/no-calls");

        RuntimeInsightsReportDto report = service(null).report();

        assertThat(report.observations())
                .filteredOn(row -> row.kind().equals(EventLoopBlocking.KIND))
                .singleElement()
                .satisfies(row -> assertThat(row.sentence()).contains("1 JDBC statement"));
        assertThat(check(report).eligibleRequests()).isEqualTo(1);
        assertThat(check(report).reason()).contains("rest-client");
    }

    @Test
    void eligibleUnitsAreAUnionButEachFindingUsesOnlyItsCapturedSitePopulation() throws Exception {
        start(JournalSource.all());
        sql("r1");
        call("r1", "/remote", "RestClient", ThreadKind.EVENT_LOOP);
        complete("r1", "/mixed");
        sql("r2");
        complete("r2", "/mixed");
        call("r3", "/remote", "RestClient", ThreadKind.WORKER);
        complete("r3", "/mixed");
        complete("r4", "/mixed");

        RuntimeInsightsReportDto report = service(null).report();

        assertThat(check(report).eligibleRequests()).isEqualTo(3);
        assertThat(report.observations())
                .filteredOn(row -> row.kind().equals(EventLoopBlocking.KIND))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.eligible()).isEqualTo(2))
                .anySatisfy(row -> {
                    assertThat(row.sentence()).contains("JDBC");
                    assertThat(row.affected()).isEqualTo(2);
                })
                .anySatisfy(row -> {
                    assertThat(row.sentence()).contains("RestClient");
                    assertThat(row.affected()).isEqualTo(1);
                });
    }

    @Test
    void absentJdbcCaptureDoesNotMakeRetainedHttpCallsUnavailable() throws Exception {
        start(JournalSource.all());
        call("r1", "/remote", "RestClient", ThreadKind.EVENT_LOOP);
        complete("r1", "/remote");
        RuntimeInsightsService service = service(null);
        service.setSqlCapture(() -> SqlCapture.notRecorded(SqlCapture.R2DBC_ONLY));

        RuntimeInsightsReportDto report = service.report();

        assertThat(check(report).status()).isEqualTo("EVALUATED");
        assertThat(check(report).reason()).contains(SqlCapture.R2DBC_ONLY);
        assertThat(report.observations())
                .filteredOn(row -> row.kind().equals(EventLoopBlocking.KIND))
                .singleElement()
                .satisfies(row -> assertThat(row.sentence()).contains("RestClient"));
    }

    @Test
    void aScheduledExecutionNeedsNeitherAnHttpSourceNorAnHttpAnchor() throws Exception {
        start(Set.of(JournalSource.SCHEDULED, JournalSource.REST_CLIENT));
        CorrelationContext context = CorrelationContext.forExecution("job-1");
        journal.offer(RuntimeEvent.of(
                JournalSource.REST_CLIENT,
                1_000,
                0,
                context,
                null,
                "loop",
                ThreadKind.EVENT_LOOP,
                false,
                new RestClientPayload("GET", "127.0.0.1:12345", "/remote", 200, "RestTemplate", false)));
        journal.offer(RuntimeEvent.of(
                JournalSource.SCHEDULED,
                1_000,
                1_000_000,
                context,
                "loop",
                null,
                false,
                new ScheduledPayload("Job.run", null)));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

        RuntimeInsightsReportDto report = service(null).report();

        assertThat(check(report).eligibleRequests()).isEqualTo(1);
        assertThat(report.observations())
                .filteredOn(row -> row.kind().equals(EventLoopBlocking.KIND))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.subject()).isEqualTo("@Scheduled Job.run");
                    assertThat(row.sentence()).contains("1 of 1 run");
                });
    }

    @Test
    void unknownIssuingKindsAreUnassessedAndSpringClientLabelsDoNotClaimQuarkusParity() throws Exception {
        start(JournalSource.all());
        call("r1", "/remote", "RestClient", ThreadKind.OTHER);
        complete("r1", "/remote");
        RuntimeInsightsReportDto unclassified = service(null).report();
        assertThat(check(unclassified).eligibleRequests()).isZero();
        assertThat(check(unclassified).reason()).contains("no classified issuing thread");
        call("r2", "/remote", "RestClient", ThreadKind.EVENT_LOOP);
        complete("r2", "/remote");
        RuntimeInsightsReportDto quarkus =
                new RuntimeInsightsService(journal, null, null, InsightsStack.QUARKUS, null).report();
        assertThat(check(quarkus).eligibleRequests()).isZero();
        assertThat(quarkus.observations()).noneMatch(row -> row.kind().equals(EventLoopBlocking.KIND));
    }

    @ParameterizedTest
    @CsvSource({"QUARKUS,true,true", "SPRING_WEBFLUX,true,false", "SPRING_WEBFLUX,false,true"})
    void r2SqlOnlyCaptureGapsAreUnavailableRatherThanInsufficient(
            InsightsStack stack, boolean restRecorded, boolean restVisible) throws Exception {
        Set<JournalSource> sources = EnumSet.of(JournalSource.HTTP, JournalSource.SQL);
        if (restRecorded) {
            sources.add(JournalSource.REST_CLIENT);
        }
        start(sources);
        complete("r1", "/no-calls");
        RuntimeInsightsService service = new RuntimeInsightsService(
                journal, null, panel -> restVisible || !panel.equals(BootUiPanels.REST_CLIENT_TRACE), stack, null);
        service.setSqlCapture(() -> SqlCapture.notRecorded(SqlCapture.NOT_RECORDED));

        RuntimeInsightsReportDto report = service.report();

        assertThat(check(report).status()).isEqualTo("UNAVAILABLE");
        assertThat(check(report).reason()).isEqualTo(SqlCapture.NOT_RECORDED);
        assertThat(check(report).eligibleRequests()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"QUARKUS,true,true", "SPRING_WEBFLUX,true,false", "SPRING_WEBFLUX,false,true"})
    void r2SqlOnlyCaptureGapsDoNotEraseRetainedJdbcExecutions(
            InsightsStack stack, boolean restRecorded, boolean restVisible) throws Exception {
        Set<JournalSource> sources = EnumSet.of(JournalSource.HTTP, JournalSource.SQL);
        if (restRecorded) {
            sources.add(JournalSource.REST_CLIENT);
        }
        start(sources);
        sql("r1");
        complete("r1", "/jdbc");
        RuntimeInsightsService service = new RuntimeInsightsService(
                journal, null, panel -> restVisible || !panel.equals(BootUiPanels.REST_CLIENT_TRACE), stack, null);
        service.setSqlCapture(() -> SqlCapture.notRecorded(SqlCapture.NOT_RECORDED));

        RuntimeInsightsReportDto report = service.report();

        assertThat(check(report).status()).isEqualTo("EVALUATED");
        assertThat(report.observations())
                .filteredOn(row -> row.kind().equals(EventLoopBlocking.KIND))
                .singleElement()
                .satisfies(row -> assertThat(row.sentence()).contains("1 JDBC statement"));
    }

    private void start(Set<JournalSource> sources) {
        journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, sources), RunIdentity.start());
    }

    private RuntimeInsightsService service(String hidden) {
        return new RuntimeInsightsService(
                journal, null, panel -> !panel.equals(hidden), InsightsStack.SPRING_WEBFLUX, null);
    }

    private static RuntimeInsightCheckDto check(RuntimeInsightsReportDto report) {
        return report.checks().stream()
                .filter(row -> row.kind().equals(EventLoopBlocking.KIND))
                .findFirst()
                .orElseThrow();
    }

    private void call(String id, String path, String client, ThreadKind issuingKind) {
        journal.offer(RuntimeEvent.of(
                JournalSource.REST_CLIENT,
                1_000,
                500_000_000,
                CorrelationContext.forRequest(id),
                null,
                "reactor-http-nio-1",
                issuingKind,
                false,
                new RestClientPayload("GET", "127.0.0.1:12345", path, 200, client, false)));
    }

    private void sql(String id) {
        journal.offer(RuntimeEvent.of(
                JournalSource.SQL,
                1_000,
                0,
                CorrelationContext.forRequest(id),
                null,
                "reactor-http-nio-1",
                ThreadKind.EVENT_LOOP,
                false,
                new SqlPayload("select * from orders", "Repo.find:1", "db", false, null, null, 1)));
    }

    private void complete(String id, String path) throws Exception {
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000,
                1_000_000_000,
                CorrelationContext.forRequest(id),
                "reactor-http-nio-1",
                null,
                false,
                new HttpPayload("GET", path, path, null, 200)));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
    }
}
