package io.github.jdubois.bootui.engine.codepaths;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Adaptive exclusion ({@code docs/PLAN-v2.md} §5.13): examples, sparse heavy requests, then seeded random fragments
 * against a reference.
 */
class AdaptiveExclusionTests {

    private static final long SECOND = 1_000_000_000L;
    private static final long MILLI = 1_000_000L;

    @Test
    void aFrequentCheapMethodIsExcludedOnceItsWindowEnds() {
        AdaptiveExclusion exclusion = new AdaptiveExclusion();
        // 60,000 calls of a 1 µs getter, 10 calls of a slow service method, in a fragment of 50 ms.
        exclusion.record(
                fragment(0L, 50 * MILLI, new int[] {7, 8}, new long[] {60_000L, 10L}, new long[] {
                    60_000_000L, 50_000_000L
                }),
                0L);

        assertThat(exclusion.evaluate(SECOND / 2))
                .as("under 100 ms recorded: the window is still open")
                .isEmpty();
        assertThat(exclusion.evaluate(SECOND)).containsExactly(7);
        assertThat(exclusion.isExcluded(7)).isTrue();
        assertThat(exclusion.isExcluded(8)).isFalse();

        exclusion.record(fragment(0L, SECOND, new int[] {7}, new long[] {90_000L}, new long[] {1L}), 2 * SECOND);
        assertThat(exclusion.evaluate(3 * SECOND)).as("never evaluated again").isEmpty();
        assertThat(exclusion.excluded()).containsExactly(7);
    }

    @Test
    void aFrequentMethodThatIsNotCheapStays() {
        AdaptiveExclusion exclusion = new AdaptiveExclusion();
        exclusion.record(
                fragment(0L, SECOND, new int[] {1}, new long[] {100_000L}, new long[] {100_000L * 2_000L}), 0L);

        assertThat(exclusion.evaluate(0L)).isEmpty();
    }

    @Test
    void theRateIsOverTheTimeTheFragmentsRecorded() {
        AdaptiveExclusion exclusion = new AdaptiveExclusion();
        // 60,000 calls in fragments that recorded two seconds: 30,000 a second, however close together they drained.
        exclusion.record(fragment(0L, SECOND, new int[] {1}, new long[] {30_000L}, new long[] {30_000L}), 0L);
        exclusion.record(fragment(0L, SECOND, new int[] {1}, new long[] {30_000L}, new long[] {30_000L}), 0L);

        assertThat(exclusion.evaluate(0L)).isEmpty();
        assertThat(exclusion.evaluate(10 * SECOND)).isEmpty();
    }

    @Test
    void aSparseHeavyRequestIsJudgedOnItsOwnFragment() {
        AdaptiveExclusion exclusion = new AdaptiveExclusion();
        // A request a minute: 300 ms in which a cheap mapper ran a million times, 100 ns each, beside one slow call.
        long now = 0L;
        for (int request = 0; request < 3 && exclusion.excluded().isEmpty(); request++) {
            now += 60 * SECOND;
            exclusion.record(
                    fragment(now - 300 * MILLI, now, new int[] {3, 4}, new long[] {1_000_000L, 1L}, new long[] {
                        100_000_000L, 200_000_000L
                    }),
                    now);
            assertThat(exclusion.evaluate(now)).as("request %d", request).containsExactly(3);
        }
        assertThat(exclusion.excluded()).containsExactly(3);
        assertThat(exclusion.isExcluded(4)).isFalse();
    }

    @Test
    void sparseShortFragmentsAddUpUntilTheyRecordedEnough() {
        AdaptiveExclusion exclusion = new AdaptiveExclusion();
        // A request every ten seconds, each 40 ms with 4,000 cheap calls: 100,000 a second of recorded time.
        long now = 0L;
        List<Integer> newly = List.of();
        int requests = 0;
        while (newly.isEmpty() && requests < 10) {
            now += 10 * SECOND;
            exclusion.record(
                    fragment(now - 40 * MILLI, now, new int[] {5}, new long[] {4_000L}, new long[] {400_000L}), now);
            newly = exclusion.evaluate(now);
            requests++;
        }
        // The first fragment's 4,000 calls are under the minimum; when the window ends at the second, its method has
        // 8,000 calls in 80 ms of recorded time, however far apart the requests were.
        assertThat(newly).containsExactly(5);
        assertThat(requests).isEqualTo(2);
    }

    @Test
    void aHandfulOfCallsInATinyFragmentIsNoRate() {
        AdaptiveExclusion exclusion = new AdaptiveExclusion();
        exclusion.record(fragment(0L, 10_000L, new int[] {6}, new long[] {3L}, new long[] {300L}), 0L);

        assertThat(exclusion.evaluate(10 * SECOND)).isEmpty();
    }

