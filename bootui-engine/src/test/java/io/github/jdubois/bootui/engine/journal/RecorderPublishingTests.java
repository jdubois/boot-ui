package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.engine.security.CapturedSecurityEvent;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every recorder publishes what it records to the runtime journal, with the correlation current where it recorded it,
 * and never what the journal must not keep ({@code docs/PLAN-v2.md} §5.2, §8).
 */
class RecorderPublishingTests {

    private static final CorrelationContext REQUEST = CorrelationContext.forRequest("0123456789abcdef");

    private final List<RuntimeEvent> published = new ArrayList<>();

    @Test
    void restClientCallsPublishTheirAuthorityPathAndStatusButNoHeaders() {
        RestClientTraceRecorder recorder = new RestClientTraceRecorder(true, true, true, false, 8, 500, 2000, 200, 5);
        recorder.setRuntimeEventSink(published::add);

        inRequest(() -> recorder.record(
                "GET",
                "http://localhost:8082/api/stock?sku=1",
                "localhost",
                "/api/stock",
                503,
                12,
                false,
                "unavailable",
                "RestClient",
                Map.of("Authorization", "Bearer secret"),
                "http-nio-8080-exec-1"));

        RuntimeEvent event = single(JournalSource.REST_CLIENT);
        assertThat(event.requestId()).isEqualTo(REQUEST.requestId());
        assertThat(event.durationNanos()).isEqualTo(12_000_000L);
        assertThat(event.failedOrSlow()).isTrue();
        assertThat(event.payload())
                .usingRecursiveComparison()
                .ignoringFields("completedNanos")
                .isEqualTo(new RestClientPayload("GET", "localhost:8082", "/api/stock", 503, "RestClient", true));
        assertThat(((RestClientPayload) event.payload()).completedNanos()).isPositive();
    }

    @Test
    void cacheAccessesPublishTheCacheAndOperationButNeverTheKey() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        recorder.setRuntimeEventSink(published::add);

        inRequest(() -> recorder.recordMiss("caffeine", "products", "secret-key"));

