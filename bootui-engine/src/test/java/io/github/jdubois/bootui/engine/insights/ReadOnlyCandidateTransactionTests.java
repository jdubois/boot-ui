package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
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

/**
 * {@code readonly-candidate-transaction}: a read-write boundary is reported only when every execution of it in the
 * window was clean, and what could not be judged is named rather than reported. The fixtures of {@link
 * ObservationFixtures} replay the same shapes through the honesty harness.
 */
class ReadOnlyCandidateTransactionTests {

    private static final String METHOD = "com.example.TagService.summary";

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int requests;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void aBoundaryWhoseEveryExecutionWasCleanIsReportedWithItsMethodAndExecutionsButNotListedByDefault() {
        for (int i = 0; i < 4; i++) {
            request("GET", transaction(false, 2, 1, 0), select(120), select(150));
        }

        assertThat(observations()).singleElement().satisfies(observation -> {
            assertThat(observation.subject()).isEqualTo(METHOD);
            assertThat(observation.status()).isEqualTo("OBSERVED");
            assertThat(observation.sentence())
                    .isEqualTo("`" + METHOD + "` ran 4 read-write transactions that changed no entity and issued no"
                            + " write.");
            assertThat(observation.listed()).isFalse();
            assertThat(observation.unlistedReason()).isNotBlank();
            assertThat(observation.whatToCheck())
                    .anyMatch(check -> check.contains("@Transactional(readOnly = true)"))
                    .anyMatch(check -> check.contains("Keep it read-write"));
            assertThat(observation.limitations()).anyMatch(limitation -> limitation.contains("retained window"));
        });
        assertThat(check().status()).isEqualTo("EVALUATED");
        assertThat(check().reason()).isNull();
    }

    @Test
    void fewerThanThreeExecutionsIsInsufficient() {
        for (int i = 0; i < 2; i++) {
            request("GET", transaction(false, 2, 1, 0), select(120), select(150));
        }

        assertThat(observations()).singleElement().satisfies(observation -> {
            assertThat(observation.status()).isEqualTo("INSUFFICIENT");
            assertThat(observation.sentence()).endsWith("Reported from 3 transactions.");
        });
    }

    @Test
    void aWriteInAnyExecutionKeepsTheBoundaryOutWithoutCallingItUncounted() {
        for (int i = 0; i < 3; i++) {
            request("GET", transaction(false, 2, 1, 0), select(120), select(150));
        }
        request("POST", transaction(false, 2, 1, 0), select(120), sql("insert into links values (1)", 150));

        assertThat(observations()).isEmpty();
        assertThat(check().reason()).as("a write is a judgement, not a gap").isNull();
    }

    @Test
    void anEntityTheTransactionChangedKeepsTheBoundaryOut() {
        for (int i = 0; i < 3; i++) {
            request(
                    "POST",
                    transaction(false, 3, 1, 1),
                    select(120),
                    sql("update tags set label = ?", 150),
                    select(160));
        }

        assertThat(observations()).isEmpty();
    }

    @Test
    void aWriteInAnExecutionWithoutHibernateStillKeepsTheBoundaryOut() {
        for (int i = 0; i < 3; i++) {
            request("GET", transaction(false, 2, 1, 0), select(120), select(150));
        }
        request("POST", transaction(false, 0, 0, 0), sql("update tags set label = ?", 150));

        assertThat(observations()).isEmpty();
    }

    @Test
    void aNestedRunOfTheMethodThatWroteKeepsTheBoundaryOut() {
        for (int i = 0; i < 3; i++) {
            request("GET", transaction(false, 2, 1, 0), select(120), select(150));
        }
        request(
                "POST",
                new Event(
                        JournalSource.TRANSACTION,
                        100,
                        new TransactionPayload(METHOD, false, true, false, 100, false, null, false, null, 1, 1, 0)),
                sql("update tags set label = ?", 150));

        assertThat(observations()).isEmpty();
    }

    @Test
    void whatCannotBeJudgedIsCountedAndNeverReported() {
        for (int i = 0; i < 3; i++) {
            request("GET", transaction(false, 2, 1, 0), select(120), select(150));
        }
        // Hibernate loaded nothing it could dirty-check, so nothing says the method only reads.
        request("GET", transaction(false, 1, 0, 0), select(120));
        // A statement without a monotonic time may have been a write.
        request(
                "GET",
                transaction(false, 1, 1, 0),
                new Event(JournalSource.SQL, 1, new SqlPayload("select 1", null, "db", false)));
        // Fewer statements recorded than Hibernate executed: one of them is missing.
        request("GET", transaction(false, 3, 1, 0), select(120));
        // It rolled back.
        request("GET", transaction(true, 1, 1, 0), select(120));

        assertThat(observations()).isEmpty();
        assertThat(check().reason()).contains("4 executions of read-write transactions could not be judged");
    }

