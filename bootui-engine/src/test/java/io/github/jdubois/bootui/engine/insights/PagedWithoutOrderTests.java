package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code paged-without-order}: an executed SELECT that limits its rows without an {@code ORDER BY} is reported per
 * route once two requests ran it, and what could not be judged is named in the check's reason rather than reported.
 * The fixtures of {@link ObservationFixtures} replay the same shapes through the honesty harness.
 */
class PagedWithoutOrderTests {

    private static final String PAGE = "select o1_0.id,o1_0.name from owner o1_0 limit ? offset ?";

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int requests;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void anUnorderedPageIsReportedPerRouteWithItsExecutionsAndRequestsButNotListedByDefault() {
        for (int i = 0; i < 4; i++) {
            request("/api/owners", execution(PAGE), execution(PAGE), execution(PAGE));
        }

        assertThat(observations()).singleElement().satisfies(observation -> {
            assertThat(observation.subject()).isEqualTo("GET /api/owners");
            assertThat(observation.status()).isEqualTo("OBSERVED");
            assertThat(observation.sentence())
                    .isEqualTo("`GET /api/owners` ran a statement that limits rows without an `ORDER BY`,"
                            + " 12 times in 4 requests.");
            RuntimeObservationDetailDto detail = service().insight(observation.id());
            assertThat(detail.columns()).containsExactly("Request", "Executions", "Call site", "Statement");
            assertThat(detail.rows()).hasSize(4);
            assertThat(detail.rows().get(0).cells())
                    .containsExactly(
                            detail.rows().get(0).cells().get(0),
                            "3",
                            "Repo.run:1",
                            "select o1_0.id,o1_0.name from owner o1_0 limit ? offset ?");
            assertThat(observation.whatToCheck()).anyMatch(check -> check.contains("ORDER BY"));
            assertThat(observation.limitations())
                    .anyMatch(limitation -> limitation.contains("key lookup"))
                    .anyMatch(limitation -> limitation.contains("arbitrary"));
            assertThat(observation.listed()).isFalse();
            assertThat(observation.unlistedReason()).isNotBlank();
        });
        assertThat(check().status()).isEqualTo("EVALUATED");
        assertThat(check().reason()).isNull();
    }

    @Test
    void oneRequestIsInsufficient() {
        request("/api/owners", execution(PAGE), execution(PAGE));

        assertThat(observations()).singleElement().satisfies(observation -> {
            assertThat(observation.status()).isEqualTo("INSUFFICIENT");
            assertThat(observation.sentence())
                    .isEqualTo("`GET /api/owners`: 1 of 2 requests needed to report a statement that limits rows"
                            + " without an `ORDER BY`.");
        });
    }

    @Test
    void aSortedPageIsNotReported() {
        for (int i = 0; i < 3; i++) {
            request("/api/owners", execution("select id from owner order by id limit ? offset ?"));
        }

        assertThat(observations()).isEmpty();
    }

    @Test
    void aStatementWhoseOrderByIsOnlyInASubqueryIsReported() {
        for (int i = 0; i < 2; i++) {
            request("/api/owners", execution("select * from (select id from owner order by id) o limit ? offset ?"));
        }

        assertThat(observations()).singleElement().satisfies(observation -> {
            assertThat(observation.status()).isEqualTo("OBSERVED");
        });
    }

    @Test
    void aKeyLookupWithOnlyEqualitiesIsNotReportedButAnOffsetOrAnOrFilterIs() {
        for (int i = 0; i < 2; i++) {
            request("/api/lookup", execution("select c1_0.id from c c1_0 where c1_0.a=? and c1_0.b=? limit ?"));
            request("/api/offset", execution("select c1_0.id from c c1_0 where c1_0.a=? limit ? offset ?"));
            request("/api/or", execution("select c1_0.id from c c1_0 where c1_0.a=? or c1_0.b=? limit ?"));
        }

        assertThat(observations())
                .extracting(RuntimeObservationDto::subject)
                .containsExactlyInAnyOrder("GET /api/offset", "GET /api/or");
    }

