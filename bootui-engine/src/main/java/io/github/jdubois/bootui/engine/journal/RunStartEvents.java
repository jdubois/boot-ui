package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Publishes a run's {@link LifecyclePayload#RUN_STARTED} event when the application is ready ({@code docs/PLAN-v2.md}
 * §5.18), from the facts each adapter reads from its framework. The event starts when the application started and
 * lasts until it was ready, and it belongs to no request, execution, or thread.
 */
public final class RunStartEvents {

    private RunStartEvents() {}

    /**
     * Publishes the run's start to {@code journal}.
     *
     * @param readyAtEpochMillis when the application was ready
     * @param readyNanos the time from its start to ready, or {@code null} when the framework reports none
     * @param steps its startup steps, in any order; the slowest {@value RunStart#MAX_STEPS} are kept
     * @param activeProfiles its active profiles
     * @param jdbcUrls its data sources' raw URLs by name, reduced here to their shapes
     * @param cacheType the cache in use, or {@code null} for none
     * @param tracing whether tracing is on
     * @return whether the journal accepted the event, which it does not when the {@code lifecycle} source is off
     */
    public static boolean publish(
            RuntimeJournal journal,
            long readyAtEpochMillis,
            Long readyNanos,
            List<StartupStepTiming> steps,
            List<String> activeProfiles,
            Map<String, String> jdbcUrls,
            String cacheType,
            boolean tracing) {
        if (journal == null || !journal.records(JournalSource.LIFECYCLE)) {
            return false;
        }
        List<StartupStepTiming> slowest = steps == null
                ? List.of()
                : steps.stream()
                        .sorted(Comparator.comparingLong(StartupStepTiming::durationNanos)
                                .reversed())
                        .limit(RunStart.MAX_STEPS)
                        .toList();
        RunStart start = new RunStart(
                readyNanos,
                slowest,
                ComparabilityFacts.of(
                        activeProfiles,
                        jdbcUrls,
                        cacheType,
                        tracing,
                        journal.settings().sources()));
        long nanos = readyNanos == null ? 0 : Math.max(0, readyNanos);
        return journal.offer(RuntimeEvent.of(
                JournalSource.LIFECYCLE,
                readyAtEpochMillis - nanos / 1_000_000,
                nanos,
                CorrelationContext.NONE,
                null,
                null,
                false,
                LifecyclePayload.runStarted(start)));
    }
}