    @Test
    void aMethodInSeveralNodesOfAFragmentCountsItsDurationOnce() {
        AdaptiveExclusion exclusion = new AdaptiveExclusion();
        // 6,000 calls under two parents in one 100 ms fragment: 60,000 a second, not 30,000.
        Blobs blobs = Blobs.request(1L, 1L).between(0L, 100 * MILLI);
        blobs.node(-1, 1, 0, 1L, 100 * MILLI, 6_000_000L);
        blobs.node(0, 2, 0, 3_000L, 3_000_000L, 0L);
        blobs.node(-1, 9, 0, 1L, 1L, 0L);
        blobs.node(2, 2, 0, 3_000L, 3_000_000L, 0L);
        exclusion.record(blobs.fragment(), 0L);

        assertThat(exclusion.evaluate(0L)).containsExactly(2);
    }

    @Test
    void randomFragmentsMatchTheReference() {
        for (long seed = 1; seed <= 300; seed++) {
            Random random = new Random(seed);
            AdaptiveExclusion exclusion = new AdaptiveExclusion();
            Reference reference = new Reference();
            long now = 0L;
            for (int step = 0; step < 60; step++) {
                long duration = random.nextInt(4) == 0 ? random.nextInt(300) * MILLI : random.nextInt(2_000_000);
                int nodes = 1 + random.nextInt(6);
                int[] methods = new int[nodes];
                long[] calls = new long[nodes];
                long[] totals = new long[nodes];
                for (int n = 0; n < nodes; n++) {
                    methods[n] = random.nextInt(8) == 0 ? CodePathFragment.OTHER : random.nextInt(12);
                    calls[n] = 1 + random.nextInt(random.nextBoolean() ? 50 : 20_000);
                    totals[n] = calls[n] * random.nextInt(4_000);
                }
                now += random.nextInt(400) * MILLI;
                exclusion.record(fragment(now - duration, now, methods, calls, totals), now);
                reference.record(duration, methods, calls, totals, now);
                assertThat(exclusion.evaluate(now))
                        .as("seed %d step %d", seed, step)
                        .isEqualTo(reference.evaluate(now));
            }
            assertThat(new HashSet<>(exclusion.excluded())).as("seed %d", seed).isEqualTo(reference.excluded);
        }
    }

    /** A straightforward model of the rule, in doubles. */
    private static final class Reference {

        final Map<Integer, double[]> sums = new HashMap<>();
        final Set<Integer> due = new LinkedHashSet<>();
        final Set<Integer> excluded = new HashSet<>();
        long windowStart = -1L;

        void record(long duration, int[] methods, long[] calls, long[] totals, long now) {
            if (windowStart < 0) {
                windowStart = now;
            }
            Set<Integer> seen = new HashSet<>();
            for (int n = 0; n < methods.length; n++) {
                if (methods[n] < 0 || excluded.contains(methods[n])) {
                    continue;
                }
                double[] sum = sums.computeIfAbsent(methods[n], ignored -> new double[3]);
                sum[0] += calls[n];
                sum[1] += totals[n];
                if (seen.add(methods[n])) {
                    sum[2] += duration;
                    if (sum[2] >= 0.1 * SECOND) {
                        due.add(methods[n]);
                    }
                }
            }
        }

        List<Integer> evaluate(long now) {
            boolean windowEnded = windowStart >= 0 && now - windowStart >= SECOND;
            List<Integer> newly = new ArrayList<>();
            for (Integer id : new ArrayList<>(sums.keySet())) {
                double[] sum = sums.get(id);
                if (!due.contains(id) && !(windowEnded && sum[0] >= 5_000)) {
                    continue;
                }
                sums.remove(id);
                double seconds = sum[2] / SECOND;
                boolean frequent = sum[0] >= 5_000 && (seconds == 0 || sum[0] / seconds > 50_000);
                if (frequent && sum[1] / sum[0] < 2_000) {
                    newly.add(id);
                }
            }
            due.clear();
            if (windowEnded) {
                windowStart = now;
            }
            newly.sort(Integer::compare);
            excluded.addAll(newly);
            return newly;
        }
    }

    private static CodePathFragment fragment(long start, long end, int[] methods, long[] calls, long[] totals) {
        Blobs blobs = Blobs.request(1L, 1L).between(start, end);
        for (int i = 0; i < methods.length; i++) {
            blobs.node(-1, methods[i], 0, calls[i], totals[i], 0L);
        }
        return blobs.fragment();
    }
}
