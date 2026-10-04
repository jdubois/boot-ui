package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationRowDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.GcPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.resources.GcPauseRange;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@code gc-inflated-latency} and {@code heap-growth-after-gc} (§5.11), each with a counterexample and a minimum. */
class GcObservationsTests {

    private static final long MS = 1_000_000;
    private static final long MIB = 1024 * 1024;
    private static final String YOUNG = "G1 Young Generation";

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int requests;
    private long collections;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void pausesCompletingDuringARoutesSlowestRequestsMoreOftenThanDuringItsOthersAreObserved() {
        request("/api/report", 500 * MS, 0); // the route's cold first request is left out of the ranking
        // Ten requests of 10 to 100 ms: pauses complete during three of the five slowest and one of the five others.
        for (int i = 1; i <= 10; i++) {
            request("/api/report", i * 10 * MS, i == 7 || i == 9 || i == 10 || i == 2 ? 1 : 0);
        }

        RuntimeInsightsService service = service();
        RuntimeObservationDto observation = only(service, GcInflatedLatency.KIND);

        assertThat(observation.status()).isEqualTo("OBSERVED");
        assertThat(observation.sentence())
                .isEqualTo("A stop-the-world pause completed during 3 of `GET /api/report`'s 5 slowest requests"
                        + " (60 %), against 20 % of its other 5 requests; those pauses total 15 ms.");
        assertThat(observation.eligible()).isEqualTo(5);
        assertThat(observation.affected()).isEqualTo(3);
        assertThat(observation.exemplarRequestIds()).containsExactly("r11", "r10", "r8");
        assertThat(service.insight(observation.id()).rows())
                .extracting(RuntimeObservationRowDto::cells)
                .first()
                .isEqualTo(List.of("r11", "100", "1", "5.0", YOUNG));
        assertThat(observation.limitations().get(0)).contains("is not measured");
    }

    @Test
    void aRoutesColdFirstRequestIsNotRankedAmongItsSlowestRequests() {
        // The cold request and one other slow request carry pauses: warm-up alone must not make the route's pauses
        // look like they inflate its latency.
        request("/api/report", 200 * MS, 1);
        for (int i = 1; i <= 10; i++) {
            request("/api/report", i * 10 * MS, i == 10 ? 1 : 0);
        }

        assertThat(service().report().observations())
                .filteredOn(observation -> observation.kind().equals(GcInflatedLatency.KIND))
                .isEmpty();
    }

    @Test
    void afterAClearTheFirstRetainedRequestIsRankedBecauseNoRequestIsKnownToBeCold() {
        request("/api/report", 5 * MS, 0);
        journal.clear();
        request("/api/report", 200 * MS, 1);
        for (int i = 1; i <= 10; i++) {
            request("/api/report", i * 10 * MS, i == 10 ? 1 : 0);
        }

        assertThat(service().report().observations())
                .filteredOn(observation -> observation.kind().equals(GcInflatedLatency.KIND))
                .hasSize(1);
    }

    @Test
    void pausesSpreadEvenlyOverARoutesRequestsAreNotObserved() {
        request("/api/report", 500 * MS, 0); // the route's cold first request is left out of the ranking
        for (int i = 1; i <= 10; i++) {
            request("/api/report", i * 10 * MS, i == 1 || i == 3 || i == 8 || i == 10 ? 1 : 0);
        }

        assertThat(service().report().observations())
                .filteredOn(observation -> observation.kind().equals(GcInflatedLatency.KIND))
                .isEmpty();
    }

    @Test
    void aRouteWithTooFewMeasuredRequestsIsInsufficientOnlyOncePausesRepeat() {
        request("/api/report", 500 * MS, 0); // the route's cold first request is left out of the ranking
        for (int i = 1; i <= 4; i++) {
            request("/api/report", i * 10 * MS, i >= 3 ? 1 : 0);
        }
        request("/api/other", 10 * MS, 1);

        RuntimeObservationDto observation = only(service(), GcInflatedLatency.KIND);

        assertThat(observation.status()).isEqualTo("INSUFFICIENT");
        assertThat(observation.subject()).isEqualTo("GET /api/report");
        assertThat(observation.sentence())
                .isEqualTo("A pause completed during 2 of `GET /api/report`'s 4 measured requests; comparing its"
                        + " slowest requests with the others needs 10.");
    }

    @Test
    void oldGenerationOccupancyRisingAfterTheCollectionsThatReclaimedItIsObserved() {
        young(30, 34);
        reclaiming(80, 40);
        young(40, 60);
        reclaiming(90, 45);
        reclaiming(95, 52);
        young(52, 70);
        reclaiming(100, 60);

        RuntimeInsightsService service = service();
        RuntimeObservationDto observation = only(service, HeapGrowthAfterGc.KIND);

        assertThat(observation.status()).isEqualTo("OBSERVED");
        assertThat(observation.sentence())
                .isEqualTo("Old-generation occupancy after the 4 collections that reclaimed it rose from 40.0 MiB to"
                        + " 60.0 MiB (+50 %), rising in 3 of 3 steps.");
        assertThat(observation.eligible()).isZero();
        assertThat(check(service, HeapGrowthAfterGc.KIND)).satisfies(check -> {
            assertThat(check.status()).isEqualTo("EVALUATED");
            assertThat(check.eligibleRequests()).isZero();
            assertThat(check.reason()).isNull();
        });
        assertThat(RuntimeInsightsAgentView.list(service.report(), null, null).checksNotRun())
                .noneMatch(reason -> reason.startsWith(HeapGrowthAfterGc.KIND + ":"));
        assertThat(service.insight(observation.id()).rows())
                .extracting(row -> row.cells().get(3))
                .containsExactly("60.0", "52.0", "45.0", "40.0");
    }

