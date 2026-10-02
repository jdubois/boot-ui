package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.security.CapturedSecurityEvent;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.security.SecurityJournal;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.Category;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.StatementType;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every publisher builds its envelope the same way, stamps when its work started, and retains a repeated string once
 * ({@code docs/PLAN-v2.md} §5.2).
 */
class JournalConsistencyTests {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final List<RuntimeEvent> published = new ArrayList<>();

    @Test
    void anEnvelopeTakesItsIdsFromTheContextAndFallsBackToTheResolvedTrace() {
        CorrelationContext request = CorrelationContext.forRequest("r1");

        RuntimeEvent fallback = RuntimeEvent.of(
                JournalSource.CACHE, 1, -1, request, TRACE, "main", null, false, new CachePayload("c", "HIT"));
        RuntimeEvent contextWins = RuntimeEvent.of(
                JournalSource.CACHE,
                1,
                -1,
                CorrelationContext.forExecution("e1").withTrace("ctx-trace", null),
                TRACE,
                "main",
                null,
                false,
                new CachePayload("c", "HIT"));
        RuntimeEvent blank = RuntimeEvent.of(
                JournalSource.CACHE, 1, -1, null, " ", "main", null, false, new CachePayload("c", "HIT"));

        assertThat(fallback.requestId()).isEqualTo("r1");
        assertThat(fallback.traceId()).isEqualTo(TRACE);
        assertThat(contextWins.executionId()).isEqualTo("e1");
        assertThat(contextWins.traceId()).isEqualTo("ctx-trace");
        assertThat(blank.traceId()).isNull();
        assertThat(blank.requestId()).isNull();
    }

    @Test
    void aStartIsTheCompletionLessTheMeasuredDuration() {
        assertThat(RuntimeEvent.startMillis(10_000, 4_000_000)).isEqualTo(9_996);
        assertThat(RuntimeEvent.startMillis(10_000, -1)).isEqualTo(10_000);
        assertThat(RuntimeEvent.millisToNanos(3L)).isEqualTo(3_000_000);
        assertThat(RuntimeEvent.millisToNanos(null)).isEqualTo(-1);
    }

