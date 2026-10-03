package io.github.jdubois.bootui.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.MemoryOffloadReport;
import io.github.jdubois.bootui.core.dto.MemoryOffloadStoreDto;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MemoryOffloadServiceTests {

    private final AtomicLong heap = new AtomicLong(1_000L);
    private final AtomicInteger gcCalls = new AtomicInteger();
    private final AtomicLong clock = new AtomicLong();

    private MemoryOffloadService service(List<Object> candidates, boolean explicitGcDisabled) {
        return new MemoryOffloadService(
                () -> candidates,
                heap::get,
                () -> {
                    gcCalls.incrementAndGet();
                    heap.set(400L);
                    clock.addAndGet(7_000_000L);
                },
                () -> explicitGcDisabled,
                clock::get);
    }

    @Test
    void emptiesEveryOffloadableOnceInIdOrderThenRequestsAGarbageCollection() {
        FakeStore sql = new FakeStore("sql-trace", "SQL Trace statements", 3);
        FakeStore journal = new FakeStore("runtime-journal", "Runtime journal events", 5);
        List<Object> candidates = new ArrayList<>(List.of(sql, "not a store", journal, sql));

        MemoryOffloadReport report = service(candidates, false).offload();

        assertThat(report.stores())
                .extracting(MemoryOffloadStoreDto::id)
                .containsExactly("runtime-journal", "sql-trace");
        assertThat(report.stores()).allSatisfy(store -> {
            assertThat(store.cleared()).isTrue();
            assertThat(store.failure()).isNull();
        });
        assertThat(report.entriesCleared()).isEqualTo(8);
        assertThat(sql.offloads).isEqualTo(1);
        assertThat(journal.offloads).isEqualTo(1);
        assertThat(sql.retained).isZero();
        assertThat(gcCalls).hasValue(1);
        assertThat(report.gcRequested()).isTrue();
        assertThat(report.explicitGcDisabled()).isFalse();
        assertThat(report.heapUsedBeforeBytes()).isEqualTo(1_000L);
        assertThat(report.heapUsedAfterBytes()).isEqualTo(400L);
        assertThat(report.reclaimedBytes()).isEqualTo(600L);
        assertThat(report.durationMillis()).isEqualTo(7L);
    }

    @Test
    void reportsAFailingStoreWithoutStoppingTheOthers() {
        MemoryOffloadable failing = new FakeStore("broken", "Broken store", 2) {
            @Override
            public long offloadRetainedData() {
                throw new IllegalStateException("store is closed");
            }
        };
        FakeStore healthy = new FakeStore("healthy", "Healthy store", 4);

        MemoryOffloadReport report = service(List.of(failing, healthy), false).offload();

        assertThat(report.stores())
                .containsExactly(
                        new MemoryOffloadStoreDto(
                                "broken", "Broken store", false, 0, "IllegalStateException: store is closed"),
                        new MemoryOffloadStoreDto("healthy", "Healthy store", true, 4, null));
        assertThat(report.entriesCleared()).isEqualTo(4);
        assertThat(gcCalls).hasValue(1);
    }

    @Test
    void neverReportsNegativeReclaimedMemoryWhenTheApplicationAllocatedMeanwhile() {
        MemoryOffloadService service = new MemoryOffloadService(
                List::of, () -> heap.getAndAdd(500L), gcCalls::incrementAndGet, () -> true, clock::get);

        MemoryOffloadReport report = service.offload();

        assertThat(report.heapUsedAfterBytes()).isGreaterThan(report.heapUsedBeforeBytes());
        assertThat(report.reclaimedBytes()).isZero();
        assertThat(report.stores()).isEmpty();
        assertThat(report.explicitGcDisabled()).isTrue();
        assertThat(report.gcRequested()).isTrue();
    }

    @Test
    void toleratesAnAbsentCandidateList() {
        MemoryOffloadReport report = new MemoryOffloadService(
                        () -> null, heap::get, gcCalls::incrementAndGet, () -> false, clock::get)
                .offload();

        assertThat(report.stores()).isEmpty();
        assertThat(gcCalls).hasValue(1);
    }

    @Test
    void readsTheLastExplicitGcFlag() {
        assertThat(MemoryOffloadService.explicitGcDisabled(null)).isFalse();
        assertThat(MemoryOffloadService.explicitGcDisabled(List.of("-Xmx512m"))).isFalse();
        assertThat(MemoryOffloadService.explicitGcDisabled(List.of("-XX:+DisableExplicitGC")))
                .isTrue();
        assertThat(MemoryOffloadService.explicitGcDisabled(List.of("-XX:+DisableExplicitGC", "-XX:-DisableExplicitGC")))
                .isFalse();
    }

    @Test
    void engineCaptureStoresAreOffloadable() {
        assertThat(MemoryOffloadable.class)
                .isAssignableFrom(ExceptionStore.class)
                .isAssignableFrom(TelemetryStore.class);
    }

    @Test
    void theRealJvmIsMeasuredWithoutFailing() {
        FakeStore store = new FakeStore("probe", "Probe", 1);

        MemoryOffloadReport report = new MemoryOffloadService(() -> List.of(store)).offload();

        assertThat(report.heapUsedBeforeBytes()).isPositive();
        assertThat(report.heapUsedAfterBytes()).isPositive();
        assertThat(report.reclaimedBytes()).isNotNegative();
        assertThat(report.durationMillis()).isNotNegative();
        assertThat(store.retained).isZero();
    }

    private static class FakeStore implements MemoryOffloadable {

        private final String id;
        private final String label;
        long retained;
        int offloads;

        FakeStore(String id, String label, long retained) {
            this.id = id;
            this.label = label;
            this.retained = retained;
        }

        @Override
        public String offloadId() {
            return id;
        }

        @Override
        public String offloadLabel() {
            return label;
        }

        @Override
        public long offloadRetainedData() {
            offloads++;
            long dropped = retained;
            retained = 0;
            return dropped;
        }
    }
}