    @Test
    void anOldGenerationLevelThatHoldsIsNotObservedAndTooFewCollectionsAreInsufficient() {
        reclaiming(80, 40);
        reclaiming(90, 41);

        RuntimeInsightsService service = service();
        RuntimeObservationDto insufficient = only(service, HeapGrowthAfterGc.KIND);
        assertThat(insufficient.status()).isEqualTo("INSUFFICIENT");
        assertThat(insufficient.sentence())
                .isEqualTo("2 collections reclaimed old-generation space in this run; a trend needs 3.");
        assertThat(check(service, HeapGrowthAfterGc.KIND).status()).isEqualTo("EVALUATED");

        reclaiming(85, 40);
        reclaiming(88, 42);
        assertThat(service.report().observations())
                .filteredOn(observation -> observation.kind().equals(HeapGrowthAfterGc.KIND))
                .isEmpty();
        assertThat(check(service, HeapGrowthAfterGc.KIND)).satisfies(check -> {
            assertThat(check.status()).isEqualTo("EVALUATED");
            assertThat(check.eligibleRequests()).isZero();
            assertThat(check.reason())
                    .contains("4 collections that reclaimed old-generation space", "growth threshold")
                    .doesNotContain("No eligible work");
        });
        assertThat(RuntimeInsightsAgentView.list(service.report(), null, null).checksNotRun())
                .noneMatch(reason -> reason.startsWith(HeapGrowthAfterGc.KIND + ":"));
    }

    @Test
    void youngCollectionsAloneLeaveHeapGrowthWithoutEligibleWork() {
        young(30, 34);
        young(34, 40);

        RuntimeInsightsService service = service();
        assertThat(check(service, HeapGrowthAfterGc.KIND)).satisfies(check -> {
            assertThat(check.status()).isEqualTo("INSUFFICIENT");
            assertThat(check.reason()).contains("No eligible work");
        });
        assertThat(RuntimeInsightsAgentView.list(service.report(), null, null).checksNotRun())
                .anyMatch(reason -> reason.startsWith(HeapGrowthAfterGc.KIND + ":"));
    }

    @Test
    void aHeapWithoutAnOldGenerationMakesHeapGrowthNotApplicable() {
        gc(new GcPayload("ZGC Cycles", ++collections, "end of GC cycle", "Allocation Rate", false, 90, 40));

        assertThat(service().report().checks())
                .filteredOn(check -> check.kind().equals(HeapGrowthAfterGc.KIND))
                .singleElement()
                .extracting(RuntimeInsightCheckDto::status)
                .isEqualTo("NOT_APPLICABLE");
    }

    private RuntimeInsightsService service() {
        return new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
    }

    private static RuntimeInsightCheckDto check(RuntimeInsightsService service, String kind) {
        return service.report().checks().stream()
                .filter(check -> check.kind().equals(kind))
                .findFirst()
                .orElseThrow();
    }

    private static RuntimeObservationDto only(RuntimeInsightsService service, String kind) {
        return service.report().observations().stream()
                .filter(observation -> observation.kind().equals(kind))
                .reduce((first, second) -> {
                    throw new AssertionError("More than one " + kind);
                })
                .orElseThrow();
    }

    private void request(String template, long durationNanos, int pauses) {
        String requestId = "r" + (++requests);
        List<GcPauseRange> ranges = List.of();
        if (pauses > 0) {
            ranges = List.of(new GcPauseRange(YOUNG, collections, collections + pauses));
            for (int i = 0; i < pauses; i++) {
                gc(new GcPayload(YOUNG, ++collections, "end of minor GC", "G1 Evacuation Pause", true, 50, 20));
            }
        }
        offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000 + requests,
                durationNanos,
                CorrelationContext.forRequest(requestId),
                "http-1",
                null,
                false,
                new HttpPayload(
                        "GET",
                        template,
                        template,
                        null,
                        200,
                        new ResourceUsage(1, 1, 1, 0, null, pauses, ranges, false))));
    }

    private void young(long oldBeforeMib, long oldAfterMib) {
        gc(new GcPayload(
                YOUNG,
                ++collections,
                "end of minor GC",
                "G1 Evacuation Pause",
                true,
                200 * MIB,
                oldAfterMib * MIB,
                oldBeforeMib * MIB,
                oldAfterMib * MIB));
    }

    private void reclaiming(long oldBeforeMib, long oldAfterMib) {
        gc(new GcPayload(
                YOUNG,
                ++collections,
                "end of minor GC",
                "G1 Evacuation Pause",
                true,
                200 * MIB,
                (oldAfterMib + 10) * MIB,
                oldBeforeMib * MIB,
                oldAfterMib * MIB));
    }

    private void gc(GcPayload payload) {
        offer(RuntimeEvent.of(JournalSource.GC, 1_000, 5 * MS, CorrelationContext.NONE, null, null, false, payload));
    }

    private void offer(RuntimeEvent event) {
        journal.offer(event);
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