    @Test
    void countsExistsAndLiteralLimitOfOneAreNotReported() {
        for (int i = 0; i < 3; i++) {
            request(
                    "/api/owners",
                    execution("select count(*) from owner limit ?"),
                    execution("select 1 from owner where id > ? limit ?"),
                    execution("select id from owner where id > ? limit 1"));
        }

        assertThat(observations()).isEmpty();
        assertThat(check().reason()).isNull();
    }

    @Test
    void statementsThatCannotBeReadAreNamedInTheReasonAndNeverReported() {
        for (int i = 0; i < 3; i++) {
            request(
                    "/api/owners",
                    execution("select id from owner where name like ? limit ? offset ?…"),
                    execution("select id from owner where note = 'unclosed limit ?"),
                    execution("select id from a union select id from b limit ?"),
                    execution("insert into owner (name) values (?)"));
        }

        assertThat(observations()).isEmpty();
        assertThat(check().reason())
                .contains("6 SELECTs could not be read", "SQL Trace shortened the statement text")
                .contains("3 statements with a UNION, INTERSECT, or EXCEPT are not read");
    }

    @Test
    void aFailedStatementIsNotCounted() {
        for (int i = 0; i < 3; i++) {
            request("/api/owners", new Event(JournalSource.SQL, 1, new SqlPayload(PAGE, "Repo.run:1", "db", true)));
        }

        assertThat(observations()).isEmpty();
    }

    @Test
    void preparedStatementsAreCountedInTheReasonNotReported() {
        for (int i = 0; i < 3; i++) {
            request(
                    "/api/owners",
                    new Event(
                            JournalSource.SQL,
                            1,
                            new SqlPayload(
                                    PAGE,
                                    "Repo.run:1",
                                    "db",
                                    false,
                                    null,
                                    null,
                                    -1,
                                    0,
                                    SqlPayload.Provenance.PREPARATION)),
                    execution("select id from owner order by id limit ?"));
        }

        assertThat(observations()).isEmpty();
        assertThat(check().reason())
                .contains("3 prepared Hibernate statements")
                .contains("Quarkus records a statement when it is prepared");
    }

    @Test
    void aJdbcExecutionStillQualifiesOnQuarkus() {
        for (int i = 0; i < 2; i++) {
            request("/api/owners", execution(PAGE));
        }

        assertThat(new RuntimeInsightsService(journal, null, null, InsightsStack.QUARKUS, null)
                        .report().observations().stream()
                                .filter(observation -> observation.kind().equals(PagedWithoutOrder.KIND)))
                .hasSize(1);
    }

    @Test
    void distinctStatementsOnOneRouteAreReportedSeparately() {
        for (int i = 0; i < 2; i++) {
            request("/api/owners", execution(PAGE), execution("select id from pet limit ? offset ?"));
        }

        assertThat(observations()).hasSize(2);
    }

    private void request(String path, Event... children) {
        CorrelationContext context = CorrelationContext.forRequest("r" + (++requests));
        for (Event child : children) {
            journal.offer(
                    RuntimeEvent.of(child.source, 1_000, child.nanos, context, "http-1", null, false, child.payload));
        }
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000 + requests,
                100_000_000,
                context,
                "http-1",
                null,
                false,
                new HttpPayload("GET", path, path, null, 200)));
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static Event execution(String statement) {
        return new Event(JournalSource.SQL, 1, new SqlPayload(statement, "Repo.run:1", "db", false));
    }

    private RuntimeInsightsService service() {
        return new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
    }

    private List<RuntimeObservationDto> observations() {
        return service().report().observations().stream()
                .filter(observation -> observation.kind().equals(PagedWithoutOrder.KIND))
                .toList();
    }

    private RuntimeInsightCheckDto check() {
        return new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null)
                .report().checks().stream()
                        .filter(candidate -> candidate.kind().equals(PagedWithoutOrder.KIND))
                        .findFirst()
                        .orElseThrow();
    }

    private record Event(JournalSource source, long nanos, RuntimeEventPayload payload) {}
}
