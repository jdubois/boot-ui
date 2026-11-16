package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationRowDto;
import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.SynchronousJournals;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RouteLatencyOutliersTests {

    private final AtomicBoolean dropNext = new AtomicBoolean();
    private final RuntimeJournal journal = SynchronousJournals.create(
            new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
            i -> dropNext.getAndSet(false));

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void comparesRouteSlowestOutlierAgainstClosestWarm2xxBaselineAndExcludesColdFirstRequest() {
        // Cold first request: slow, but excluded as first request on the route.
        offerHttp("r-cold", 1_000, 350_000_000, 200);

        for (int i = 0; i < 5; i++) {
            String reqId = "r-warm-" + i;
            long start = 2_000 + i * 100L;
            offerChild(reqId, JournalSource.CONNECTION, start + 1, 2_000_000, new ConnectionPayload("db", 100_000, 1));
            offerChild(
                    reqId,
                    JournalSource.SQL,
                    start + 2,
                    2_000_000,
                    new SqlPayload("select * from catalog where id = " + i, null, "db", false));
            offerChild(reqId, JournalSource.CACHE, start + 3, -1, new CachePayload("catalog", "HIT"));
            offerChild(
                    reqId,
                    JournalSource.REST_CLIENT,
                    start + 4,
                    3_000_000,
                    new RestClientPayload("GET", "pricing:8080", "/p", 200, "RestClient", false));
            offerChild(
                    reqId,
                    JournalSource.ORM,
                    start + 5,
                    2_000_000,
                    new OrmPayload(null, 1, 2_000_000, 1, 100_000, 0, 0, 0, 0, 0, 4, 1, 0, 0));
            offerHttp(reqId, start, 15_000_000, 200);
        }

        // Outlier request on the same route (240 ms vs 15 ms warm median).
        offerChild("r-outlier", JournalSource.CONNECTION, 3_001, 40_000_000, new ConnectionPayload("db", 8_100_000, 4));
        offerChild(
                "r-outlier",
                JournalSource.SQL,
                3_002,
                5_000_000,
                new SqlPayload("select * from catalog where id = 99", null, "db", false));
        for (int i = 0; i < 3; i++) {
            offerChild(
                    "r-outlier",
                    JournalSource.SQL,
                    3_003 + i,
                    12_000_000,
                    new SqlPayload("select * from catalog_reviews where catalog_id = " + i, null, "db", false));
        }
        offerChild("r-outlier", JournalSource.CACHE, 3_010, -1, new CachePayload("catalog", "MISS"));
        offerChild(
                "r-outlier",
                JournalSource.REST_CLIENT,
                3_011,
                15_000_000,
                new RestClientPayload("GET", "pricing:8080", "/p", 200, "RestClient", false));
        offerChild(
                "r-outlier",
                JournalSource.REST_CLIENT,
                3_012,
                45_000_000,
                new RestClientPayload("POST", "inventory:8080", "/hold", 200, "RestClient", false));
        offerChild(
                "r-outlier",
                JournalSource.ORM,
                3_015,
                50_000_000,
                new OrmPayload(null, 4, 41_000_000, 1, 8_100_000, 1, 2_000_000, 2, 3_000_000, 2, 34, 0, 1, 1));
        offerHttp("r-outlier", 3_000, 240_000_000, 200);
        SynchronousJournals.dispatch(journal);

        RuntimeInsightsService service = new RuntimeInsightsService(
                journal, RouteTemplateResolver::empty, panel -> true, InsightsStack.SPRING_MVC, List::of);
        RuntimeObservationDto observed = service.report().observations().stream()
                .filter(row -> RouteLatencyOutliers.KIND.equals(row.kind()))
                .findFirst()
                .orElseThrow();

        assertThat(observed.status()).isEqualTo("OBSERVED");
        assertThat(observed.subject()).isEqualTo("GET /api/catalog/{id}");
        assertThat(observed.exemplarRequestIds()).containsExactly("r-outlier", "r-warm-4");
        assertThat(observed.sentence())
                .contains("r-outlier")
                .contains("r-warm-4")
                .contains("+3 SQL executions")
                .contains("+1 cache miss")
                .contains("+1 REST client call")
                .contains("+2 auto-flushes");

        RuntimeObservationDetailDto detail = service.insight(observed.id());
        assertThat(detail.available()).isTrue();
        assertThat(detail.columns()).containsExactly("Dimension", "Outlier", "Baseline", "Delta");
        assertThat(detail.rows())
                .extracting(RuntimeObservationRowDto::cells)
                .anySatisfy(cells -> assertThat(cells)
                        .contains("Distinguishing SQL", "select * from catalog_reviews where catalog_id = ?"));
        service.setCacheCapture(() -> CacheCapture.notRecorded(CacheCapture.PAUSED));
        assertThat(service.report().observations()).contains(observed);
        assertThat(service.insight(observed.id()).rows()).isEqualTo(detail.rows());
        assertThat(service.report().checks())
                .filteredOn(check -> check.kind().equals(RouteLatencyOutliers.KIND))
                .singleElement()
                .satisfies(check -> assertThat(String.valueOf(check.reason())).doesNotContain(CacheCapture.PAUSED));
    }

    @Test
    void pausedCapturePreservesRetainedCacheComparisonsAndPriorCacheDropPartialStatus() {
        dropNext.set(true);
        offerChild("lost", JournalSource.CACHE, 500, -1, new CachePayload("catalog", "HIT"));
        offerHttp("cold", 1_000, 10_000_000, 200);
        for (int i = 0; i < 5; i++) {
            offerChild("warm" + i, JournalSource.CACHE, 2_001 + i * 100, -1, new CachePayload("catalog", "HIT"));
            offerHttp("warm" + i, 2_000 + i * 100, 10_000_000, 200);
        }
        offerChild("slow", JournalSource.CACHE, 3_001, -1, new CachePayload("catalog", "MISS"));
        offerHttp("slow", 3_000, 240_000_000, 200);
        SynchronousJournals.dispatch(journal);
        AtomicReference<CacheCapture> capture = new AtomicReference<>(CacheCapture.capturing());
        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        service.setCacheCapture(capture::get);
        RuntimeObservationDto before = service.report().observations().stream()
                .filter(row -> row.kind().equals(RouteLatencyOutliers.KIND))
                .findFirst()
                .orElseThrow();
        assertThat(before.status()).isEqualTo("PARTIAL");
        assertThat(before.sentence()).contains("cache miss");
        List<RuntimeObservationRowDto> rows = service.insight(before.id()).rows();
        capture.set(CacheCapture.notRecorded(CacheCapture.PAUSED));
        assertThat(service.report().observations()).contains(before);
        assertThat(service.insight(before.id()).rows()).isEqualTo(rows);
        assertThat(service.report().checks())
                .filteredOn(check -> check.kind().equals(RouteLatencyOutliers.KIND))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo("PARTIAL");
                    assertThat(check.reason()).contains("dropped 1 events").doesNotContain(CacheCapture.PAUSED);
                });
    }

    private void offerHttp(String requestId, long start, long durationNanos, int status) {
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                start,
                durationNanos,
                CorrelationContext.forRequest(requestId),
                "worker-1",
                ThreadKind.WORKER,
                status >= 500,
                new HttpPayload("GET", "/api/catalog/42", "/api/catalog/{id}", null, status, null)));
    }

    private void offerChild(
            String requestId, JournalSource source, long start, long durationNanos, RuntimeEventPayload payload) {
        journal.offer(RuntimeEvent.of(
                source,
                start,
                durationNanos,
                CorrelationContext.forRequest(requestId),
                "worker-1",
                ThreadKind.WORKER,
                false,
                payload));
    }
}
