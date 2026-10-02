package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.activity.BootUiJdbcCaptureGuard;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.security.CapturedSecurityEvent;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.Category;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.StatementType;
import io.github.jdubois.bootui.engine.telemetry.AttributeValue;
import io.github.jdubois.bootui.engine.telemetry.NormalizedSpan;
import io.github.jdubois.bootui.engine.telemetry.TelemetrySettings;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Pausing a panel or suspending it while BootUI is idle stops what the panel retains, never what the runtime journal
 * records ({@code docs/PLAN-v2.md} §5.2): the panel's buffer stays empty, the journal still receives every event of a
 * source it records, and nothing reaches a journal that does not record the source.
 */
class PausedPanelPublishingTests {

    private static final CorrelationContext REQUEST = CorrelationContext.forRequest("0123456789abcdef");

    private final List<RuntimeEvent> published = new ArrayList<>();

    /** A journal that records every source. */
    private final RuntimeEventSink recording = published::add;

    /** A journal that records no source; offering to it is a bug the test catches. */
    private final RuntimeEventSink notRecording = new RuntimeEventSink() {
        @Override
        public boolean offer(RuntimeEvent event) {
            published.add(event);
            return false;
        }

        @Override
        public boolean records(JournalSource source) {
            return false;
        }
    };

    @Test
    void aPausedOrIdleSqlTracePanelKeepsPublishingStatementsAndConnections() {
        for (Consumer<SqlTraceRecorder> quiet :
                List.<Consumer<SqlTraceRecorder>>of(r -> r.setRecording(false), SqlTraceRecorder::suspendForIdle)) {
            published.clear();
            SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, true, false, 8, 100, 2000, 200, 5, 50);
            recorder.setRuntimeEventSink(recording);
            quiet.accept(recorder);

            inRequest(() -> {
                var checkout = recorder.checkoutConnection("primary", 1_000);
                recordSelect(recorder);
                recorder.releaseConnection(checkout);
            });

            assertThat(recorder.capturesForPanel()).isFalse();
            assertThat(recorder.recent()).isEmpty();
            assertThat(recorder.totalCaptured()).isZero();
            assertThat(published)
                    .extracting(RuntimeEvent::source)
                    .containsExactly(JournalSource.SQL, JournalSource.CONNECTION);
            RuntimeEvent statement = published.get(0);
            assertThat(statement.requestId()).isEqualTo(REQUEST.requestId());
            assertThat(statement.durationNanos()).isEqualTo(3_000_000L);
            assertThat(((SqlPayload) statement.payload()).sql()).isEqualTo("select * from orders where id = ?");
        }
    }

    @Test
    void aDisabledOrSelfSuppressedSqlTraceRecorderPublishesNothingWhilePaused() {
        SqlTraceRecorder disabled = new SqlTraceRecorder(false, true, true, false, 8, 100, 2000, 200, 5, 50);
        disabled.setRuntimeEventSink(recording);
        disabled.setRecording(false);
        SqlTraceRecorder paused = new SqlTraceRecorder(true, true, true, false, 8, 100, 2000, 200, 5, 50);
        paused.setRuntimeEventSink(recording);
        paused.setRecording(false);

        inRequest(() -> {
            assertThat(disabled.checkoutConnection("primary", 1_000)).isNull();
            recordSelect(disabled);
            BootUiJdbcCaptureGuard.runSuppressed(() -> {
                assertThat(paused.checkoutConnection("primary", 1_000)).isNull();
                recordSelect(paused);
            });
        });

        assertThat(disabled.recent()).isEmpty();
        assertThat(paused.recent()).isEmpty();
        assertThat(published)
                .as("enabled=false and BootUI's own SQL record nothing")
                .isEmpty();
    }

    @Test
    void aPausedSqlTracePanelPublishesNothingWhenTheJournalDoesNotRecordSql() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, true, false, 8, 100, 2000, 200, 5, 50);
        recorder.setRuntimeEventSink(notRecording);
        recorder.setRecording(false);

        inRequest(() -> {
            assertThat(recorder.checkoutConnection("primary", 1_000)).isNull();
            recordSelect(recorder);
        });

        assertThat(recorder.recent()).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void aRecordingSqlTracePanelStillRetainsAndPublishes() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, true, false, 8, 100, 2000, 200, 5, 50);
        recorder.setRuntimeEventSink(recording);

        inRequest(() -> recordSelect(recorder));

        assertThat(recorder.recent())
                .singleElement()
                .satisfies(entry -> assertThat(entry.parameters()).containsExactly("42"));
        assertThat(published).singleElement().extracting(RuntimeEvent::source).isEqualTo(JournalSource.SQL);
    }

    @Test
    void aPausedOrIdleTransactionsPanelKeepsPublishingTransactions() {
        for (Consumer<TransactionRecorder> quiet : List.<Consumer<TransactionRecorder>>of(
                r -> r.setRecording(false), TransactionRecorder::suspendForIdle)) {
            published.clear();
            TransactionRecorder recorder = new TransactionRecorder(true, true, 10, 100, 100, null);
            recorder.setRuntimeEventSink(recording);
            quiet.accept(recorder);

            inRequest(() -> {
                long id = recorder.beginTransaction("OrderService.place", false, null, "worker-1", null);
                recorder.completeTransaction(id, TransactionRecorder.Status.ROLLED_BACK, "boom");
            });

            assertThat(recorder.recent()).isEmpty();
            assertThat(published).singleElement().satisfies(event -> {
                assertThat(event.source()).isEqualTo(JournalSource.TRANSACTION);
                assertThat(event.requestId()).isEqualTo(REQUEST.requestId());
                assertThat(((TransactionPayload) event.payload()).rolledBack()).isTrue();
            });
        }
    }

    @Test
    void aPausedTransactionsPanelPublishesNothingWhenTheJournalDoesNotRecordTransactions() {
        TransactionRecorder recorder = new TransactionRecorder(true, true, 10, 100, 100, null);
        recorder.setRuntimeEventSink(notRecording);
        recorder.setRecording(false);

        long id = recorder.beginTransaction("OrderService.place", false, null, "worker-1", null);
        recorder.completeTransaction(id, TransactionRecorder.Status.COMMITTED, null);

        assertThat(id).isEqualTo(-1);
        assertThat(recorder.recent()).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void aPausedOrIdleRestClientTracePanelKeepsPublishingCalls() {
        for (Consumer<RestClientTraceRecorder> quiet : List.<Consumer<RestClientTraceRecorder>>of(
                r -> r.setRecording(false), RestClientTraceRecorder::suspendForIdle)) {
            published.clear();
            RestClientTraceRecorder recorder =
                    new RestClientTraceRecorder(true, true, true, false, 8, 500, 2000, 200, 5);
            recorder.setRuntimeEventSink(recording);
            quiet.accept(recorder);

            inRequest(() -> recordCall(recorder));

            assertThat(recorder.recent()).isEmpty();
            assertThat(recorder.totalCaptured()).isZero();
            assertThat(published).singleElement().satisfies(event -> {
                assertThat(event.source()).isEqualTo(JournalSource.REST_CLIENT);
                assertThat(event.requestId()).isEqualTo(REQUEST.requestId());
                assertThat(event.payload())
                        .usingRecursiveComparison()
                        .ignoringFields("completedNanos")
                        .isEqualTo(
                                new RestClientPayload("GET", "localhost:8082", "/api/stock", 503, "RestClient", true));
            });
        }
    }

    @Test
    void aPausedRestClientTracePanelPublishesNothingWhenTheJournalDoesNotRecordCalls() {
        RestClientTraceRecorder recorder = new RestClientTraceRecorder(true, true, true, false, 8, 500, 2000, 200, 5);
        recorder.setRuntimeEventSink(notRecording);
        recorder.setRecording(false);

        recordCall(recorder);

        assertThat(recorder.recent()).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void anIdleTelemetryStoreStillPublishesAiSpansWithoutStoringThem() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 500, 4096));
        store.setRuntimeEventSink(recording);
        store.suspendForIdle();

        assertThat(store.add(aiSpan("trace-1"), false)).isFalse();
        assertThat(store.add(aiSpan("trace-2"), true)).as("BootUI's own trace").isFalse();
        assertThat(store.add(aiSpan("trace-2"), false))
                .as("the rest of BootUI's own trace")
                .isFalse();

        assertThat(store.recentTraces(10)).isEmpty();
        assertThat(published).singleElement().satisfies(event -> {
            assertThat(event.source()).isEqualTo(JournalSource.AI);
            assertThat(event.traceId()).isEqualTo("trace-1");
            assertThat(((AiPayload) event.payload()).model()).isEqualTo("gpt-4o");
        });
    }

    @Test
    void anIdleTelemetryStorePublishesNothingWhenTheJournalDoesNotRecordAi() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 500, 4096));
        store.setRuntimeEventSink(notRecording);
        store.suspendForIdle();

        store.add(aiSpan("trace-1"), false);

        assertThat(store.recentTraces(10)).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void anIdleSecurityEventBufferKeepsPublishingEvents() {
        SecurityEventBuffer buffer = new SecurityEventBuffer(10);
        buffer.setRuntimeEventSink(recording);
        buffer.suspendForIdle();
        List<String> notified = new ArrayList<>();
        buffer.subscribe(() -> notified.add("changed"));

        buffer.record(securityEvent());

        assertThat(buffer.snapshot()).isEmpty();
        assertThat(notified).isEmpty();
        assertThat(published).singleElement().satisfies(event -> {
            assertThat(event.source()).isEqualTo(JournalSource.SECURITY);
            assertThat(event.requestId()).isEqualTo("0123456789abcdef");
            assertThat(event.payload()).isEqualTo(new SecurityPayload("AUTHENTICATION_FAILURE"));
        });
    }

    @Test
    void anIdleSecurityEventBufferPublishesNothingWhenTheJournalDoesNotRecordSecurity() {
        SecurityEventBuffer buffer = new SecurityEventBuffer(10);
        buffer.setRuntimeEventSink(notRecording);
        buffer.suspendForIdle();

        buffer.record(securityEvent());

        assertThat(buffer.snapshot()).isEmpty();
        assertThat(published).isEmpty();
    }

    private static void recordSelect(SqlTraceRecorder recorder) {
        recorder.record(
                StatementType.PREPARED,
                Category.SELECT,
                "select * from orders where id = ?",
                List.of("42"),
                3_000,
                true,
                null,
                null,
                0,
                "conn-1",
                "worker-1");
    }

    private static void recordCall(RestClientTraceRecorder recorder) {
        recorder.record(
                "GET",
                "http://localhost:8082/api/stock",
                "localhost",
                "/api/stock",
                503,
                12,
                false,
                "unavailable",
                "RestClient",
                Map.of(),
                "worker-1");
    }

    private static CapturedSecurityEvent securityEvent() {
        return new CapturedSecurityEvent(
                Instant.ofEpochMilli(2_000), "alice", "AUTHENTICATION_FAILURE", Map.of(), null, "0123456789abcdef");
    }

    private static NormalizedSpan aiSpan(String traceId) {
        return new NormalizedSpan(
                traceId,
                "span-" + traceId,
                null,
                "chat gpt-4o",
                "CLIENT",
                "sample",
                "spring-ai",
                5_000_000L,
                45_000_000L,
                "OK",
                null,
                Map.of(
                        "gen_ai.operation.name", AttributeValue.ofString("chat"),
                        "gen_ai.system", AttributeValue.ofString("openai"),
                        "gen_ai.request.model", AttributeValue.ofString("gpt-4o")),
                List.of());
    }

    private static void inRequest(Runnable work) {
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(REQUEST)) {
            work.run();
        }
    }
}
