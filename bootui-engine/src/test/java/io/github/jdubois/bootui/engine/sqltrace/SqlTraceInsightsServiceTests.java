package io.github.jdubois.bootui.engine.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.SqlTraceInsightsReport;
import io.github.jdubois.bootui.engine.sqltrace.SqlRouteAttribution.Correlation;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.Category;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.StatementType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SqlTraceInsightsServiceTests {

    private static final Set<Correlation> SERVLET =
            Set.of(Correlation.TRACE_ID, Correlation.SERVING_THREAD, Correlation.TIME_WINDOW);

    private SqlTraceRecorder recorder(boolean enabled, int maxEntries) {
        SqlTraceRecorder recorder = new SqlTraceRecorder(enabled, true, true, false, maxEntries, 100, 4_000, 200, 5);
        if (enabled) {
            recorder.registerDataSource("dataSource");
        }
        return recorder;
    }

    /** {@code durationMillis} is written in milliseconds and recorded in the microseconds the API takes. */
    private void record(SqlTraceRecorder recorder, String sql, long durationMillis) {
        recorder.record(
                StatementType.PREPARED,
                Category.SELECT,
                sql,
                List.of(),
                durationMillis * 1_000L,
                true,
                null,
                null,
                0,
                "conn-1",
                "exec-1");
    }

    @Test
    void reportsUnavailableWhenTracingIsOff() {
        SqlTraceInsightsReport report = new SqlTraceInsightsService(recorder(false, 100))
                .insights(List.of(), SERVLET, RouteTemplateResolver.empty());

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).contains("SQL tracing is not active");
        assertThat(report.statements()).isEmpty();
        assertThat(report.attribution().available()).isFalse();
    }

    @Test
    void stillRanksStatementsWhenTheRuntimeCannotSupplyRequestEvidence() {
        SqlTraceRecorder recorder = recorder(true, 100);
        record(recorder, "select * from orders where id = 1", 30);

        SqlTraceInsightsReport report = new SqlTraceInsightsService(recorder)
                .insights(
                        List.of(),
                        Set.of(Correlation.TRACE_ID),
                        RouteTemplateResolver.empty(),
                        "Request correlation needs the OpenTelemetry integration on WebFlux.");

        assertThat(report.available()).isTrue();
        assertThat(report.statements()).hasSize(1);
        assertThat(report.attribution().available()).isFalse();
        assertThat(report.attribution().unavailableReason()).contains("OpenTelemetry");
        assertThat(report.attribution().routes()).isEmpty();
        assertThat(report.attribution().unattributed().executions()).isZero();
    }

    @Test
    void reportsUnavailableWhenNoDataSourceHasBeenWrapped() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, true, false, 100, 100, 4_000, 200, 5);

        assertThat(new SqlTraceInsightsService(recorder)
                        .insights(List.of(), SERVLET, RouteTemplateResolver.empty())
                        .available())
                .isFalse();
    }

    @Test
    void describesTheBoundedWindowTheRankingsCover() {
        SqlTraceRecorder recorder = recorder(true, 100);
        record(recorder, "select * from a where id = 1", 30);
        record(recorder, "select * from a where id = 2", 10);

        SqlTraceInsightsReport report =
                new SqlTraceInsightsService(recorder).insights(List.of(), SERVLET, RouteTemplateResolver.empty());

        assertThat(report.available()).isTrue();
        assertThat(report.window().retainedStatements()).isEqualTo(2);
        assertThat(report.window().bufferSize()).isEqualTo(100);
        assertThat(report.window().totalCaptured()).isEqualTo(2);
        assertThat(report.window().totalDurationMillis()).isEqualTo(40);
        assertThat(report.window().oldestTimestamp()).isNotNull();
        assertThat(report.window().newestTimestamp()).isNotNull();
        assertThat(report.distinctStatements()).isEqualTo(1);
        assertThat(report.topPerCriterion()).isEqualTo(SqlStatementRanking.TOP_PER_CRITERION);
    }

    @Test
    void warnsWhenOlderExecutionsHaveAlreadyBeenEvicted() {
        SqlTraceRecorder recorder = recorder(true, 2);
        record(recorder, "select 1", 5);
        record(recorder, "select 2", 5);
        record(recorder, "select 3", 5);

        SqlTraceInsightsReport report =
                new SqlTraceInsightsService(recorder).insights(List.of(), SERVLET, RouteTemplateResolver.empty());

        assertThat(report.window().retainedStatements()).isEqualTo(2);
        assertThat(report.window().evicted()).isEqualTo(1);
        assertThat(report.notes()).anySatisfy(note -> assertThat(note).contains("aged out"));
    }

    @Test
    void saysTheFiguresDescribeTheRetainedWindowRatherThanLifetimeTotals() {
        SqlTraceRecorder recorder = recorder(true, 100);
        record(recorder, "select 1", 5);

        SqlTraceInsightsReport report =
                new SqlTraceInsightsService(recorder).insights(List.of(), SERVLET, RouteTemplateResolver.empty());

        assertThat(report.notes()).anySatisfy(note -> assertThat(note).contains("not lifetime metrics"));
    }

    @Test
    void reportsPausedRecordingSoAStaleWindowIsNotMistakenForAQuietSystem() {
        SqlTraceRecorder recorder = recorder(true, 100);
        record(recorder, "select 1", 5);
        recorder.setRecording(false);

        SqlTraceInsightsReport report =
                new SqlTraceInsightsService(recorder).insights(List.of(), SERVLET, RouteTemplateResolver.empty());

        assertThat(report.capturing()).isFalse();
        assertThat(report.notes()).anySatisfy(note -> assertThat(note).contains("paused"));
    }

    @Test
    void neverExposesBoundParametersEvenWhenParameterCaptureIsOn() {
        SqlTraceRecorder recorder = recorder(true, 100);
        recorder.record(
                StatementType.PREPARED,
                Category.SELECT,
                "select * from users where email = ?",
                List.of("ada@example.com"),
                5,
                true,
                null,
                null,
                0,
                "conn-1",
                "exec-1");

        SqlTraceInsightsReport report =
                new SqlTraceInsightsService(recorder).insights(List.of(), SERVLET, RouteTemplateResolver.empty());

        assertThat(report.statements().get(0).sql()).doesNotContain("ada@example.com");
    }

    @Test
    void attributesRecordedStatementsToTheSuppliedRequests() {
        SqlTraceRecorder recorder = recorder(true, 100);
        record(recorder, "select * from orders", 12);
        long now = System.currentTimeMillis();
        SqlRequestEvidence request =
                new SqlRequestEvidence("r1", "GET", "/api/orders", "/api/orders", null, now - 500, now + 500, "exec-1");

        SqlTraceInsightsReport report = new SqlTraceInsightsService(recorder)
                .insights(List.of(request), SERVLET, RouteTemplateResolver.empty());

        assertThat(report.attribution().routes()).hasSize(1);
        assertThat(report.attribution().routes().get(0).route()).isEqualTo("/api/orders");
        assertThat(report.attribution().supportedCorrelations())
                .containsExactly("SERVING_THREAD", "TIME_WINDOW", "TRACE_ID");
    }
}
