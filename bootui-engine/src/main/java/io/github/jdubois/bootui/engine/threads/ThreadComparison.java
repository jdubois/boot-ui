package io.github.jdubois.bootui.engine.threads;

import io.github.jdubois.bootui.core.dto.CapturedThreadDto;
import io.github.jdubois.bootui.core.dto.ThreadComparisonDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Comparisons are same-run, same-reader observations, not proof of continuous execution. */
final class ThreadComparison {
    private ThreadComparison() {}

    static List<ThreadComparisonDto> compare(List<Sample> samples) {
        Map<Long, Track> current = new HashMap<>();
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            for (CapturedThreadDto row : sample.rows()) {
                Track track = current.get(row.thread().id());
                if (track == null || track.lastIndex != i - 1) {
                    track = new Track();
                    current.put(row.thread().id(), track);
                    tracks.add(track);
                }
                track.lastIndex = i;
                track.rows.add(row);
                track.times.add(sample.snapshot().report().capturedAt());
                track.readings.add(sample.snapshot().cpu().get(row.thread().id()));
            }
        }
        return tracks.stream()
                .map(track -> new Ranked(track.dto(), track.acquisitionStreak()))
                .sorted(Comparator.comparingInt(Ranked::acquisitionStreak)
                        .reversed()
                        .thenComparing(Comparator.comparingLong(
                                        (Ranked row) -> row.report().cpuDeltaNanos() == null
                                                ? -1L
                                                : row.report().cpuDeltaNanos())
                                .reversed())
                        .thenComparingLong(row -> row.report().threadId())
                        .thenComparingLong(row -> row.report().firstObservedAt()))
                .map(Ranked::report)
                .toList();
    }

    record Sample(ThreadSnapshot snapshot, List<CapturedThreadDto> rows) {}

    private record Ranked(ThreadComparisonDto report, int acquisitionStreak) {}

    private static final class Track {
        private int lastIndex = -1;
        private final List<CapturedThreadDto> rows = new ArrayList<>();
        private final List<Long> times = new ArrayList<>();
        private final List<ThreadSnapshot.CpuReading> readings = new ArrayList<>();

        private int acquisitionStreak() {
            int current = 0;
            int longest = 0;
            for (CapturedThreadDto row : rows) {
                current = ThreadContentionAnalysis.supported(row) ? current + 1 : 0;
                longest = Math.max(longest, current);
            }
            return longest;
        }

        private ThreadComparisonDto dto() {
            CapturedThreadDto last = rows.get(rows.size() - 1);
            List<String> signature = last.thread().stackTrace();
            boolean comparable = ThreadSnapshot.hasComparableStack(last);
            int matches = 0;
            int streak = 0;
            int longest = 0;
            int stateChanges = 0;
            String previousState = null;
            for (CapturedThreadDto row : rows) {
                if (comparable && signature.equals(row.thread().stackTrace())) {
                    matches++;
                    longest = Math.max(longest, ++streak);
                } else {
                    streak = 0;
                }
                if (previousState != null && !previousState.equals(row.thread().state())) {
                    stateChanges++;
                }
                previousState = row.thread().state();
            }
            String unavailable = null;
            Long delta = null;
            Long elapsed = null;
            if (readings.size() < 2) {
                unavailable = "At least two consecutive eligible observations are required.";
            } else {
                for (int i = 0; i < readings.size(); i++) {
                    var reading = readings.get(i);
                    var previous = i == 0 ? null : readings.get(i - 1);
                    if (reading == null || reading.cpuNanos() < 0) {
                        unavailable = "Thread CPU time is unsupported, disabled, or unavailable.";
                        break;
                    }
                    if (previous != null
                            && (reading.cpuNanos() < previous.cpuNanos()
                                    || reading.readAtNanos() <= previous.readAtNanos())) {
                        unavailable = "CPU counters reset or the reading interval is invalid; identity is uncertain.";
                        break;
                    }
                }
                if (unavailable == null) {
                    delta = readings.get(readings.size() - 1).cpuNanos()
                            - readings.get(0).cpuNanos();
                    elapsed = readings.get(readings.size() - 1).readAtNanos()
                            - readings.get(0).readAtNanos();
                }
            }
            String interpretation = !comparable
                    ? "Stack comparison is unavailable; hidden, masked, or absent frames are not fingerprinted."
                    : "The last visible stack occurred in " + matches + " observations, with a longest consecutive"
                            + " observed streak of " + longest
                            + ". This does not prove a continuous hang or busy loop.";
            return new ThreadComparisonDto(
                    last.observationId(),
                    last.thread().id(),
                    last.thread().name(),
                    rows.size(),
                    matches,
                    longest,
                    stateChanges,
                    times.get(0),
                    times.get(times.size() - 1),
                    delta,
                    elapsed,
                    delta == null ? null : 100.0 * delta / elapsed,
                    unavailable,
                    interpretation);
        }
    }
}
