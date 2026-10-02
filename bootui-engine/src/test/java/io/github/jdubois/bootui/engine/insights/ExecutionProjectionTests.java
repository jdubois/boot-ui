package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationRowDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.RequestTiming;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.ScheduledPayload;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** M3-8: scheduled runs and consumed messages are projected like requests, and the handler names AI and sends. */
class ExecutionProjectionTests {

    private static final long MS = 1_000_000;

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int executions;
    private long clock = 1_000_000_000L;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void aScheduledJobsRepeatedSelectsAreObservedUnderTheJobsNameInRuns() {
        for (int i = 0; i < 3; i++) {
            execution(new ScheduledPayload("OrderJob.run", null), repeatedSelects());
        }
        execution(new MessagingPayload("kafka", false, "orders", false), repeatedSelects());

        RuntimeInsightsService service = service();
        List<RuntimeObservationDto> repeats = service.report().observations().stream()
                .filter(observation -> observation.kind().equals(RepeatedSelects.KIND))
                .toList();

        assertThat(repeats)
                .extracting(RuntimeObservationDto::subject, RuntimeObservationDto::status)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("@Scheduled OrderJob.run", "OBSERVED"),
                        org.assertj.core.groups.Tuple.tuple("consume kafka:orders", "INSUFFICIENT"));
        assertThat(repeats.stream()
                        .filter(o -> o.subject().equals("@Scheduled OrderJob.run"))
                        .findFirst()
                        .orElseThrow()
                        .sentence())
                .endsWith("in 3 of 3 runs, up to 5 times in one.");
        assertThat(repeats.stream()
                        .filter(o -> o.subject().equals("consume kafka:orders"))
                        .findFirst()
                        .orElseThrow()
                        .sentence())
                .contains("1 of 3 messages needed");
        assertThat(service.report().window().requests())
                .as("the window counts HTTP requests")
                .isZero();
        assertThat(service.report().observations())
                .as("observations that read what only a request has ignore executions")
                .noneMatch(observation -> observation.kind().equals(RouteTimeBreakdown.KIND)
                        || observation.kind().equals(SafeMethodDml.KIND));
    }

    @Test
    void theHandlerNamesItsAiCallsAndSynchronousMessageSends() {
        for (int i = 0; i < 6; i++) {
            request(
                    100 * MS,
                    new RequestTiming(clock, -1, 2 * MS, 95 * MS),
                    new Child(
                            JournalSource.AI,
                            40 * MS,
                            new AiPayload("chat", "openai", "gpt-4o", 10L, 5L, "stop", false)),
                    new Child(JournalSource.MESSAGING, 10 * MS, new MessagingPayload("kafka", true, "orders", false)),
                    new Child(JournalSource.MESSAGING, 30 * MS, new MessagingPayload("kafka", false, "orders", false)));
        }

        RuntimeInsightsService service = service();
        RuntimeObservationDto breakdown = service.report().observations().stream()
                .filter(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                .findFirst()
                .orElseThrow();

        assertThat(service.insight(breakdown.id()).rows())
                .extracting(RuntimeObservationRowDto::cells)
                .extracting(cells -> cells.get(0) + "=" + cells.get(3))
                .contains("AI calls=40", "Message sends=10", "Handler, other work=43")
                .as("a consumed message is not the handler's send")
                .noneMatch(cell -> cell.startsWith("Message sends=40"));
        assertThat(breakdown.sentence()).contains("AI calls 40 %");
    }

    private RuntimeInsightsService service() {
        return new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
    }

    private static List<RuntimeEventPayload> repeatedSelects() {
        List<RuntimeEventPayload> statements = new java.util.ArrayList<>();
        statements.add(new SqlPayload("select * from orders", null, "db", false));
        for (int i = 0; i < 5; i++) {
            statements.add(new SqlPayload("select * from lines where order_id = ?", null, "db", false));
        }
        return statements;
    }

    private void execution(RuntimeEventPayload entry, List<RuntimeEventPayload> statements) {
        CorrelationContext context = CorrelationContext.forExecution("x" + (++executions));
        for (RuntimeEventPayload statement : statements) {
            journal.offer(RuntimeEvent.of(JournalSource.SQL, 1_000, MS, context, "job-1", null, false, statement));
        }
        journal.offer(RuntimeEvent.of(
                entry instanceof ScheduledPayload ? JournalSource.SCHEDULED : JournalSource.MESSAGING,
                1_000 + executions,
                20 * MS,
                context,
                "job-1",
                null,
                false,
                entry));
        drain();
    }

    private void request(long durationNanos, RequestTiming timing, Child... children) {
        CorrelationContext context = CorrelationContext.forRequest("r" + (++executions));
        for (Child child : children) {
            journal.offer(RuntimeEvent.of(
                    child.source(), 1_000, child.nanos(), context, "http-1", null, false, child.payload()));
        }
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000 + executions,
                durationNanos,
                context,
                "http-1",
                null,
                false,
                new HttpPayload("POST", "/api/chat", "/api/chat", null, 200, null, timing)));
        clock += 1_000 * MS;
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
