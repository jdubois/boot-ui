package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationRowDto;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.OrmSessionEvents;
import io.github.jdubois.bootui.engine.journal.RequestTiming;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** M4-9: the {@code orm} source's session meter, its two observations with counterexamples, and the Hibernate phase. */
class OrmObservationsTests {

    private static final long MS = 1_000_000;

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int requests;
    private long clock = 1_000_000_000L;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void aSessionMeterCountsOnlyAutoFlushesThatWroteAndReportsFlushTimeWithoutItsStatements() throws Exception {
        OrmSessionEvents.Publisher publisher = new OrmSessionEvents.Publisher();
        publisher.setRuntimeEventSink(journal);
        CorrelationContext request = CorrelationContext.forRequest("r-meter");
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(request)) {
            OrmSessionEvents.Session session = OrmSessionEvents.open();
            assertThat(session).isNotNull();
            // An auto-flush check before a query that finds nothing to write.
            session.partialFlushStart();
            session.partialFlushEnd(0);
            session.statementStart();
            session.statementEnd();
            // One that writes a pending insert before the next query.
            session.partialFlushStart();
            session.statementStart();
            Thread.sleep(2);
            session.statementEnd();
            session.partialFlushEnd(4);
            session.flushStart();
            session.flushEnd(4);
            session.end("orders");
        } finally {
            publisher.close();
        }
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

