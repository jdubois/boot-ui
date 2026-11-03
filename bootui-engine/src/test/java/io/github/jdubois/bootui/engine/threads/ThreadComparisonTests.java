package io.github.jdubois.bootui.engine.threads;

import static io.github.jdubois.bootui.engine.threads.ThreadAnalysisFixtures.row;
import static io.github.jdubois.bootui.engine.threads.ThreadAnalysisFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CapturedThreadDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThreadComparisonTests {
    @Test
    void repeatedAcquisitionsPrecedeCpuWorkersAndCpuTiesUseNumericThreadIds() {
        var rows = new ArrayList<CapturedThreadDto>();
        for (int id = 1; id <= 40; id++) rows.add(row(id, "cpu-" + id, "RUNNABLE", null, "app.Work"));
        rows.add(row(41, "blocked", "BLOCKED", 99L, "app.Lock.acquire(Lock.java:1)"));
        var first = sample(1000, rows.toArray(CapturedThreadDto[]::new));
        rows.set(40, row(41, "blocked", "BLOCKED", 99L, "app.Lock.acquire(Lock.java:2)"));
        var later = snapshot(2000, rows.toArray(CapturedThreadDto[]::new));
        var cpu = new HashMap<>(later.cpu());
        cpu.put(41L, new ThreadSnapshot.CpuReading(100_000_000, 50_000_000, 2_000_000_000L));
        var second = new ThreadSnapshot(
                later.report(),
                later.threads(),
                cpu,
                later.completedAt(),
                later.durationNanos(),
                later.retainedBytes());
        var result = ThreadComparison.compare(List.of(first, new ThreadComparison.Sample(second, second.threads())));
        assertThat(result.get(0)).satisfies(thread -> {
            assertThat(thread.threadId()).isEqualTo(41);
            assertThat(thread.cpuDeltaNanos()).isZero();
            assertThat(thread.consecutiveStackStreak()).isEqualTo(1);
            assertThat(thread.interpretation()).contains("does not prove a continuous hang");
        });
        assertThat(result.subList(1, 41))
                .extracting(thread -> thread.threadId())
                .isSorted();
    }

    @Test
    void disappearanceAndReappearanceNeverJoinCpuOrConsecutiveStackTracks() {
        var result = ThreadComparison.compare(List.of(
                sample(1000, row(1, "first", "RUNNABLE", null, "app.Work")),
                sample(2000, row(2, "other", "RUNNABLE", null, "app.Work")),
                sample(3000, row(1, "reused", "RUNNABLE", null, "app.Work"))));
        assertThat(result)
                .filteredOn(thread -> thread.threadId() == 1)
                .hasSize(2)
                .allSatisfy(thread -> {
                    assertThat(thread.observedSamples()).isEqualTo(1);
                    assertThat(thread.consecutiveStackStreak()).isEqualTo(1);
                    assertThat(thread.cpuDeltaNanos()).isNull();
                });
    }

    @Test
    void missingOrResetCpuCountersAndNonPositiveIntervalsStayUnavailable() {
        var first = sample(1000, row(1, "worker", "RUNNABLE", null, "app.Work"));
        for (var reading : List.of(
                new ThreadSnapshot.CpuReading(-1, -1, 2000000000L),
                new ThreadSnapshot.CpuReading(0, 0, 2000000000L),
                new ThreadSnapshot.CpuReading(200000000, 100000000, 1000000000L))) {
            var later = snapshot(2000, row(1, "renamed", "RUNNABLE", null, "app.Work"));
            var altered = new ThreadSnapshot(
                    later.report(),
                    later.threads(),
                    Map.of(1L, reading),
                    later.completedAt(),
                    later.durationNanos(),
                    later.retainedBytes());
            assertThat(ThreadComparison.compare(List.of(first, new ThreadComparison.Sample(altered, later.threads()))))
                    .singleElement()
                    .satisfies(thread -> {
                        assertThat(thread.cpuDeltaNanos()).isNull();
                        assertThat(thread.singleCoreCpuPercent()).isNull();
                        assertThat(thread.cpuUnavailableReason()).isNotBlank();
                    });
        }
        var later = snapshot(2000, row(1, "worker", "RUNNABLE", null, "app.Work"));
        var missing = new ThreadSnapshot(
                later.report(),
                later.threads(),
                Map.of(),
                later.completedAt(),
                later.durationNanos(),
                later.retainedBytes());
        assertThat(ThreadComparison.compare(List.of(first, new ThreadComparison.Sample(missing, later.threads()))))
                .singleElement()
                .satisfies(thread -> assertThat(thread.cpuDeltaNanos()).isNull());
    }

    @Test
    void metadataOnlyDoesNotFingerprintAbsentStacks() {
        var result = ThreadComparison.compare(List.of(
                sample(1000, row(1, "worker", "RUNNABLE", null, null)),
                sample(2000, row(1, "worker", "RUNNABLE", null, null))));
        assertThat(result).singleElement().satisfies(thread -> {
            assertThat(thread.matchingStackOccurrences()).isZero();
            assertThat(thread.consecutiveStackStreak()).isZero();
            assertThat(thread.interpretation()).contains("not fingerprinted");
        });
    }

    @Test
    void maskedFramesNeverCreateMatchingStackEvidence() {
        var result = ThreadComparison.compare(List.of(
                sample(
                        1000,
                        row(1, "worker", "RUNNABLE", null, io.github.jdubois.bootui.core.SecretMasker.MASKED_VALUE)),
                sample(
                        2000,
                        row(1, "worker", "RUNNABLE", null, io.github.jdubois.bootui.core.SecretMasker.MASKED_VALUE))));
        assertThat(result).singleElement().satisfies(thread -> {
            assertThat(thread.matchingStackOccurrences()).isZero();
            assertThat(thread.consecutiveStackStreak()).isZero();
            assertThat(thread.interpretation()).contains("masked");
        });
    }

    private static ThreadComparison.Sample sample(long millis, CapturedThreadDto... rows) {
        var source = snapshot(millis, rows);
        return new ThreadComparison.Sample(source, source.threads());
    }
}
