package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.journalapp.ApplicationCode;
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
    void anEnvelopeTakesItsIdsFromTheContextAndPrefersTheTraceTheRecorderObserved() {
        CorrelationContext request = CorrelationContext.forRequest("r1");

        RuntimeEvent observed = RuntimeEvent.of(
                JournalSource.CACHE, 1, -1, request, TRACE, "main", null, false, new CachePayload("c", "HIT"));
        RuntimeEvent observedWins = RuntimeEvent.of(
                JournalSource.CACHE,
                1,
                -1,
                CorrelationContext.forExecution("e1").withTrace("ctx-trace", null),
                TRACE,
                "main",
                null,
                false,
                new CachePayload("c", "HIT"));
        RuntimeEvent contextOnly = RuntimeEvent.of(
                JournalSource.CACHE,
                1,
                -1,
                CorrelationContext.forExecution("e1").withTrace("ctx-trace", null),
                " ",
                "main",
                null,
                false,
                new CachePayload("c", "HIT"));
        RuntimeEvent blank = RuntimeEvent.of(
                JournalSource.CACHE, 1, -1, null, " ", "main", null, false, new CachePayload("c", "HIT"));

        assertThat(observed.requestId()).isEqualTo("r1");
        assertThat(observed.traceId()).isEqualTo(TRACE);
        assertThat(observedWins.executionId()).isEqualTo("e1");
        assertThat(observedWins.traceId())
                .as("read with the work, so it wins over a context opened earlier")
                .isEqualTo(TRACE);
        assertThat(contextOnly.traceId()).isEqualTo("ctx-trace");
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
    void framesAreWalkedOnlyWithCallSitesOnAndWhenThePanelOrTheJournalKeepsThem() {
        assertThat(ApplicationFrames.wanted(true, true, false)).isTrue();
        assertThat(ApplicationFrames.wanted(false, true, true)).isTrue();
        assertThat(ApplicationFrames.wanted(true, true, true)).isTrue();
        assertThat(ApplicationFrames.wanted(false, true, false))
                .as("a paused panel and a journal not recording the source keep no frames")
                .isFalse();
        assertThat(ApplicationFrames.wanted(true, false, true))
                .as("turning call sites off skips the walk for the journal too")
                .isFalse();
        assertThat(ApplicationFrames.wanted(false, false, true)).isFalse();
    }

    @Test
    void aPausedPanelsRecordersWalkTheStackForTheJournalOnlyWithCallSitesOn() {
        assertThat(journalSqlFrames(true)).as("the journal keeps the frames").isNotNull();
        assertThat(journalSqlFrames(true).callSite()).startsWith("com.example.journalapp.ApplicationCode.run(");
        assertThat(journalSqlFrames(false)).as("call sites off: no walk").isNull();
        assertThat(journalRestClientFrames(true)).isNotNull();
        assertThat(journalRestClientFrames(false)).isNull();
    }

    @Test
    void aStatementRecordedOnlyForTheJournalIsFormattedOnItsDispatcher() {
        List<RuntimeEvent> events = new ArrayList<>();
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, false, false, true, 10, 500, 2000, 200, 5);
        recorder.setRuntimeEventSink(events::add);
        ApplicationCode.run(() -> recorder.recordNanos(
                StatementType.STATEMENT, Category.SELECT, "select 1", List.of(), 1, true, null, null, 0, "c1", "t"));
        SqlPayload recorded = (SqlPayload) events.get(0).payload();

        assertThat(recorded.frames().isFormatted())
                .as("the application thread only selects the frames")
                .isFalse();

        SqlPayload retained = (SqlPayload) recorded.interned(new JournalDictionary(100, 100_000));
        assertThat(retained.frames().isFormatted()).isTrue();
        assertThat(retained.callSite())
                .startsWith("com.example.journalapp.ApplicationCode.run(")
                .isEqualTo(retained.frames().callSite())
                .isEqualTo(recorded.callSite());
        SqlPayload formattedOnTheApplicationThread = new SqlPayload(
                recorded.sql(),
                recorded.frames().callSite(),
                recorded.dataSource(),
                recorded.failed(),
                ApplicationFrames.of(recorded.frames().frames()),
                recorded.phase(),
                recorded.completedNanos(),
                recorded.codePathStamp());
        assertThat(recorded.estimatedBytes(null))
                .as("the same bytes as frames formatted where they were captured")
                .isEqualTo(formattedOnTheApplicationThread.estimatedBytes(null));
        assertThat(retained.estimatedBytes(null))
                .isEqualTo(formattedOnTheApplicationThread
                        .interned(new JournalDictionary(100, 100_000))
                        .estimatedBytes(null));
    }

    @Test
    void repeatedStatementsRetainTheirSqlOnce() {
        String sql = "select o.id, o.total, o.customer_id from orders o where o.customer_id = ? order by o.id";
        long sqlBytes = RuntimeEvent.stringBytes(sql);
        long repeated = retainedBytes(i -> new String(sql));
        // As long as the repeated statement, and parameterized, so each one is shared once.
        long distinct = retainedBytes(i -> sql.replace("o.total", String.format("o.tot%02d", i)));

        assertThat(repeated)
                .as("50 runs of one statement retain it once, not 50 times")
                .isLessThan(distinct - 40 * sqlBytes);
    }

    @Test
    void aStatementWithAnInlinedValueKeepsItsOwnCopy() {
        JournalDictionary dictionary = new JournalDictionary(100, 100_000);
        SqlPayload inlined = (SqlPayload)
                new SqlPayload("select * from orders where id = 42", null, null, false).interned(dictionary);
        SqlPayload parameterized = (SqlPayload)
                new SqlPayload("select * from orders where id = ?", null, null, false).interned(dictionary);

        assertThat(JournalDictionary.retained(dictionary, inlined.sql()))
                .as("a value concatenated into it would fill the dictionary with one-off strings")
                .isEqualTo(RuntimeEvent.stringBytes(inlined.sql()));
        assertThat(JournalDictionary.retained(dictionary, parameterized.sql()))
                .isEqualTo(JournalDictionary.REFERENCE_BYTES);
        assertThat(dictionary.size()).isEqualTo(1);
    }

    @Test
    void aTemporaryDestinationKeepsItsOwnCopy() {
        JournalDictionary dictionary = new JournalDictionary(100, 100_000);

        for (String temporary : List.of(
                "temp-queue://ID:host-1234-1:1:1",
                "ID:host-1234-1:1:1",
                "amq.gen-JzTY20BRgKO-HjmUJj0wLg",
                "2f1c7d4e-8a1b-4c3d-9e2f-0a1b2c3d4e5f")) {
            MessagingPayload sent =
                    (MessagingPayload) new MessagingPayload("jms", true, temporary, false).interned(dictionary);
            assertThat(JournalDictionary.retained(dictionary, sent.destination()))
                    .as(temporary)
                    .isEqualTo(RuntimeEvent.stringBytes(temporary));
        }
        MessagingPayload named =
                (MessagingPayload) new MessagingPayload("kafka", true, "orders", false).interned(dictionary);
        assertThat(JournalDictionary.retained(dictionary, named.destination()))
                .isEqualTo(JournalDictionary.REFERENCE_BYTES);
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

    /** The frames of the SQL event a paused SQL Trace panel publishes to a journal recording SQL. */
    private static ApplicationFrames journalSqlFrames(boolean captureCallSite) {
        List<RuntimeEvent> events = new ArrayList<>();
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, false, false, captureCallSite, 10, 500, 2000, 200, 5);
        recorder.setRuntimeEventSink(events::add);
        ApplicationCode.run(() -> recorder.recordNanos(
                StatementType.STATEMENT, Category.SELECT, "select 1", List.of(), 1, true, null, null, 0, "c1", "t"));
        assertThat(recorder.recent()).as("the panel is paused").isEmpty();
        return ((SqlPayload) events.get(0).payload()).frames();
    }

    /** The frames of the call a paused REST Client panel publishes to a journal recording REST client calls. */
    private static ApplicationFrames journalRestClientFrames(boolean captureCallSite) {
        List<RuntimeEvent> events = new ArrayList<>();
        RestClientTraceRecorder recorder =
                new RestClientTraceRecorder(true, false, false, captureCallSite, 8, 500, 2000, 200, 5);
        recorder.setRuntimeEventSink(events::add);
        ApplicationCode.run(() -> recorder.recordNanos(
                "GET",
                "http://pricing/p",
                "pricing",
                "/p",
                200,
                1,
                true,
                null,
                "RestClient",
                Map.of(),
                "t",
                null,
                CorrelationContext.NONE,
                null));
        assertThat(recorder.recent()).as("the panel is paused").isEmpty();
        return ((RestClientPayload) events.get(0).payload()).frames();
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
