package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.SqlPayload.Provenance;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code unbatched-write-loop}: a loop is a shape executed ten times with a batch size of one, and a statement whose
 * batch size is unknown is never part of one. The fixtures of {@link ObservationFixtures} replay the same shapes through
 * the honesty harness.
 */
class UnbatchedWriteLoopTests {

    private static final String INSERT = "insert into audit (order_id) values (?)";

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int requests;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void aLoopOfSingleWritesInTwoRequestsIsReportedWithItsShapeCallSiteAndTime() {
        for (int i = 0; i < 2; i++) {
            request("POST", inTransaction(INSERT, 12));
        }
        request("POST", inTransaction(INSERT, 15));

        RuntimeObservationDto observation = observations().get(0);
        assertThat(observation.status()).isEqualTo("OBSERVED");
        assertThat(observation.subject()).isEqualTo("POST /api/audit");
        assertThat(observation.sentence())
                .isEqualTo("`POST /api/audit` ran `insert into audit (order_id) values (?)` 10 or more times, each"
                        + " without a JDBC batch, in 3 of 3 requests, up to 15 times in one.");
        assertThat(observation.listed()).as("not listed by default").isFalse();
        assertThat(observation.limitations())
                .anyMatch(limitation -> limitation.contains("batch size of one"))
                .anyMatch(limitation -> limitation.contains("within one transaction"));
        assertThat(observation.whatToCheck())
                .anyMatch(check -> check.contains("hibernate.jdbc.batch_size"))
                .anyMatch(check -> check.contains("JdbcTemplate.batchUpdate"));
        RuntimeObservationDetailDto detail = insights().insight(observation.id());
        assertThat(detail.columns()).containsExactly("Request", "Statement", "Executions", "Time (ms)", "Call site");
        assertThat(detail.rows().get(0).cells())
                .containsExactly("r3", "insert into audit (order_id) values (?)", "15", "15", "Repo.run:1");
        assertThat(check().status()).isEqualTo("EVALUATED");
        assertThat(check().reason()).isNull();
    }

    @Test
    void aSingleQualifyingRequestIsInsufficient() {
        request("POST", inTransaction(INSERT, 12));
        request("POST", inTransaction(INSERT, 3));

        assertThat(observations()).singleElement().satisfies(observation -> {
            assertThat(observation.status()).isEqualTo("INSUFFICIENT");
            assertThat(observation.sentence()).contains("1 of 2 requests needed to report");
        });
    }

    @Test
    void aBatchNineSinglesAndAnUnbatchedReadAreNotLoops() {
        for (int i = 0; i < 2; i++) {
            request("POST", writes(INSERT, 1, 12));
            request("PUT", writes(INSERT, 9, 1));
            request("GET", writes("select * from audit where id = ?", 12, 1));
        }

        assertThat(observations()).isEmpty();
    }

    @Test
    void aBatchOfOneIsAnOrdinaryExecutionAndCounts() {
        for (int i = 0; i < 2; i++) {
            request("POST", writes(INSERT, 10, 1));
        }

        assertThat(observations())
                .singleElement()
                .satisfies(observation -> assertThat(observation.status()).isEqualTo("OBSERVED"));
    }

    @Test
    void anExecutedWriteWithUnknownBatchSizeIsNeverCountedAndTheCheckSaysSo() {
        for (int i = 0; i < 2; i++) {
            request("POST", writes(INSERT, 12, 0));
        }

        assertThat(observations()).isEmpty();
        assertThat(check().status()).isEqualTo("INSUFFICIENT");
        assertThat(check().reason())
                .contains("24 executed writes had no recorded batch size")
                .contains("not counted as unbatched");
    }

    @Test
    void unknownBatchSizesDoNotHideTheLoopsAroundThem() {
        for (int i = 0; i < 2; i++) {
            List<Event> events = new ArrayList<>(inTransaction(INSERT, 12));
            events.addAll(writes("update audit set seen = true where id = ?", 3, 0));
            request("POST", events);
        }

        assertThat(observations())
                .singleElement()
                .satisfies(observation -> assertThat(observation.status()).isEqualTo("OBSERVED"));
        assertThat(check().status()).isEqualTo("EVALUATED");
        assertThat(check().reason()).contains("6 executed writes had no recorded batch size");
    }

