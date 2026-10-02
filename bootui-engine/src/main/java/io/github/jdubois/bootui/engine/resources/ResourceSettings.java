package io.github.jdubois.bootui.engine.resources;

import java.time.Duration;

/**
 * Settings of §5.11's CPU ledger, resource track, and JFR attribution ({@code docs/PLAN-v2.md}): {@code bootui.resources.*}, with the same
 * keys and defaults on Spring and Quarkus.
 *
 * @param sampleInterval how often the sampler sweeps: {@code bootui.resources.sample-interval}
 * @param maxThreads the most platform threads one sweep reads: {@code bootui.resources.max-threads}
 * @param jfrMaxDuration how long a <b>Profile resources</b> session records: {@code bootui.resources.jfr.max-duration}
 */
public record ResourceSettings(Duration sampleInterval, int maxThreads, Duration jfrMaxDuration) {

    public static final Duration DEFAULT_SAMPLE_INTERVAL = Duration.ofSeconds(1);
    public static final Duration MIN_SAMPLE_INTERVAL = Duration.ofMillis(100);
    public static final int DEFAULT_MAX_THREADS = 500;
    public static final Duration DEFAULT_JFR_MAX_DURATION = Duration.ofSeconds(30);
    public static final Duration MIN_JFR_MAX_DURATION = Duration.ofSeconds(1);
    public static final Duration MAX_JFR_MAX_DURATION = Duration.ofMinutes(10);

    public ResourceSettings {
        sampleInterval = sampleInterval == null ? DEFAULT_SAMPLE_INTERVAL : sampleInterval;
        if (sampleInterval.compareTo(MIN_SAMPLE_INTERVAL) < 0) {
            throw new IllegalArgumentException(
                    "bootui.resources.sample-interval must be at least 100ms, was " + sampleInterval);
        }
        if (maxThreads < 1) {
            throw new IllegalArgumentException("bootui.resources.max-threads must be positive, was " + maxThreads);
        }
        jfrMaxDuration = jfrMaxDuration == null ? DEFAULT_JFR_MAX_DURATION : jfrMaxDuration;
        if (jfrMaxDuration.compareTo(MIN_JFR_MAX_DURATION) < 0 || jfrMaxDuration.compareTo(MAX_JFR_MAX_DURATION) > 0) {
            throw new IllegalArgumentException(
                    "bootui.resources.jfr.max-duration must be between 1s and 10m, was " + jfrMaxDuration);
        }
    }

    /** The sampler's settings, with the default session length. */
    public ResourceSettings(Duration sampleInterval, int maxThreads) {
        this(sampleInterval, maxThreads, DEFAULT_JFR_MAX_DURATION);
    }

    /** The defaults: one sweep a second, reading at most 500 threads, and sessions of 30 seconds. */
    public static ResourceSettings defaults() {
        return new ResourceSettings(DEFAULT_SAMPLE_INTERVAL, DEFAULT_MAX_THREADS, DEFAULT_JFR_MAX_DURATION);
    }
}
