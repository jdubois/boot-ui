package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationRowDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.RequestTiming;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@code slow-flush}: flush time, full and automatic together, against the 100 ms and a-fifth-of-ORM-time bars. */
class SlowFlushTests {

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
    void aRouteWithThreeRequestsOverTheBarsIsReportedWithItsFullAndAutoFlushTimes() {
        request("/api/export", flushing(150 * MS, 0, 4, 1_200));
        request("/api/export", flushing(200 * MS, 0, 4, 1_500));
        request("/api/export", flushing(120 * MS, 0, 4, 900));

        assertThat(observations()).singleElement().satisfies(observation -> {
            assertThat(observation.subject()).isEqualTo("POST /api/export");
            assertThat(observation.status()).isEqualTo("OBSERVED");
            assertThat(observation.sentence())
                    .isEqualTo("`POST /api/export` spent up to 200 ms flushing Hibernate's persistence context in one"
                            + " request, in 3 of 3 requests. Those 3 took at least 100 ms and a fifth of their"
                            + " ORM time each.");
            assertThat(observation.whatToCheck()).anyMatch(check -> check.contains("`large-persistence-context`"));
            assertThat(observation.limitations())
                    .contains("The flush timeline keeps only the first 16 flushes of a session; totals still include"
                            + " all.");
        });
        RuntimeInsightsService service = service();
        RuntimeObservationDto observation = observations().get(0);
        assertThat(service.insight(observation.id()).columns())
                .containsExactly(
                        "Request", "Flush time (ms)", "Of which auto-flushes (ms)", "Most entities in a context");
        assertThat(service.insight(observation.id()).rows())
                .extracting(RuntimeObservationRowDto::cells)
                .extracting(cells -> cells.subList(1, 4))
                .as("the slowest request first")
                .containsExactly(
                        List.of("200", "0.0", "1500"), List.of("150", "0.0", "1200"), List.of("120", "0.0", "900"));
    }

    @Test
    void autoFlushTimeCountsTowardTheBarAndIsShownApart() {
        for (int i = 0; i < 3; i++) {
            request("/api/mixed", flushing(40 * MS, 60 * MS, 4, 900));
        }

        RuntimeObservationDto observation = observations().get(0);
        assertThat(observation.sentence()).contains("spent up to 100 ms");
        assertThat(service().insight(observation.id()).rows())
                .extracting(RuntimeObservationRowDto::cells)
                .extracting(cells -> cells.subList(1, 3))
                .as("the total that crossed the bar, then its auto-flush share")
                .containsOnly(List.of("100", "60"));
    }

    @Test
    void aFlushTimeJustUnder100MillisecondsIsNotReported() {
        for (int i = 0; i < 3; i++) {
            request("/api/almost", flushing(99 * MS, 0, 4, 900));
        }

        assertThat(observations()).isEmpty();
    }

    @Test
    void fastFlushesAreNotReported() {
        for (int i = 0; i < 5; i++) {
            request("/api/quick", flushing(5 * MS, 0, 4, 20));
        }

        assertThat(observations()).isEmpty();
    }

    @Test
    void aSlowFlushInTwoRequestsOnlyIsNotReported() {
        request("/api/twice", flushing(150 * MS, 0, 4, 800));
        request("/api/twice", flushing(150 * MS, 0, 4, 800));
        request("/api/twice", flushing(5 * MS, 0, 4, 20));

        assertThat(observations()).isEmpty();
    }

    @Test
    void aSlowFlushThatIsUnderAFifthOfTheRequestsOrmTimeIsNotReported() {
        // 100 ms of flushing against 500 ms of statements: 100 of 600 ms, a sixth.
        for (int i = 0; i < 3; i++) {
            request("/api/statements", flushing(100 * MS, 0, 100, 800));
        }

        assertThat(observations()).isEmpty();
    }

    @Test
    void theShareIsPerRequestSoThreeSlowRequestsAmongManyAreStillCounted() {
        request("/api/mixed-load", flushing(150 * MS, 0, 4, 800));
        request("/api/mixed-load", flushing(150 * MS, 0, 4, 800));
        request("/api/mixed-load", flushing(150 * MS, 0, 4, 800));
        request("/api/mixed-load", flushing(5 * MS, 0, 4, 20));
        request("/api/mixed-load", flushing(100 * MS, 0, 100, 800));

        assertThat(observations())
                .singleElement()
                .satisfies(observation -> assertThat(observation.sentence()).contains("in 3 of 5 requests"));
    }

    @Test
    void aRequestWithTwoSessionsAddsTheirFlushTimes() {
        for (int i = 0; i < 3; i++) {
            request("/api/two-sessions", flushing(60 * MS, 0, 4, 40), flushing(60 * MS, 0, 4, 40));
        }

        assertThat(observations())
                .singleElement()
                .satisfies(observation -> assertThat(observation.sentence()).contains("spent up to 120 ms"));
    }

    @Test
    void aShareOfExactlyAFifthIsReported() {
        // 100 ms of flushing against 400 ms of statements: 100 of 500 ms.
        for (int i = 0; i < 3; i++) {
            request("/api/fifth", flushing(100 * MS, 0, 80, 40));
        }

        assertThat(observations()).hasSize(1);
    }

    @Test
    void autoFlushChecksThatWroteNothingAreSlowFlushTimeButNotAnAutoFlushFinding() {
        for (int i = 0; i < 3; i++) {
            request("/api/checks-only", flushing(0, 150 * MS, 0, 4, 300));
        }

        assertThat(observations()).hasSize(1);
        assertThat(service().report().observations())
                .filteredOn(observation -> observation.kind().equals(OrmAutoFlush.KIND))
                .as("orm-auto-flush counts only auto-flushes that wrote")
                .isEmpty();
    }

    @Test
    void aContextSizeThatWasNeverMeasuredIsShownAsUnknown() {
        request("/api/auto-only", flushing(0, 150 * MS, 4, -1));
        request("/api/auto-only", flushing(0, 150 * MS, 4, -1));
        request("/api/auto-only", flushing(0, 150 * MS, 4, -1));

        RuntimeObservationDto observation = observations().get(0);
        assertThat(service().insight(observation.id()).rows())
                .extracting(row -> row.cells().get(3))
                .containsOnly("unknown");
    }

    private RuntimeInsightsService service() {
        return new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
    }

    private List<RuntimeObservationDto> observations() {
        return service().report().observations().stream()
                .filter(observation -> observation.kind().equals(SlowFlush.KIND))
                .toList();
    }

    private static OrmPayload flushing(long flushNanos, long partialNanos, int statements, int entities) {
        return flushing(flushNanos, partialNanos, partialNanos > 0 ? 1 : 0, statements, entities);
    }

    /** A session whose {@code partialFlushes} auto-flushes wrote, though every auto-flush check adds its time. */
    private static OrmPayload flushing(
            long flushNanos, long partialNanos, int partialFlushes, int statements, int entities) {
        return new OrmPayload(
                null,
                statements,
                statements * 5L * MS,
                1,
                MS,
                flushNanos > 0 ? 1 : 0,
                flushNanos,
                partialFlushes,
                partialNanos,
                0,
                entities,
                0,
                0,
                0);
    }

    private void request(String path, OrmPayload... sessions) {
        CorrelationContext context = CorrelationContext.forRequest("r" + (++requests));
        for (OrmPayload orm : sessions) {
            journal.offer(RuntimeEvent.of(JournalSource.ORM, 1_000, 80 * MS, context, "http-1", null, false, orm));
        }
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