    @Test
    void preparedWritesAreNeverCountedAndTheCheckSaysHowManyWereLeftOut() {
        for (int i = 0; i < 2; i++) {
            request("POST", prepared(INSERT, 12));
        }

        assertThat(observations()).isEmpty();
        assertThat(check().status()).isEqualTo("INSUFFICIENT");
        assertThat(check().reason())
                .contains("24 write statements were only prepared, not executed")
                .contains("Quarkus")
                .contains("not counted");
    }

    @Test
    void aFailedWriteIsNotCounted() {
        for (int i = 0; i < 2; i++) {
            List<Event> failed = new ArrayList<>();
            for (int j = 0; j < 12; j++) {
                failed.add(new Event(JournalSource.SQL, 1, new SqlPayload(INSERT, "Repo.run:1", "db", true)));
            }
            request("POST", failed);
        }

        assertThat(observations()).isEmpty();
    }

    @Test
    void differentShapesAreCountedApart() {
        for (int i = 0; i < 2; i++) {
            List<Event> both = new ArrayList<>(writes(INSERT, 6, 1));
            both.addAll(writes("insert into orders (customer) values (?)", 6, 1));
            request("POST", both);
        }

        assertThat(observations()).isEmpty();
    }

    @Test
    void literalsDoNotMakeShapesDiffer() {
        for (int i = 0; i < 2; i++) {
            List<Event> events = new ArrayList<>();
            for (int j = 0; j < 12; j++) {
                events.add(sql("insert into audit (order_id) values (" + j + ")", 110 + j, 1));
            }
            events.add(transaction(100, 200));
            request("POST", events);
        }

        assertThat(observations())
                .singleElement()
                .satisfies(observation ->
                        assertThat(observation.sentence()).contains("insert into audit (order_id) values (?)"));
    }

    @Test
    void writesInTheirOwnTransactionsOrSplitAcrossTwoAreNotOneLoop() {
        for (int i = 0; i < 2; i++) {
            List<Event> own = new ArrayList<>();
            for (int j = 0; j < 12; j++) {
                own.add(sql(INSERT, 1_000 + 100L * j, 1));
                own.add(transaction(1_000 + 100L * j - 50, 1_000 + 100L * j + 50));
            }
            request("POST", own);
        }
        for (int i = 0; i < 2; i++) {
            List<Event> split = new ArrayList<>();
            for (int j = 0; j < 12; j++) {
                split.add(sql(INSERT, (j < 6 ? 110 : 310) + j, 1));
            }
            split.add(transaction(100, 200));
            split.add(transaction(300, 400));
            request("PUT", split);
        }

        assertThat(observations()).isEmpty();
    }

    @Test
    void withoutARecordedTransactionTheLoopIsKeptAndTheMissingGroupingIsSaid() {
        for (int i = 0; i < 2; i++) {
            request("POST", writes(INSERT, 12, 1));
        }

        assertThat(observations()).singleElement().satisfies(observation -> {
            assertThat(observation.status()).isEqualTo("OBSERVED");
            assertThat(observation.limitations())
                    .anyMatch(limitation -> limitation.contains("outside any recorded transaction"));
        });
    }

    @Test
    void withoutTheTransactionSourceTheLoopIsKeptAndTheMissingGroupingIsSaid() {
        try (RuntimeJournal withoutTransactions = new RuntimeJournal(
                new RuntimeJournalSettings(
                        true,
                        10_000,
                        50_000_000,
                        10_000,
                        10,
                        10,
                        java.util.EnumSet.complementOf(java.util.EnumSet.of(JournalSource.TRANSACTION))),
                RunIdentity.start())) {
            for (int i = 0; i < 2; i++) {
                requestTo(withoutTransactions, "POST", writes(INSERT, 12, 1));
            }
            RuntimeInsightsService service =
                    new RuntimeInsightsService(withoutTransactions, null, null, InsightsStack.QUARKUS, null);

            assertThat(service.report().observations())
                    .filteredOn(observation -> observation.kind().equals(UnbatchedWriteLoop.KIND))
                    .singleElement()
                    .satisfies(observation -> {
                        assertThat(observation.status()).isEqualTo("OBSERVED");
                        assertThat(observation.limitations())
                                .anyMatch(limitation -> limitation.contains("Transactions are not recorded"));
                    });
        }
    }

