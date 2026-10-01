package io.github.jdubois.bootui.engine.resources;

import java.time.Duration;

/**
 * Settings of §5.11's CPU ledger and resource track ({@code docs/PLAN-v2.md}): {@code bootui.resources.*}, with the same
 * keys and defaults on Spring and Quarkus.
 *
 * @param sampleInterval how often the sampler sweeps: {@code bootui.resources.sample-interval}
 * @param maxThreads the most platform threads one sweep reads: {@code bootui.resources.max-threads}
 */
public record ResourceSettings(Duration sampleInterval, int maxThreads) {

    public static final Duration DEFAULT_SAMPLE_INTERVAL = Duration.ofSeconds(1);
    public static final Duration MIN_SAMPLE_INTERVAL = Duration.ofMillis(100);
    public static final int DEFAULT_MAX_THREADS = 500;

    public ResourceSettings {
        sampleInterval = sampleInterval == null ? DEFAULT_SAMPLE_INTERVAL : sampleInterval;
        if (sampleInterval.compareTo(MIN_SAMPLE_INTERVAL) < 0) {
            throw new IllegalArgumentException(
                    "bootui.resources.sample-interval must be at least 100ms, was " + sampleInterval);
        }
        if (maxThreads < 1) {
            throw new IllegalArgumentException("bootui.resources.max-threads must be positive, was " + maxThreads);
        }
    }

    /** The defaults: one sweep a second, reading at most 500 threads. */
    public static ResourceSettings defaults() {
        return new ResourceSettings(DEFAULT_SAMPLE_INTERVAL, DEFAULT_MAX_THREADS);
    }
}