    @Test
    void aNestedRunOfTheMethodThatCouldNotBeJudgedKeepsTheBoundaryOutAndIsCounted() {
        for (int i = 0; i < 3; i++) {
            request("GET", transaction(false, 2, 1, 0), select(120), select(150));
        }
        // A REQUIRES_NEW run of the same method that rolled back: no evidence, and not nothing.
        request(
                "GET",
                new Event(
                        JournalSource.TRANSACTION,
                        100,
                        new TransactionPayload(METHOD, true, true, false, 100, false, null, false, null, 1, 0, 0)),
                select(120));

        assertThat(observations()).isEmpty();
        assertThat(check().reason()).contains("1 execution of read-write transactions could not be judged");
    }

    @Test
    void anOuterTransactionThatHoldsAnotherOrARolledBackSavepointIsNotJudgedFromTheMixedWork() {
        for (int i = 0; i < 3; i++) {
            request("GET", transaction(false, 2, 1, 0), select(120), select(150));
        }
        // The outer method, with a REQUIRES_NEW audit write inside it, whose dirty entity is in the outer's thread
        // delta.
        request(
                "POST",
                new Event(
                        JournalSource.TRANSACTION,
                        50,
                        new TransactionPayload(
                                "com.example.Audit.log", false, true, false, 130, false, null, false, null, 1, 1, 1)),
                new Event(
                        JournalSource.TRANSACTION,
                        100,
                        new TransactionPayload(
                                "com.example.Outer.run", false, false, false, 100, false, null, false, null, 2, 2, 1)),
                select(120));
        // The outer method whose savepoint rolled back before its write.
        request(
                "GET",
                new Event(
                        JournalSource.TRANSACTION,
                        20,
                        new TransactionPayload(
                                "com.example.Outer.run", true, false, true, 130, false, null, false, null, 0, 0, 0)),
                transaction(false, 2, 1, 0),
                select(120),
                select(150));

        assertThat(observations())
                .as("the audit method wrote; the outer is mixed with it")
                .isEmpty();
        assertThat(check().reason()).contains("could not be judged");
    }

    @Test
    void whenNothingCouldBeJudgedTheCheckSaysSoInsteadOfNoEligibleWork() {
        for (int i = 0; i < 3; i++) {
            request("GET", transaction(false, 1, 0, 0), select(120));
        }

        assertThat(observations()).isEmpty();
        assertThat(check().status()).isEqualTo("EVALUATED");
        assertThat(check().reason())
                .contains("3 executions of read-write transactions could not be judged")
                .doesNotContain("No eligible work");
    }

    @Test
    void aStatementShortenedByTheRecorderIsNotReadableSoItsTransactionIsNotJudged() {
        for (int i = 0; i < 3; i++) {
            request("GET", transaction(false, 2, 1, 0), select(120), sql("select * from tags where id in (…", 150));
        }

        assertThat(observations()).isEmpty();
        assertThat(check().reason()).contains("3 executions of read-write transactions could not be judged");
    }

