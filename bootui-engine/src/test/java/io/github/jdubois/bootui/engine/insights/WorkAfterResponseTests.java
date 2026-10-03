package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AsyncHandoffPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkAfterResponseTests {

    private static final String SEED = "GET /seed/work-after-response";
    private static final String WAITS = "GET /seed/work-after-response/waits";

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int requests;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void workStillRunningAfterTheResponseThatRanSqlIsReportedFromOneRequest() {
        // The seed: hands a query to a raw executor and answers before it ran.
        request("/seed/work-after-response", 1_000, handoff(1_010, 200, null, false), sql(1_150));

        RuntimeObservationDto observation = only(service(true).report());

        assertThat(observation.kind()).isEqualTo(WorkAfterResponse.KIND);
        assertThat(observation.subject()).isEqualTo(SEED);
        assertThat(observation.sentence())
                .isEqualTo("`" + SEED + "` handed work to an executor that was still running after the response in 1"
                        + " of 1 request.");
        assertThat(observation.affected()).isEqualTo(1);
    }

    @Test
    void aFailureAfterTheResponseIsNamed() {
        request(
                "/seed/work-after-response",
                1_000,
                new Child(
                        JournalSource.AGENT_EXECUTORS,
                        1_010,
                        Duration.ofMillis(100).toNanos(),
                        "async-1",
                        new AsyncHandoffPayload(
                                "async-1",
                                null,
                                "java.util.concurrent.FutureTask",
                                "ThreadPoolExecutor.runWorker",
                                1_005,
                                0,
                                null,
                                true,
                                "java.lang.IllegalStateException",
                                true,
                                80_000L,
                                false)));

        RuntimeObservationDto observation = only(service(true).report());

        assertThat(observation.sentence()).contains("1 task failed after the response");
    }

    @Test
    void theCounterexampleThatWaitsForItsWorkIsNotReported() {
        // Ends before the request's response: the handler waited for it.
        request("/seed/work-after-response/waits", 1_000, handoff(1_002, 10, false, false), sql(1_005));

        RuntimeInsightsReportDto report = service(true).report();

        assertThat(byKind(report)).isEmpty();
        assertThat(check(report).status()).isNotEqualTo("NOT_APPLICABLE");
        assertThat(WAITS).isNotBlank();
    }

    @Test
    void aWaitedForTaskWhoseHandoffClosedJustAfterTheResponseIsNotReported() {
        // The handler's get() returned when the task set its result; the worker closed the handoff 1 ms after the
        // response started, but the task's query ended before it.
        request("/seed/work-after-response/waits", 1_000, handoff(1_002, 10, true, 1_000L, false), sql(1_005));

        assertThat(byKind(service(true).report())).isEmpty();
    }

    @Test
    void aFastTaskStartingLongAfterTheResponseIsReported() {
        request(
                "/seed/work-after-response",
                1_000,
                observedBody(1_200, 1_500_000, true, 1_500L, 1_030_000L),
                new Child(
                        JournalSource.SQL,
                        1_200,
                        500_000,
                        "async-1",
                        new SqlPayload("insert into orders values (?)", "Seeds.lambda:12", "db", false)));

        assertThat(only(service(true).report()).affected()).isEqualTo(1);
    }

    @Test
    void earlySqlIsEvidenceOfATaskWhoseBodyContinuesAfterTheResponse() {
        request(
                "/seed/work-after-response",
                1_000,
                observedBody(1_010, 495_000_000, true, 480_000L, 1_025_000L),
                sql(1_012));

        assertThat(only(service(true).report()).affected()).isEqualTo(1);
    }

    @Test
    void aBodyCompletedBeforeTheResponseDoesNotCountItsEarlierSql() {
        request(
                "/seed/work-after-response/waits",
                1_000,
                observedBody(1_002, 500_000_000, false, 0L, 1_030_000L),
                sql(1_005));

        assertThat(byKind(service(true).report())).isEmpty();
    }

    @Test
    void aBodyThatFailedBeforeTheResponseIsNotALateFailure() {
        request(
                "/seed/work-after-response/waits",
                1_000,
                new Child(
                        JournalSource.AGENT_EXECUTORS,
                        1_002,
                        500_000_000,
                        "async-1",
                        new AsyncHandoffPayload(
                                "async-1",
                                null,
                                "java.util.concurrent.FutureTask",
                                "ThreadPoolExecutor.runWorker",
                                1_001,
                                0,
                                null,
                                true,
                                "java.lang.IllegalStateException",
                                true,
                                472_000L,
                                false,
                                false,
                                0L,
                                1_030_000L)));

        assertThat(byKind(service(true).report())).isEmpty();
    }

    @Test
    void lateTailIoDoesNotTurnAnEarlierFailureIntoALateFailure() {
        request(
                "/seed/work-after-response",
                1_000,
                new Child(
                        JournalSource.AGENT_EXECUTORS,
                        1_002,
                        500_000_000,
                        "async-1",
                        new AsyncHandoffPayload(
                                "async-1",
                                null,
                                "java.util.concurrent.FutureTask",
                                "ThreadPoolExecutor.runWorker",
                                1_001,
                                0,
                                null,
                                true,
                                "java.lang.IllegalStateException",
                                true,
                                472_000L,
                                false,
                                false,
                                0L,
                                1_030_000L)),
                sql(1_200));

        RuntimeObservationDto observation = only(service(true).report());
        assertThat(observation.affected()).isEqualTo(1);
        assertThat(observation.sentence()).doesNotContain("failed after the response");
    }

    @Test
    void dependentWorkAfterBodyCompletionIsStillReported() {
        request(
                "/seed/work-after-response",
                1_000,
                observedBody(1_002, 500_000_000, false, 0L, 1_030_000L),
                sql(1_200));

        assertThat(only(service(true).report()).affected()).isEqualTo(1);
    }

    @Test
    void anUnmarkedLateTaskUsesTheActualResponseBoundaryNotItsStart() {
        request(
                "/seed/work-after-response",
                1_000,
                observedBody(1_200, 1_500_000, null, null, 1_030_000L),
                new Child(
                        JournalSource.SQL,
                        1_200,
                        500_000,
                        "async-1",
                        new SqlPayload("insert into orders values (?)", "Seeds.lambda:12", "db", false)));

        assertThat(only(service(true).report()).affected()).isEqualTo(1);
    }

    @Test
    void aQueryEndingWithinTheTimestampsPrecisionOfTheResponseIsNotReported() {
        // As recorded: the query completed at 1_011.1 ms after running 1.9 ms, so it starts at 1_011 - 1 and reads as
        // ending at 1_011.9; the handoff truly started at 1_002.9 and the response at 1_011.2, recovered as 1_010.3.
        // The query reads 1.6 ms after the response although it ended before it.
        request(
                "/seed/work-after-response/waits",
                1_000,
                handoff(1_002, 10, true, 1_700L, false),
                new Child(
                        JournalSource.SQL,
                        1_010,
                        1_900_000,
                        "async-1",
                        new SqlPayload("select * from orders", "Seeds.lambda:12", "db", false)));

        assertThat(byKind(service(true).report())).isEmpty();
    }

    @Test
    void aLibraryHandoffThatRecordedNothingIsNeverCounted() {
        request("/seed/work-after-response", 1_000, handoff(1_010, 200, true, false));

        assertThat(byKind(service(true).report())).isEmpty();
    }

    @Test
    void workRecordedPastTheHandoffDeadlineIsNotAttributed() {
        request(
                "/seed/work-after-response",
                1_000,
                handoff(1_010, Duration.ofSeconds(10).toMillis(), true, true),
                sql(1_010 + Duration.ofSeconds(9).toMillis()));
        RuntimeInsightsService service = service(true);
        service.setAgent(() -> null, Duration.ofSeconds(5));

        assertThat(byKind(service.report())).isEmpty();
    }

    @Test
    void withoutTheAgentItDoesNotApply() {
        request("/seed/work-after-response", 1_000, handoff(1_010, 200, true, false), sql(1_150));

        RuntimeInsightCheckDto check = check(service(false).report());

        assertThat(check.status()).isEqualTo("NOT_APPLICABLE");
        assertThat(check.reason())
                .contains("requires the BootUI agent's executors sensor")
                .contains("-javaagent");
    }

    @Test
    void withTheAgentButWithoutPropagationItDoesNotApplyAndSaysWhy() {
        request("/seed/work-after-response", 1_000, handoff(1_010, 200, true, false), sql(1_150));
        RuntimeInsightsService service = service(true);
        service.setAgent(
                () -> "Requires the BootUI agent's executors sensor: the agent disabled executor propagation: self-test"
                        + " failed",
                null);

        RuntimeInsightCheckDto check = check(service.report());

        assertThat(check.status()).isEqualTo("NOT_APPLICABLE");
        assertThat(check.reason())
                .isEqualTo("This observation requires the BootUI agent's executors sensor: the agent disabled executor"
                        + " propagation: self-test failed");
    }

    @Test
    void aHandoffStartingPastMaxHandoffAfterItsRequestEndedIsNotItsWork() {
        // The request ends at 1_030; this handoff starts six seconds later, past a five-second max-handoff.
        request(
                "/seed/work-after-response",
                1_000,
                handoff(1_030 + Duration.ofSeconds(6).toMillis(), 200, true, false),
                sql(1_030 + Duration.ofSeconds(6).toMillis() + 50));
        RuntimeInsightsService service = service(true);
        service.setAgent(() -> null, Duration.ofSeconds(5));

        assertThat(byKind(service.report())).isEmpty();
    }

    @Test
    void itReadsRestCallsAsWork() {
        request(
                "/seed/work-after-response",
                1_000,
                handoff(1_010, 200, true, false),
                new Child(
                        JournalSource.REST_CLIENT,
                        1_150,
                        1_000_000,
                        "async-1",
                        new RestClientPayload("POST", "audit:8080", "/events", 202, "RestClient", false)));

        RuntimeObservationDto observation = only(service(true).report());
        assertThat(observation.affected()).isEqualTo(1);
    }

    private RuntimeInsightsService service(boolean agent) {
        RuntimeInsightsService service = new RuntimeInsightsService(journal, null, null, null, List::of);
        service.setAgent(() -> agent ? null : WorkAfterResponse.REQUIRES_AGENT, null);
        return service;
    }

    private static List<RuntimeObservationDto> byKind(RuntimeInsightsReportDto report) {
        return report.observations().stream()
                .filter(observation -> observation.kind().equals(WorkAfterResponse.KIND))
                .toList();
    }

    private static RuntimeObservationDto only(RuntimeInsightsReportDto report) {
        List<RuntimeObservationDto> observations = byKind(report);
        assertThat(observations).hasSize(1);
        return observations.get(0);
    }

    private static RuntimeInsightCheckDto check(RuntimeInsightsReportDto report) {
        return report.checks().stream()
                .filter(check -> check.kind().equals(WorkAfterResponse.KIND))
                .findFirst()
                .orElseThrow();
    }

    private static Child handoff(long start, long durationMillis, Boolean afterResponse, boolean capped) {
        return handoff(
                start,
                durationMillis,
                afterResponse,
                afterResponse == null ? null : afterResponse ? 100_000L : 0L,
                capped);
    }

    private static Child handoff(
            long start, long durationMillis, Boolean afterResponse, Long afterResponseMicros, boolean capped) {
        return new Child(
                JournalSource.AGENT_EXECUTORS,
                start,
                Duration.ofMillis(durationMillis).toNanos(),
                "async-1",
                new AsyncHandoffPayload(
                        "async-1",
                        null,
                        "com.example.Seeds$$Lambda",
                        "ThreadPoolExecutor.runWorker",
                        start - 1,
                        1_000_000,
                        2_048L,
                        false,
                        null,
                        afterResponse,
                        afterResponseMicros,
                        capped));
    }

    private static Child sql(long at) {
        return new Child(
                JournalSource.SQL,
                at,
                2_000_000,
                "async-1",
                new SqlPayload("select * from orders", "Seeds.lambda:12", "db", false));
    }

    private static Child observedBody(
            long start,
            long durationNanos,
            Boolean bodyAfterResponse,
            Long bodyAfterResponseMicros,
            long responseAtMicros) {
        return new Child(
                JournalSource.AGENT_EXECUTORS,
                start,
                durationNanos,
                "async-1",
                new AsyncHandoffPayload(
                        "async-1",
                        null,
                        "java.util.concurrent.FutureTask",
                        "ThreadPoolExecutor.runWorker",
                        start - 1,
                        1_000_000,
                        2_048L,
                        false,
                        null,
                        true,
                        Math.max(0, start * 1_000 + durationNanos / 1_000 - Math.max(start * 1_000, responseAtMicros)),
                        false,
                        bodyAfterResponse,
                        bodyAfterResponseMicros,
                        responseAtMicros));
    }

    private void request(String path, long start, Child... children) {
        String requestId = "r" + (++requests);
        CorrelationContext context = CorrelationContext.forRequest(requestId);
        for (Child child : children) {
            journal.offer(RuntimeEvent.of(
                    child.source(),
                    child.epochMillis(),
                    child.nanos(),
                    context.withExecutionId(child.executionId()),
                    "pool-1-thread-1",
                    null,
                    false,
                    child.payload()));
        }
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                start,
                30_000_000,
                context,
                "http-nio-1",
                null,
                false,
                new HttpPayload("GET", path, path, null, 200)));
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private record Child(
            JournalSource source, long epochMillis, long nanos, String executionId, RuntimeEventPayload payload) {}
}
