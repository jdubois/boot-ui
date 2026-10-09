package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AppEventPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** M4-8: {@code transactional-listener-skipped} and {@code after-commit-writes}, each with its counterexample. */
class AppEventObservationsTests {

    private static final long MS = 1_000_000;
    private static final String PLACED = "com.example.OrderPlaced";

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int requests;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void aListenerSkippedForLackOfATransactionIsReportedButOneDeferredAndRunIsNot() {
        request(
                "/api/orders/import",
                AppEventPayload.published(PLACED, 1),
                AppEventPayload.listener(
                        PLACED,
                        "OrderListener#onPlaced",
                        "AFTER_COMMIT",
                        AppEventPayload.SKIPPED_NO_TRANSACTION,
                        null,
                        -1));
        request(
                "/api/orders",
                AppEventPayload.published(PLACED, 1),
                AppEventPayload.listener(
                        PLACED, "OrderListener#onPlaced", "AFTER_COMMIT", AppEventPayload.DEFERRED, null, -1),
                AppEventPayload.listener(
                        PLACED, "OrderListener#onPlaced", "AFTER_COMMIT", AppEventPayload.RAN, null, 10));

        List<RuntimeObservationDto> skipped = observations(TransactionalListenerSkipped.KIND);

        assertThat(skipped).singleElement().satisfies(observation -> {
            assertThat(observation.subject()).isEqualTo("POST /api/orders/import");
            assertThat(observation.sentence())
                    .isEqualTo("`POST /api/orders/import` published `OrderPlaced` with no transaction active in 1 of"
                            + " 1 request, so its transactional listener `OrderListener#onPlaced` (AFTER_COMMIT)"
                            + " never ran.");
        });
    }

    @Test
    void aWriteInAnAfterCommitListenerIsReportedButNotOneInItsOwnTransactionNorAnImmediateListener() {
        long start = 1_000 * MS;
        offer(
                "r1",
                new Child(
                        JournalSource.TRANSACTION,
                        20 * MS,
                        new TransactionPayload("OrderService.ship", false, false, false, start)),
                new Child(
                        JournalSource.APP_EVENT,
                        10 * MS,
                        AppEventPayload.listener(
                                PLACED,
                                "AuditListener#onShipped",
                                "AFTER_COMMIT",
                                AppEventPayload.RAN,
                                null,
                                start + 15 * MS)),
                new Child(JournalSource.SQL, MS, sql("insert into audit values (?)", start + 18 * MS)),
                new Child(
                        JournalSource.TRANSACTION,
                        3 * MS,
                        new TransactionPayload("AuditService.record", false, false, false, start + 20 * MS)),
                new Child(JournalSource.SQL, MS, sql("update audit set done = 1", start + 22 * MS)),
                new Child(
                        JournalSource.APP_EVENT,
                        5 * MS,
                        AppEventPayload.listener(
                                PLACED,
                                "MailListener#onShipped",
                                AppEventPayload.IMMEDIATE,
                                AppEventPayload.RAN,
                                null,
                                start + 1 * MS)),
                new Child(JournalSource.SQL, MS, sql("insert into outbox values (?)", start + 3 * MS)));

        List<RuntimeObservationDto> writes = observations(AfterCommitWrites.KIND);

        assertThat(writes).singleElement().satisfies(observation -> {
            assertThat(observation.sentence())
                    .isEqualTo("`POST /api/orders/{id}/ship`'s AFTER_COMMIT listener `AuditListener#onShipped` ran 1"
                            + " write in 1 of 1 request after its transaction completed, in no transaction of its"
                            + " own.");
            assertThat(observation.whatToCheck().get(1)).contains("REQUIRES_NEW");
            assertThat(observation.whatToCheck().get(0)).contains("timing alone does not prove");
        });
    }

    @Test
    void aNoTransactionFallbackWriteIsNotEvidenceThatATransactionCompleted() {
        long start = 1_000 * MS;
        offer(
                "fallback",
                new Child(
                        JournalSource.APP_EVENT,
                        5 * MS,
                        AppEventPayload.listener(
                                PLACED,
                                "AuditListener#fallback",
                                AppEventPayload.IMMEDIATE,
                                AppEventPayload.RAN,
                                null,
                                start)),
                new Child(JournalSource.SQL, MS, sql("insert into audit values (?)", start + 2 * MS)));

        assertThat(observations(AfterCommitWrites.KIND)).isEmpty();
        assertThat(observations(TransactionalListenerSkipped.KIND)).isEmpty();
    }

    @Test
    void neitherObservationAppliesOnQuarkusWhereNoListenerIsSkippedAndNoTransactionIsRecorded() {
        RuntimeInsightsService service = new RuntimeInsightsService(journal, null, null, InsightsStack.QUARKUS, null);
        request("/api/orders", AppEventPayload.published(PLACED, 0));

        assertThat(service.report().checks())
                .filteredOn(check -> check.kind().equals(TransactionalListenerSkipped.KIND)
                        || check.kind().equals(AfterCommitWrites.KIND))
                .extracting(RuntimeInsightCheckDto::status)
                .containsOnly("NOT_APPLICABLE");
    }

    private List<RuntimeObservationDto> observations(String kind) {
        return new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null)
                .report().observations().stream()
                        .filter(observation -> observation.kind().equals(kind))
                        .toList();
    }

    private static SqlPayload sql(String statement, long completedNanos) {
        return new SqlPayload(statement, null, "db", false, null, null, completedNanos);
    }

    private void request(String path, AppEventPayload... events) {
        String requestId = "r" + (++requests) + "x";
        CorrelationContext context = CorrelationContext.forRequest(requestId);
        for (AppEventPayload event : events) {
            journal.offer(RuntimeEvent.of(JournalSource.APP_EVENT, 1_000, MS, context, "http-1", null, false, event));
        }
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000,
                30 * MS,
                context,
                "http-1",
                null,
                false,
                new HttpPayload("POST", path, path, null, 200)));
        drain();
    }

    private void offer(String requestId, Child... children) {
        CorrelationContext context = CorrelationContext.forRequest(requestId);
        for (Child child : children) {
            journal.offer(RuntimeEvent.of(
                    child.source(), 1_000, child.nanos(), context, "http-1", null, false, child.payload()));
        }
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000,
                40 * MS,
                context,
                "http-1",
                null,
                false,
                new HttpPayload("POST", "/api/orders/7/ship", "/api/orders/{id}/ship", null, 200)));
        drain();
    }

    private void drain() {
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private record Child(JournalSource source, long nanos, RuntimeEventPayload payload) {}
}