    @Test
    void aMethodWithoutAnameIsNeverReported() {
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    new Event(
                            JournalSource.TRANSACTION,
                            100,
                            new TransactionPayload(
                                    "unknown", false, false, false, 100, false, null, false, null, 2, 1, 0)),
                    select(120),
                    select(150));
        }

        assertThat(observations()).isEmpty();
    }

    @Test
    void aMethodWithoutHibernateWorkAndAnUnrecordedTransactionAreNotGapsOfAnyMethod() {
        for (int i = 0; i < 3; i++) {
            request("GET", transaction(false, 0, 0, 0), select(120));
            request(
                    "GET",
                    new Event(
                            JournalSource.TRANSACTION,
                            100,
                            new TransactionPayload("com.example.Other.run", false, false, false, 100)),
                    select(120));
        }

        assertThat(observations()).isEmpty();
        assertThat(check().reason()).doesNotContain("could not be judged");
    }

    @Test
    void aReadOnlyTransactionIsNotACandidate() {
        for (int i = 0; i < 3; i++) {
            request("GET", transaction(false, 2, 1, 0, true), select(120), select(150));
        }

        assertThat(observations()).isEmpty();
    }

    @Test
    void quarkusRecordsNoTransactionsSoItsCheckIsNotApplicable() {
        RuntimeInsightCheckDto check = new RuntimeInsightsService(journal, null, null, InsightsStack.QUARKUS, null)
                .report().checks().stream()
                        .filter(candidate -> candidate.kind().equals(ReadOnlyCandidateTransaction.KIND))
                        .findFirst()
                        .orElseThrow();

        assertThat(check.status()).isEqualTo("NOT_APPLICABLE");
        assertThat(check.reason()).contains("Quarkus");
    }

    @Test
    void onlyAQueryOrSessionControlWithoutAWriteWordIsRead() {
        assertThat(List.of(
                        "select * from tags",
                        "  SELECT t.* FROM tags t WHERE t.updated_at > ?",
                        "/* page */ select * from tags",
                        "-- audit\nselect 1",
                        "(select 1) union (select 2)",
                        "with recent as (select * from tags) select * from recent",
                        "values (1)",
                        "set session x = 1",
                        "savepoint s1"))
                .noneMatch(ReadOnlyCandidateTransaction::mayWrite);
        assertThat(List.of(
                        "insert into tags values (1)",
                        "update tags set label = ?",
                        "delete from tags",
                        "merge into tags using x on y",
                        "select * from tags for update",
                        "select * into backup from tags",
                        "with moved as (delete from tags returning *) select * from moved",
                        "{call record_view(?)}",
                        "/* audit */ call record_view(?)",
                        "/* unterminated select 1",
                        "truncate table tags",
                        "explain analyze select 1",
                        "select nextval('tags_seq')",
                        "select setval('tags_seq', 5)",
                        "select next value for tags_seq",
                        "select * from tags for share",
                        "select * from tags lock in share mode",
                        "begin pkg.record_view(); end;",
                        "select 1; call record_view(?)",
                        "set x = 1; truncate table tags",
                        "",
                        "   "))
                .allMatch(ReadOnlyCandidateTransaction::mayWrite);
        assertThat(ReadOnlyCandidateTransaction.mayWrite("select 1;"))
                .as("a trailing semicolon")
                .isFalse();
        assertThat(ReadOnlyCandidateTransaction.mayWrite(null)).isTrue();
    }

    private void request(String method, Event... children) {
        CorrelationContext context = CorrelationContext.forRequest("r" + (++requests));
        for (Event child : children) {
            journal.offer(
                    RuntimeEvent.of(child.source, 1_000, child.nanos, context, "http-1", null, false, child.payload));
        }
        journal.offer(RuntimeEvent.of(
                JournalSource.ORM,
                1_000,
                80_000_000,
                context,
                "http-1",
                null,
                false,
                new OrmPayload(null, 2, 0, 1, 0, 1, 0, 0, 0, 0, 5, 0, 0, 0)));
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000 + requests,
                100_000_000,
                context,
                "http-1",
                null,
                false,
                new HttpPayload(method, "/api/tags/summary", "/api/tags/summary", null, 200)));
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static Event transaction(boolean rolledBack, long statements, long flushes, long dirty) {
        return transaction(rolledBack, statements, flushes, dirty, false);
    }

    private static Event transaction(boolean rolledBack, long statements, long flushes, long dirty, boolean readOnly) {
        return new Event(
                JournalSource.TRANSACTION,
                100,
                new TransactionPayload(
                        METHOD,
                        rolledBack,
                        false,
                        false,
                        100,
                        readOnly,
                        null,
                        false,
                        null,
                        statements,
                        flushes,
                        dirty));
    }

    private static Event select(long completedNanos) {
        return sql("select * from tags", completedNanos);
    }

    private static Event sql(String statement, long completedNanos) {
        return new Event(
                JournalSource.SQL,
                1,
                new SqlPayload(statement, "Repo.run:1", "db", false, null, RequestPhase.HANDLER, completedNanos));
    }

    private List<RuntimeObservationDto> observations() {
        return new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null)
                .report().observations().stream()
                        .filter(observation -> observation.kind().equals(ReadOnlyCandidateTransaction.KIND))
                        .toList();
    }

    private RuntimeInsightCheckDto check() {
        return new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null)
                .report().checks().stream()
                        .filter(candidate -> candidate.kind().equals(ReadOnlyCandidateTransaction.KIND))
                        .findFirst()
                        .orElseThrow();
    }

    private record Event(JournalSource source, long nanos, RuntimeEventPayload payload) {}
}
