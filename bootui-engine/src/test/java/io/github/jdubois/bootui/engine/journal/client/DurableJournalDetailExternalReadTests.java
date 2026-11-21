package io.github.jdubois.bootui.engine.journal.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.journal.DurableJournalDetail;
import io.github.jdubois.bootui.engine.journal.DurableJournalDetailKind;
import io.github.jdubois.bootui.engine.journal.DurableJournalRun;
import io.github.jdubois.bootui.engine.journal.DurableJournalScope;
import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.ExceptionStoreDurableJournalDetailProvider;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayloadDurableJournalDetailProvider;
import io.github.jdubois.bootui.engine.journal.JdbcDurableJournalStore;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.ThrowableMarks;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.util.ArrayList;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class DurableJournalDetailExternalReadTests {

    @Test
    void externallyReadsExceptionMetadataWithoutMessagesThrowableOrMarks() {
        ExceptionStore exceptions = new ExceptionStore(10, 10, 50);
        exceptions.setApplicationPackages(List.of(getClass().getPackageName()));
        List<RuntimeEvent> events = new ArrayList<>();
        exceptions.setRuntimeEventSink(events::add);
        ThrowableMarks.retain();
        try {
            exceptions.record(
                    new IllegalStateException(
                            "top-secret throwable message", new IllegalArgumentException("nested secret")),
                    "http-worker",
                    "POST",
                    "/orders/42?token=raw",
                    "OrderController.create",
                    "handler",
                    "trace-1");
        } finally {
            ThrowableMarks.release();
        }
        RuntimeEvent event = events.get(0);
        ExceptionPayload payload = (ExceptionPayload) event.payload();
        assertThat(payload.marks()).isNotNull();
        JournalEntry entry = new JournalEntry(7, event, 256);
        DurableJournalScope scope = scope();
        ExceptionStoreDurableJournalDetailProvider provider =
                new ExceptionStoreDurableJournalDetailProvider(exceptions);

        DurableJournalDetail detail = provider.details(scope, List.of(entry)).get(0);
        JdbcDurableJournalStore store = persisted(detail, entry);

        assertThat(store.listDetails(scope)).singleElement().satisfies(read -> {
            assertThat(read.kind()).isEqualTo(DurableJournalDetailKind.EXCEPTION);
            assertThat(read.journalSequence()).isEqualTo(7);
            assertThat(read.exceptionClass()).isEqualTo(IllegalStateException.class.getName());
            assertThat(read.signature()).isEqualTo(payload.signature());
            assertThat(read.types()).contains(IllegalStateException.class.getName());
            assertThat(read.applicationFrames())
                    .anySatisfy(frame -> assertThat(frame).contains("ExternalReadTests"));
            assertThat(read.causes()).containsExactly(IllegalArgumentException.class.getName());
            assertThat(read.occurrences()).isOne();
            assertThat(read.toString())
                    .doesNotContain(
                            "top-secret throwable message",
                            "nested secret",
                            "/orders/42",
                            "token=raw",
                            "ThrowableMarks");
        });
    }

    @Test
    void omittedExceptionDetailStaysOmittedAfterGroupEviction() {
        ExceptionStore exceptions = new ExceptionStore(1, 10, 50);
        List<RuntimeEvent> events = new ArrayList<>();
        exceptions.setRuntimeEventSink(events::add);
        exceptions.record(
                new IllegalStateException("first secret"), "worker", "GET", "/first", "First.handler", "handler", null);
        JournalEntry evicted = new JournalEntry(1, events.get(0), 256);
        exceptions.record(
                new IllegalArgumentException("second secret"),
                "worker",
                "GET",
                "/second",
                "Second.handler",
                "handler",
                null);

        assertThat(new ExceptionStoreDurableJournalDetailProvider(exceptions).details(scope(), List.of(evicted)))
                .isEmpty();
    }

    @Test
    void externallyReadsRequestRouteMetadataButNeverTheConcretePath() {
        DurableJournalScope scope = scope();
        JournalEntry entry = new JournalEntry(
                9,
                new RuntimeEvent(
                        JournalSource.HTTP,
                        10,
                        1,
                        "request-1",
                        "execution",
                        "trace",
                        "worker",
                        ThreadKind.WORKER,
                        false,
                        new HttpPayload(
                                "GET", "/orders/123?token=raw", "/orders/{id}", "readOrder password=hunter2", 204)),
                256);

        DurableJournalDetail detail = new HttpPayloadDurableJournalDetailProvider()
                .details(scope, List.of(entry))
                .get(0);
        JdbcDurableJournalStore store = persisted(detail, entry);

        assertThat(store.listDetails(scope)).singleElement().satisfies(read -> {
            assertThat(read.requestMethod()).isEqualTo("GET");
            assertThat(read.routeTemplate()).isEqualTo("/orders/{id}");
            assertThat(read.operation()).doesNotContain("hunter2");
            assertThat(read.status()).isEqualTo(204);
            assertThat(read.toString()).doesNotContain("/orders/123", "token=raw", "hunter2", "execution", "trace");
        });
    }

    private static DurableJournalScope scope() {
        return new DurableJournalScope("local", "orders", "run-1");
    }

    private static JdbcDurableJournalStore persisted(DurableJournalDetail detail, JournalEntry entry) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:external-detail-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource, "durable");
        DurableJournalScope scope = detail.scope();
        store.upsertRun(new DurableJournalRun(scope, 1, 1, "RUNNING", null, 0));
        store.append(scope, entry);
        store.upsertDetail(detail);
        return store;
    }
}
