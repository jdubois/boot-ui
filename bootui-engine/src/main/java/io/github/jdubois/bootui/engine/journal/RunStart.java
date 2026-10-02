package io.github.jdubois.bootui.engine.journal;

import java.util.List;
import java.util.Objects;

/**
 * What a run recorded when it started ({@code docs/PLAN-v2.md} §5.18): how long it took to be ready, its slowest startup
 * steps, and the facts that decide whether two runs can be compared (§5.8). The run summary's header keeps it.
 *
 * @param readyNanos the time from the application's start to ready, or {@code null} when the framework reports none
 * @param slowestSteps the slowest startup steps, slowest first, at most {@value #MAX_STEPS}
 * @param facts the comparability facts
 */
public record RunStart(Long readyNanos, List<StartupStepTiming> slowestSteps, ComparabilityFacts facts) {

    /** The most startup steps a run keeps. */
    public static final int MAX_STEPS = 10;

    public RunStart {
        slowestSteps = slowestSteps == null ? List.of() : List.copyOf(slowestSteps);
        Objects.requireNonNull(facts, "facts");
    }
}