    @Test
    void sqlIsStampedWhenItStartedWithItsNanosecondDuration() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, false, false, 10, 500, 2000, 200, 5);
        recorder.setRuntimeEventSink(published::add);

        long before = System.currentTimeMillis();
        recorder.recordNanos(
                StatementType.STATEMENT,
                Category.SELECT,
                "select 1",
                List.of(),
                2_000_123_456L,
                true,
                null,
                null,
                0,
                "c1",
                "main");
        long after = System.currentTimeMillis();

        RuntimeEvent event = published.get(0);
        assertThat(event.durationNanos()).isEqualTo(2_000_123_456L);
        assertThat(event.epochMillis()).isBetween(before - 2_001, after - 2_000);
        assertThat(recorder.recent().get(0).durationMicros()).isEqualTo(2_000_123);
    }

    @Test
    void restClientAndMessagingCarryNanosecondsAndStampTheirStart() {
        RestClientTraceRecorder rest = new RestClientTraceRecorder(true, true, false, false, 8, 500, 2000, 200, 5);
        KafkaActivityRecorder kafka = new KafkaActivityRecorder(true, true, 10, 16);
        rest.setRuntimeEventSink(published::add);
        kafka.setRuntimeEventSink(published::add);
        CorrelationContext execution = CorrelationContext.forExecution("exec-1");

        long before = System.currentTimeMillis();
        rest.recordNanos(
                "GET",
                "http://pricing/p",
                "pricing",
                "/p",
                200,
                1_500_000_001L,
                true,
                null,
                "RestClient",
                Map.of(),
                "main",
                TRACE,
                CorrelationContext.forRequest("r1"),
                null);
        kafka.recordConsumeNanos("orders", 0, 1L, "k", 1_000_000_007L, true, null, "g", "l", execution);
        long after = System.currentTimeMillis();

        RuntimeEvent call = published.get(0);
        assertThat(call.durationNanos()).isEqualTo(1_500_000_001L);
        assertThat(call.epochMillis()).isBetween(before - 1_501, after - 1_500);
        assertThat(call.requestId()).isEqualTo("r1");
        assertThat(call.traceId()).as("the trace the recorder resolved").isEqualTo(TRACE);
        assertThat(rest.recent().get(0).durationMillis()).isEqualTo(1_500);
        RuntimeEvent consumed = published.get(1);
        assertThat(consumed.durationNanos()).isEqualTo(1_000_000_007L);
        assertThat(consumed.epochMillis()).isBetween(before - 1_001, after - 1_000);
        assertThat(consumed.executionId()).isEqualTo("exec-1");
    }

    @Test
    void securityEventsPublishTheContextTheyWereObservedUnder() {
        SecurityEventBuffer buffer = new SecurityEventBuffer(10);
        buffer.setRuntimeEventSink(published::add);
        CorrelationContext execution = CorrelationContext.forExecution("exec-1");

        buffer.record(
                new CapturedSecurityEvent(
                        Instant.ofEpochMilli(42), "alice", "AUTHENTICATION_FAILURE", Map.of(), TRACE, null),
                execution);
        SecurityJournal.publish(published::add, "AUTHENTICATION_SUCCESS", null, null, null);
        SecurityJournal.publish(
                event -> {
                    throw new IllegalStateException("full");
                },
                "AUTHORIZATION_FAILURE",
                null,
                null,
                null);

        assertThat(published).hasSize(2);
        RuntimeEvent failure = published.get(0);
        assertThat(failure.epochMillis()).isEqualTo(42);
        assertThat(failure.executionId()).isEqualTo("exec-1");
        assertThat(failure.traceId()).isEqualTo(TRACE);
        assertThat(failure.failedOrSlow()).isTrue();
        assertThat(failure.payload()).isEqualTo(new SecurityPayload("AUTHENTICATION_FAILURE"));
        assertThat(published.get(1).failedOrSlow()).isFalse();
    }

    @Test
    void framesAreWalkedOnlyWhenThePanelsCallSitesOrTheJournalKeepThem() {
        assertThat(ApplicationFrames.wanted(true, true, false)).isTrue();
        assertThat(ApplicationFrames.wanted(false, false, true)).isTrue();
        assertThat(ApplicationFrames.wanted(true, false, true)).isTrue();
        assertThat(ApplicationFrames.wanted(true, false, false)).isFalse();
        assertThat(ApplicationFrames.wanted(false, true, false))
                .as("a paused panel keeps no call sites")
                .isFalse();
    }

    @Test
    void repeatedStatementsRetainTheirSqlOnce() {
        String sql = "select o.id, o.total, o.customer_id from orders o where o.customer_id = ? order by o.id";
        long repeated = retainedBytes(i -> new String(sql));
        long distinct = retainedBytes(i -> sql.replace("o.id", "o.id" + i));

        assertThat(repeated).isLessThan(distinct);
    }

    @Test
    void aSharedStringCostsAReferenceAndAnUnsharedOneItsCharacters() {
        SqlPayload payload = new SqlPayload("select * from orders", "A.a(A.java:1)", "orders", false);
        JournalDictionary dictionary = new JournalDictionary(100, 100_000);
        JournalDictionary full = new JournalDictionary(0, 0);

        SqlPayload shared = (SqlPayload) payload.interned(dictionary);
        SqlPayload unshared = (SqlPayload) payload.interned(full);

        assertThat(shared.estimatedBytes(dictionary)).isLessThan(payload.estimatedBytes(null));
        assertThat(unshared.estimatedBytes(full))
                .as("a full dictionary shares nothing, so each string costs its characters")
                .isEqualTo(payload.estimatedBytes(null));
        assertThat(JournalDictionary.retained(dictionary, shared.sql())).isEqualTo(JournalDictionary.REFERENCE_BYTES);
        assertThat(JournalDictionary.retained(dictionary, new String(shared.sql())))
                .as("an equal copy the dictionary does not hold costs its characters")
                .isGreaterThan(JournalDictionary.REFERENCE_BYTES);
    }

    private static long retainedBytes(java.util.function.IntFunction<String> sql) {
        RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 1_000, 10_000_000, 100, 10, 10, JournalSource.all()),
                RunIdentity.start(),
                false);
        try {
            for (int i = 0; i < 50; i++) {
                journal.offer(RuntimeEvent.of(
                        JournalSource.SQL,
                        i,
                        1,
                        null,
                        null,
                        null,
                        false,
                        new SqlPayload(sql.apply(i), null, "orders", false)));
            }
            journal.dispatchPending();
            JournalStatus status = journal.status();
            return status.retainedBytes() + status.dictionaryBytes();
        } finally {
            journal.close();
        }
    }
}
