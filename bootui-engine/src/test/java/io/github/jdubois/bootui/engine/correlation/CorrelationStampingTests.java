package io.github.jdubois.bootui.engine.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.email.CapturedEmail;
import io.github.jdubois.bootui.engine.email.EmailStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/PLAN-v2.md} §5.1: every recorder stamps BootUI's request id from its {@link CorrelationSource}, the
 * thread scope by default or an adapter-installed provider, and a failing provider never disrupts the recorded work.
 */
class CorrelationStampingTests {

    private static final String REQUEST_ID = "0123456789abcdef";

    @Test
    void theSourceReadsTheThreadScopeByDefaultAndSurvivesAFailingProvider() {
        CorrelationSource source = new CorrelationSource();
        assertThat(source.requestId()).isNull();
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(REQUEST_ID))) {
            assertThat(source.requestId()).isEqualTo(REQUEST_ID);
        }

        source.set(() -> {
            throw new IllegalStateException("tracer down");
        });
        assertThat(source.current()).isSameAs(CorrelationContext.NONE);
        source.set(() -> null);
        assertThat(source.current()).isSameAs(CorrelationContext.NONE);
        source.set(() -> CorrelationContext.forRequest("fedcba9876543210"));
        assertThat(source.requestId()).isEqualTo("fedcba9876543210");
        source.set(null);
        assertThat(source.requestId()).isNull();
    }

    @Test
    void stampsAreKeptPerObjectIdentityAndOnlyWhenARequestOwnsTheWork() {
        RequestIdStamps<Object> stamps = new RequestIdStamps<>();
        Object owned = new Object();
        Object unowned = new Object();

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(REQUEST_ID))) {
            stamps.stamp(owned);
            stamps.stamp(null);
        }
        stamps.stamp(unowned);

        assertThat(stamps.requestId(owned)).isEqualTo(REQUEST_ID);
        assertThat(stamps.requestId(unowned)).isNull();
        assertThat(stamps.requestId(null)).isNull();
    }

    @Test
    void cacheFaultToleranceEmailAndRestCapturesCarryTheCurrentRequestId() {
        CacheActivityRecorder cache = new CacheActivityRecorder(true, 10);
        FaultToleranceEventRecorder faultTolerance = new FaultToleranceEventRecorder(true, 10);
        EmailStore email = new EmailStore(10, 1_000);
        RestClientTraceRecorder rest = restRecorder();

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(REQUEST_ID))) {
            cache.recordHit("cacheManager", "orders", "key");
            faultTolerance.record("rates", "RETRY", "Resilience4j", "rates", "RETRY", 1, 3L, null);
            email.capture(email(), true);
            recordCall(rest);
            assertThat(rest.currentRequestId()).isEqualTo(REQUEST_ID);
        }
        cache.recordMiss("cacheManager", "orders", "key");

        assertThat(cache.recentEvents())
                .extracting(event -> event.requestId())
                .containsExactlyInAnyOrder(REQUEST_ID, null);
        assertThat(faultTolerance.recent())
                .singleElement()
                .extracting(event -> event.requestId())
                .isEqualTo(REQUEST_ID);
        assertThat(email.list())
                .singleElement()
                .extracting(EmailStore.Entry::requestId)
                .isEqualTo(REQUEST_ID);
        assertThat(rest.recent())
                .singleElement()
                .extracting(call -> call.requestId())
                .isEqualTo(REQUEST_ID);
    }

    @Test
    void sqlExceptionsAndRestCallsCarryTheCurrentExecutionId() {
        SqlTraceRecorder sql = new SqlTraceRecorder(true, true, false, false, 10, 100L, 2_048, 256, 5);
        ExceptionStore exceptions = new ExceptionStore(10, 10, 10);
        RestClientTraceRecorder rest = restRecorder();

        try (BootUiCorrelation.Scope ignored =
                BootUiCorrelation.open(CorrelationContext.forExecution("00112233aabbccdd"))) {
            sql.record(
                    SqlTraceRecorder.StatementType.STATEMENT,
                    SqlTraceRecorder.Category.SELECT,
                    "select 1",
                    List.of(),
                    10L,
                    true,
                    null,
                    null,
                    0,
                    "c1",
                    "scheduling-1");
            exceptions.record(new IllegalStateException("boom"), "scheduling-1", null, null, null, "log");
            recordCall(rest);
        }

        assertThat(sql.recent()).singleElement().satisfies(statement -> {
            assertThat(statement.executionId()).isEqualTo("00112233aabbccdd");
            assertThat(statement.requestId()).isNull();
        });
        assertThat(exceptions.groups().get(0).last().executionId()).isEqualTo("00112233aabbccdd");
        assertThat(rest.recent())
                .singleElement()
                .extracting(call -> call.executionId())
                .isEqualTo("00112233aabbccdd");
    }

    @Test
    void sqlAndRestCallsCarryTheKindOfTheThreadTheyStartedOn() {
        SqlTraceRecorder sql = new SqlTraceRecorder(true, true, false, false, 10, 100L, 2_048, 256, 5);
        RestClientTraceRecorder rest = restRecorder();
        sql.setThreadKindClassifier(() -> ThreadKind.EVENT_LOOP);
        rest.setThreadKindClassifier(() -> {
            throw new IllegalStateException("classifier down");
        });

        sql.record(
                SqlTraceRecorder.StatementType.STATEMENT,
                SqlTraceRecorder.Category.SELECT,
                "select 1",
                List.of(),
                10L,
                true,
                null,
                null,
                0,
                "c1",
                "reactor-http-nio-1");
        recordCall(rest);
        rest.record(
                "GET",
                "https://api.example.com/rates",
                "api.example.com",
                "/rates",
                200,
                3L,
                true,
                null,
                "WebClient",
                Map.of(),
                "reactor-http-nio-2",
                null,
                CorrelationContext.NONE,
                ThreadKind.WORKER);

        assertThat(sql.recent())
                .singleElement()
                .extracting(statement -> statement.threadKind())
                .isEqualTo("EVENT_LOOP");
        assertThat(rest.recent())
                .extracting(call -> call.threadKind())
                .as("an explicit start-thread kind wins, and a failing classifier reads OTHER")
                .containsExactlyInAnyOrder("WORKER", "OTHER");
    }

    @Test
    void theDefaultClassifierOnlyKnowsVirtualThreads() {
        assertThat(ThreadKinds.isVirtual(Thread.currentThread())).isFalse();
        assertThat(ThreadKinds.DEFAULT.current()).isEqualTo(ThreadKind.OTHER);
        assertThat(ThreadKinds.isVirtual(null)).isFalse();
    }

    @Test
    void anAdapterProviderReplacesTheThreadScope() {
        CacheActivityRecorder cache = new CacheActivityRecorder(true, 10);
        RestClientTraceRecorder rest = restRecorder();
        cache.setCorrelationContextProvider(() -> CorrelationContext.forRequest(REQUEST_ID));
        rest.setCorrelationContextProvider(() -> {
            throw new IllegalStateException("no Vert.x context");
        });

        cache.recordPut("cacheManager", "orders", "key");
        recordCall(rest);

        assertThat(cache.recentEvents())
                .singleElement()
                .extracting(event -> event.requestId())
                .isEqualTo(REQUEST_ID);
        assertThat(rest.recent())
                .singleElement()
                .extracting(call -> call.requestId())
                .isNull();
    }

    @Test
    void aRestCallRecordedWithAnExplicitRequestIdKeepsIt() {
        RestClientTraceRecorder rest = restRecorder();

        rest.record(
                "GET",
                "https://api.example.com/rates",
                "api.example.com",
                "/rates",
                200,
                3L,
                true,
                null,
                "Quarkus REST Client Reactive",
                Map.of(),
                "vert.x-eventloop-thread-1",
                null,
                REQUEST_ID);

        assertThat(rest.recent())
                .singleElement()
                .extracting(call -> call.requestId())
                .isEqualTo(REQUEST_ID);
    }

    private static RestClientTraceRecorder restRecorder() {
        return new RestClientTraceRecorder(true, true, false, false, 10, 1_000L, 2_048, 256, 5);
    }

    private static void recordCall(RestClientTraceRecorder rest) {
        rest.record(
                "GET",
                "https://api.example.com/rates",
                "api.example.com",
                "/rates",
                200,
                3L,
                true,
                null,
                "RestClient",
                Map.of(),
                Thread.currentThread().getName());
    }

    private static CapturedEmail email() {
        return new CapturedEmail(
                "noreply@example.com",
                List.of("user@example.com"),
                List.of(),
                List.of(),
                "Hi",
                "Hello",
                null,
                List.of());
    }
}