        RuntimeEvent event = single(JournalSource.CACHE);
        assertThat(event.requestId()).isEqualTo(REQUEST.requestId());
        assertThat(event.durationNanos()).isEqualTo(-1);
        assertThat(event.payload()).isEqualTo(new CachePayload("products", "MISS"));
    }

    @Test
    void exceptionOccurrencesPublishTheirGroupAndClassButNeverTheirMessage() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.setRuntimeEventSink(published::add);

        inRequest(() -> store.record(
                new IllegalStateException("password=hunter2"), "http-nio-8080-exec-1", "GET", "/boom", null, "test"));

        RuntimeEvent event = single(JournalSource.EXCEPTION);
        assertThat(event.requestId()).isEqualTo(REQUEST.requestId());
        assertThat(event.failedOrSlow()).isTrue();
        ExceptionPayload payload = (ExceptionPayload) event.payload();
        assertThat(payload.exceptionClass()).isEqualTo("java.lang.IllegalStateException");
        assertThat(payload.groupId()).isEqualTo(store.groups().get(0).fingerprint());
    }

    @Test
    void messagesPublishTheirBrokerDirectionAndDestinationButNeverTheirKey() {
        KafkaActivityRecorder kafka = new KafkaActivityRecorder(true, true, 10, 16);
        RabbitActivityRecorder rabbit = new RabbitActivityRecorder(true, true, 10, 16);
        JmsActivityRecorder jms = new JmsActivityRecorder(true, true, 10, 16);
        kafka.setRuntimeEventSink(published::add);
        rabbit.setRuntimeEventSink(published::add);
        jms.setRuntimeEventSink(published::add);

        kafka.recordProduce("orders", 0, "order-42", null, true, null, REQUEST);
        rabbit.recordConsume("events", "order.created", "audit", 3L, false, "boom", "c1");
        jms.recordProduce("queue.mail", "m1", 2L, true, null);

        assertThat(published).hasSize(3);
        assertThat(published.get(0).requestId()).isEqualTo(REQUEST.requestId());
        assertThat(published.get(0).thread())
                .as("the sender's thread is unknown on the I/O thread")
                .isNull();
        assertThat(published.get(0).payload()).isEqualTo(new MessagingPayload("kafka", true, "orders", false));
        assertThat(published.get(1).thread()).isEqualTo(Thread.currentThread().getName());
        assertThat(published.get(1).failedOrSlow()).isTrue();
        assertThat(published.get(1).payload()).isEqualTo(new MessagingPayload("rabbitmq", false, "audit", true));
        assertThat(published.get(2).payload()).isEqualTo(new MessagingPayload("jms", true, "queue.mail", false));
    }

    @Test
    void aConsumedMessageLinksToTheTraceItsExecutionWasOpenedFrom() {
        KafkaActivityRecorder kafka = new KafkaActivityRecorder(true, true, 10, 16);
        JmsActivityRecorder jms = new JmsActivityRecorder(true, true, 10, 16);
        kafka.setRuntimeEventSink(published::add);
        jms.setRuntimeEventSink(published::add);
        CorrelationContext execution =
                CorrelationContext.forExecution("exec-1").withLinkedTraceId("4bf92f3577b34da6a3ce929d0e0e4736");

        kafka.recordConsume("orders", 0, 7L, "k", 3L, true, null, "group", "listener", execution);
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(execution)) {
            jms.recordConsume("queue.mail", "m1", 2L, true, null, null, "listener");
            jms.recordProduce("queue.audit", "m2", 1L, true, null);
        }

        assertThat(published)
                .extracting(event -> ((MessagingPayload) event.payload()).linkedTraceId())
                .containsExactly("4bf92f3577b34da6a3ce929d0e0e4736", "4bf92f3577b34da6a3ce929d0e0e4736", null);
        assertThat(published.get(0).executionId()).isEqualTo("exec-1");
    }

    @Test
    void scheduledRunsPublishTheirTaskExecutionAndFailure() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        store.setRuntimeEventSink(published::add);

        store.record(
                "com.example.Jobs#purge", 1_000, 25, false, "java.io.IOException", "disk", "scheduling-1", "exec-1");

        RuntimeEvent event = single(JournalSource.SCHEDULED);
        assertThat(event.executionId()).isEqualTo("exec-1");
        assertThat(event.requestId()).isNull();
        assertThat(event.epochMillis()).isEqualTo(1_000);
        assertThat(event.durationNanos()).isEqualTo(25_000_000L);
        assertThat(event.failedOrSlow()).isTrue();
        assertThat(event.payload()).isEqualTo(new ScheduledPayload("com.example.Jobs#purge", "java.io.IOException"));
    }

    @Test
    void securityEventsPublishTheirTypeButNeverThePrincipal() {
        SecurityEventBuffer buffer = new SecurityEventBuffer(10);
        buffer.setRuntimeEventSink(published::add);

        buffer.record(new CapturedSecurityEvent(
                Instant.ofEpochMilli(2_000), "alice", "AUTHENTICATION_FAILURE", Map.of(), null, "0123456789abcdef"));

        RuntimeEvent event = single(JournalSource.SECURITY);
        assertThat(event.requestId()).isEqualTo("0123456789abcdef");
        assertThat(event.epochMillis()).isEqualTo(2_000);
        assertThat(event.failedOrSlow()).isTrue();
        assertThat(event.payload()).isEqualTo(new SecurityPayload("AUTHENTICATION_FAILURE"));
        assertThat(SecurityPayload.isFailure("AUTHORIZATION_DENIED")).isTrue();
        assertThat(SecurityPayload.isFailure("AUTHENTICATION_SUCCESS")).isFalse();
    }

    @Test
    void transactionsPublishTheirMethodRollbackAndTheRequestTheyBeganIn() {
        TransactionRecorder recorder = new TransactionRecorder(true, true, 10, 100, 100, null);
        recorder.setRuntimeEventSink(published::add);
        long before = System.nanoTime();

        inRequest(() -> {
            long committed = recorder.beginTransaction("OrderService.place", false, null, "worker-1", null);
            long joined = recorder.beginTransaction("StockService.reserve", false, null, "worker-1", null, true);
            recorder.completeTransaction(joined, TransactionRecorder.Status.COMMITTED, null);
            recorder.completeTransaction(committed, TransactionRecorder.Status.COMMITTED, null);
            long rolledBack = recorder.beginTransaction("OrderService.cancel", false, null, "worker-1", null);
            recorder.completeTransaction(rolledBack, TransactionRecorder.Status.ROLLED_BACK, "boom");
        });

        assertThat(published).hasSize(3).allSatisfy(event -> {
            assertThat(event.source()).isEqualTo(JournalSource.TRANSACTION);
            assertThat(event.requestId()).isEqualTo(REQUEST.requestId());
            assertThat(event.durationNanos()).isNotNegative();
            assertThat(((TransactionPayload) event.payload()).startNanos()).isGreaterThanOrEqualTo(before);
        });
        TransactionPayload joined = (TransactionPayload) published.get(0).payload();
        assertThat(joined.method()).isEqualTo("StockService.reserve");
        assertThat(joined.nested()).isTrue();
        assertThat(joined.savepoint()).isTrue();
        assertThat(joined.independent()).isFalse();
        TransactionPayload place = (TransactionPayload) published.get(1).payload();
        assertThat(place.method()).isEqualTo("OrderService.place");
        assertThat(place.rolledBack()).isFalse();
        assertThat(place.nested()).isFalse();
        assertThat(place.savepoint()).isFalse();
        assertThat(place.independent()).isTrue();
        assertThat(place.startNanos()).isLessThan(joined.startNanos());
        assertThat(published.get(1).failedOrSlow()).isFalse();
        TransactionPayload cancel = (TransactionPayload) published.get(2).payload();
        assertThat(cancel.method()).isEqualTo("OrderService.cancel");
        assertThat(cancel.rolledBack()).isTrue();
        assertThat(published.get(2).failedOrSlow()).isTrue();
    }

    @Test
    void aDisabledRecorderPublishesNothing() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(false, 10);
        recorder.setRuntimeEventSink(published::add);

        recorder.recordHit("caffeine", "products", "k");

        assertThat(published).isEmpty();
    }

    private RuntimeEvent single(JournalSource source) {
        assertThat(published).singleElement().extracting(RuntimeEvent::source).isEqualTo(source);
        return published.get(0);
    }

    private static void inRequest(Runnable work) {
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(REQUEST)) {
            work.run();
        }
    }
}