    @Test
    void aStatementThatCannotBePlacedKeepsTheLoopWithoutGroupingIt() {
        for (int i = 0; i < 2; i++) {
            List<Event> events = new ArrayList<>(writes(INSERT, 12, 1));
            events.add(transaction(100, 200));
            request("POST", events);
        }

        assertThat(observations())
                .singleElement()
                .satisfies(observation -> assertThat(observation.limitations())
                        .anyMatch(limitation -> limitation.contains("no monotonic time")));
    }

    @Test
    void twoRoutesAreReportedApart() {
        for (int i = 0; i < 2; i++) {
            request("POST", inTransaction(INSERT, 12));
            request("DELETE", inTransaction("delete from audit where id = ?", 11));
        }

        assertThat(observations())
                .extracting(RuntimeObservationDto::subject)
                .containsExactlyInAnyOrder("POST /api/audit", "DELETE /api/audit");
    }

    @Test
    void thePayloadSaysUnknownForEveryPublisherThatDoesNotKnow() {
        assertThat(new SqlPayload("select 1", null, "db", false).batchSize()).isEqualTo(1);
        assertThat(new SqlPayload("insert", null, "db", false, null, null, 5, 0L, Provenance.PREPARATION).batchSize())
                .isZero();
        assertThat(new SqlPayload("insert", null, "db", false, null, null, 5, 0L, Provenance.UNKNOWN, 7).batchSize())
                .as("only an execution knows its batch size")
                .isZero();
        assertThat(new SqlPayload("insert", null, "db", false, null, null, 5, 0L, Provenance.EXECUTION, 7).batchSize())
                .isEqualTo(7);
    }

    private static List<Event> inTransaction(String statement, int count) {
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(sql(statement, 110L + i, 1));
        }
        events.add(transaction(100, 200));
        return events;
    }

    private static List<Event> writes(String statement, int count, int batchSize) {
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(new Event(
                    JournalSource.SQL,
                    1_000_000,
                    new SqlPayload(
                            statement,
                            "Repo.run:1",
                            "db",
                            false,
                            null,
                            null,
                            -1,
                            0L,
                            Provenance.EXECUTION,
                            batchSize)));
        }
        return events;
    }

    private static List<Event> prepared(String statement, int count) {
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(new Event(
                    JournalSource.SQL,
                    0,
                    new SqlPayload(statement, "Repo.run:1", "db", false, null, null, -1, 0L, Provenance.PREPARATION)));
        }
        return events;
    }

    private static Event sql(String statement, long completedNanos, int batchSize) {
        return new Event(
                JournalSource.SQL,
                1_000_000,
                new SqlPayload(
                        statement,
                        "Repo.run:1",
                        "db",
                        false,
                        null,
                        RequestPhase.HANDLER,
                        completedNanos,
                        0L,
                        Provenance.EXECUTION,
                        batchSize));
    }

    private static Event transaction(long startNanos, long endNanos) {
        return new Event(
                JournalSource.TRANSACTION,
                endNanos - startNanos,
                new TransactionPayload("AuditService.log", false, false, false, startNanos));
    }

    private void request(String method, List<Event> children) {
        requestTo(journal, method, children);
    }

    private void requestTo(RuntimeJournal target, String method, List<Event> children) {
        CorrelationContext context = CorrelationContext.forRequest("r" + (++requests));
        for (Event child : children) {
            target.offer(
                    RuntimeEvent.of(child.source, 1_000, child.nanos, context, "http-1", null, false, child.payload));
        }
        target.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000 + requests,
                100_000_000,
                context,
                "http-1",
                null,
                false,
                new HttpPayload(method, "/api/audit", "/api/audit", null, 200)));
        try {
            assertThat(target.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private RuntimeInsightsService insights() {
        return new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
    }

    private List<RuntimeObservationDto> observations() {
        return insights().report().observations().stream()
                .filter(observation -> observation.kind().equals(UnbatchedWriteLoop.KIND))
                .toList();
    }

    private RuntimeInsightCheckDto check() {
        return insights().report().checks().stream()
                .filter(candidate -> candidate.kind().equals(UnbatchedWriteLoop.KIND))
                .findFirst()
                .orElseThrow();
    }

    private record Event(JournalSource source, long nanos, RuntimeEventPayload payload) {}
}