        RuntimeEvent event = journal.entries().stream()
                .map(entry -> entry.event())
                .filter(e -> e.source() == JournalSource.ORM)
                .findFirst()
                .orElseThrow();
        OrmPayload orm = (OrmPayload) event.payload();
        assertThat(event.requestId()).isEqualTo("r-meter");
        assertThat(orm.persistenceUnit()).isEqualTo("orders");
        assertThat(orm.statements()).isEqualTo(2);
        assertThat(orm.partialFlushes()).as("only the auto-flush that wrote").isEqualTo(1);
        assertThat(orm.flushes()).isEqualTo(1);
        assertThat(orm.entitiesInContext()).isEqualTo(4);
        assertThat(orm.partialFlushNanos())
                .as("flush time leaves out the statement it executed")
                .isLessThan(orm.statementNanos());
        assertThat(orm.flushTimeline())
                .as("the auto-flush that wrote and the full flush, never the check that found nothing")
                .extracting(OrmPayload.Flush::auto, OrmPayload.Flush::entities)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(true, 4), org.assertj.core.groups.Tuple.tuple(false, 4));
        assertThat(OrmSessionEvents.open())
                .as("nothing is metered once the run's publisher closed")
                .isNull();
    }

    @Test
    void aSessionThatNeverFlushedReportsNoContextSizeAndOneThatDidNothingIsNotPublished() throws Exception {
        OrmSessionEvents.Publisher publisher = new OrmSessionEvents.Publisher();
        publisher.setRuntimeEventSink(journal);
        try {
            OrmSessionEvents.Session idle = OrmSessionEvents.open();
            idle.end(null);
            OrmSessionEvents.Session readOnly = OrmSessionEvents.open();
            readOnly.statementStart();
            readOnly.statementEnd();
            readOnly.end(null);
        } finally {
            publisher.close();
        }
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

        assertThat(journal.entries())
                .extracting(entry -> entry.event().payload())
                .filteredOn(OrmPayload.class::isInstance)
                .singleElement()
                .satisfies(payload ->
                        assertThat(((OrmPayload) payload).entitiesInContext()).isEqualTo(-1));
    }

    @Test
    void repeatedWritingAutoFlushesAreReportedButAFlushAtCommitIsNot() {
        for (int i = 0; i < 3; i++) {
            request("/api/tags/auto-flush", orm(1, 3, 6, 0, 4 * MS, 2 * MS, 12));
            request("/api/tags/read-then-write", orm(1, 0, 4, 0, 0, 2 * MS, 3));
        }

        List<RuntimeObservationDto> flushes = observations(OrmAutoFlush.KIND);

        assertThat(flushes).singleElement().satisfies(observation -> {
            assertThat(observation.subject()).isEqualTo("POST /api/tags/auto-flush");
            assertThat(observation.status()).isEqualTo("OBSERVED");
            assertThat(observation.sentence())
                    .isEqualTo("`POST /api/tags/auto-flush` made Hibernate write pending changes before a query up to"
                            + " 3 times in one request, in 3 of 3 requests.");
        });
    }

    @Test
    void theThresholdIsPerRequestSoOneFlaggedRequestOfARouteIsReported() {
        // Three auto-flushes that wrote, in the route's only request.
        request("/api/tags/once", orm(1, 3, 6, 0, 4 * MS, 2 * MS, 12));
        // One auto-flush taking 3 of the request's 10 ms of ORM time: under three, but over a fifth.
        request("/api/tags/slow-flush", orm(1, 1, 1, 0, 3 * MS, 2 * MS, 12));
        // One auto-flush taking 1 of 33 ms: under three and under a fifth, in each of three requests.
        for (int i = 0; i < 3; i++) {
            request("/api/tags/quick-flush", orm(1, 1, 6, 0, MS, 2 * MS, 12));
        }

        List<RuntimeObservationDto> flushes = observations(OrmAutoFlush.KIND);

        assertThat(flushes)
                .extracting(RuntimeObservationDto::subject, RuntimeObservationDto::status)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("POST /api/tags/once", "OBSERVED"),
                        org.assertj.core.groups.Tuple.tuple("POST /api/tags/slow-flush", "OBSERVED"));
        assertThat(flushes)
                .extracting(RuntimeObservationDto::sentence)
                .contains("`POST /api/tags/once` made Hibernate write pending changes before a query up to 3 times in"
                        + " one request, in 1 of 1 request.");
    }

    @Test
    void aContextOf500EntitiesInThreeRequestsIsReportedButASmallerOneIsNot() {
        for (int i = 0; i < 3; i++) {
            request("/api/report/all", orm(1, 0, 3, 0, 0, MS, 1_200));
            request("/api/report/page", orm(1, 0, 3, 0, 0, MS, 499));
        }

        assertThat(observations(LargePersistenceContext.KIND)).singleElement().satisfies(observation -> {
            assertThat(observation.subject()).isEqualTo("POST /api/report/all");
            assertThat(observation.status()).isEqualTo("OBSERVED");
            assertThat(observation.sentence())
                    .isEqualTo("`POST /api/report/all` held up to 1200 entities in its persistence context in"
                            + " 3 of 3 requests.");
        });
    }

    @Test
    void aLargeContextInFewerThanThreeRequestsOfARouteIsInsufficient() {
        for (int i = 0; i < 2; i++) {
            request("/api/report/twice", orm(1, 0, 3, 0, 0, MS, 800));
        }

        assertThat(observations(LargePersistenceContext.KIND)).singleElement().satisfies(observation -> {
            assertThat(observation.subject()).isEqualTo("POST /api/report/twice");
            assertThat(observation.status()).isEqualTo("INSUFFICIENT");
            assertThat(observation.sentence())
                    .isEqualTo("`POST /api/report/twice` held up to 800 entities in its persistence context in"
                            + " 2 of 2 requests. 3 requests with 500 entities or more are needed to call it a"
                            + " pattern.");
        });
    }

    @Test
    void hibernateFlushTimeIsAPhaseOfItsOwnAndMeasuresSqlWhereStatementsHaveNoDuration() {
        for (int i = 0; i < 6; i++) {
            request("/api/tags/auto-flush", orm(1, 2, 6, 2, 20 * MS, 10 * MS, 12));
        }
        RuntimeInsightsService service = new RuntimeInsightsService(journal, null, null, InsightsStack.QUARKUS, null);
        RuntimeObservationDto breakdown = service.report().observations().stream()
                .filter(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                .findFirst()
                .orElseThrow();

        assertThat(service.insight(breakdown.id()).rows())
                .extracting(RuntimeObservationRowDto::cells)
                .extracting(cells -> cells.get(0) + "=" + cells.get(3))
                .contains("Hibernate flushes=30", "SQL=30");
    }

    @Test
    void onQuarkusAPreparedWriteInAGetIsLeftOutOnlyWhenItsSessionsExecutedNothing() {
        for (int i = 0; i < 2; i++) {
            get("/api/orders/{id}", orm(1, 0, 1, 0, 0, MS, 3));
            get("/api/catalog", orm(1, 0, 0, 0, 0, MS, 3));
            get("/api/unmetered", null);
        }

        assertThat(new RuntimeInsightsService(journal, null, null, InsightsStack.QUARKUS, null)
                        .report().observations().stream()
                                .filter(observation -> observation.kind().equals(SafeMethodDml.KIND))
                                .toList())
                .as("fewer executions than preparations, as a batch over several tables counts, or no metered session"
                        + " is no proof; a session that executed nothing is")
                .extracting(RuntimeObservationDto::subject)
                .containsExactlyInAnyOrder("GET /api/orders/{id}", "GET /api/unmetered");
    }

    @Test
    void onQuarkusPreparationsAndTimedJdbcExecutionsOfTheSameWriteAreNotConflated() {
        get("/api/orders/{id}", orm(1, 0, 1, 0, 0, MS, 3), true);
        RuntimeInsightsService service = new RuntimeInsightsService(journal, null, null, InsightsStack.QUARKUS, null);
        List<RuntimeObservationDto> writes = service.report().observations().stream()
                .filter(observation -> observation.kind().equals(SafeMethodDml.KIND))
                .toList();

        assertThat(writes).hasSize(2);
        assertThat(writes).extracting(RuntimeObservationDto::id).doesNotHaveDuplicates();
        assertThat(writes)
                .extracting(RuntimeObservationDto::sentence)
                .containsExactlyInAnyOrder(
                        "`GET /api/orders/{id}` prepared `insert into audit (what) values (?)` in 1 of 1 request:"
                                + " an incidental write, such as an audit or a counter, or a change the caller asked"
                                + " for?",
                        "`GET /api/orders/{id}` executed `insert into audit (what) values (?)` in 1 of 1 request:"
                                + " an incidental write, such as an audit or a counter, or a change the caller asked"
                                + " for?");
        assertThat(writes)
                .extracting(observation ->
                        service.insight(observation.id()).columns().get(2))
                .containsExactlyInAnyOrder("Prepared statements", "Executions");
        assertThat(writes)
                .allSatisfy(observation -> assertThat(
                                service.insight(observation.id()).rows())
                        .singleElement()
                        .satisfies(row -> assertThat(row.cells().get(2)).isEqualTo("1")));
        assertThat(writes)
                .filteredOn(observation -> observation.sentence().contains(" prepared "))
                .singleElement()
                .satisfies(observation -> assertThat(observation.limitations())
                        .anyMatch(limitation -> limitation.contains("cannot prove this particular statement ran")));
        assertThat(writes)
                .filteredOn(observation -> observation.sentence().contains(" executed "))
                .singleElement()
                .satisfies(observation ->
                        assertThat(observation.limitations()).noneMatch(limitation -> limitation.contains("prepared")));
    }

    /** A GET that prepared a SELECT and an audit INSERT, as Quarkus's statement inspector records them. */
    private void get(String route, OrmPayload orm) {
        get(route, orm, false);
    }

    private void get(String route, OrmPayload orm, boolean timedJdbc) {
        CorrelationContext context = CorrelationContext.forRequest("r" + (++requests));
        for (String sql : List.of("select * from orders where id = ?", "insert into audit (what) values (?)")) {
            journal.offer(RuntimeEvent.of(
                    JournalSource.SQL,
                    1_000,
                    0,
                    context,
                    "executor-thread-1",
                    null,
                    false,
                    new io.github.jdubois.bootui.engine.journal.SqlPayload(sql, null, "<default>", false)));
        }
        if (timedJdbc) {
            journal.offer(RuntimeEvent.of(
                    JournalSource.SQL,
                    1_000,
                    MS,
                    context,
                    "executor-thread-1",
                    null,
                    false,
                    new io.github.jdubois.bootui.engine.journal.SqlPayload(
                            "insert into audit (what) values (?)", null, "<default>", false)));
        }
        journal.offer(
                RuntimeEvent.of(JournalSource.ORM, 1_000, 10 * MS, context, "executor-thread-1", null, false, orm));
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000 + requests,
                20 * MS,
                context,
                "executor-thread-1",
                null,
                false,
                new HttpPayload("GET", route.replace("{id}", "7"), route, null, 200)));
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private List<RuntimeObservationDto> observations(String kind) {
        return new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null)
                .report().observations().stream()
                        .filter(observation -> observation.kind().equals(kind))
                        .toList();
    }

    /**
     * @param flushes full flushes, each taking {@code flushNanos} in total
     * @param partialFlushes auto-flushes that wrote, together taking {@code partialFlushNanos}
     */
    private static OrmPayload orm(
            int flushes,
            int partialFlushes,
            int statements,
            int unused,
            long partialFlushNanos,
            long flushNanos,
            int entities) {
        return new OrmPayload(
                null,
                statements,
                statements * 5L * MS,
                1,
                MS,
                flushes,
                flushNanos,
                partialFlushes,
                partialFlushNanos,
                0,
                entities,
                0,
                0,
                0);
    }

    private void request(String path, OrmPayload orm) {
        CorrelationContext context = CorrelationContext.forRequest("r" + (++requests));
        journal.offer(RuntimeEvent.of(JournalSource.ORM, 1_000, 80 * MS, context, "http-1", null, false, orm));
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000 + requests,
                100 * MS,
                context,
                "http-1",
                null,
                false,
                new HttpPayload("POST", path, path, null, 200, null, new RequestTiming(clock, -1, 2 * MS, 95 * MS))));
        clock += 1_000 * MS;
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
