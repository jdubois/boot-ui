package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.activity.SecurityEventCorrelationRegistry.SecurityEventCorrelation;
import io.github.jdubois.bootui.autoconfigure.activity.SecurityEventCorrelationRegistry.ThreadMatch;
import org.junit.jupiter.api.Test;

class SecurityEventCorrelationRegistryTests {

    @Test
    void classifiesEventOnServingThreadAsOurs() {
        SecurityEventCorrelationRegistry registry = new SecurityEventCorrelationRegistry(10);
        registry.record(new SecurityEventCorrelation(1_000L, "exec-1", "AUTHORIZATION_FAILURE", "admin"));

        assertThat(registry.classify("exec-1", "AUTHORIZATION_FAILURE", 1_000L, 2L))
                .isEqualTo(ThreadMatch.OURS);
    }

    @Test
    void classifiesEventOnAnotherThreadAsForeign() {
        SecurityEventCorrelationRegistry registry = new SecurityEventCorrelationRegistry(10);
        registry.record(new SecurityEventCorrelation(1_000L, "exec-2", "AUTHORIZATION_FAILURE", "admin"));

        assertThat(registry.classify("exec-1", "AUTHORIZATION_FAILURE", 1_000L, 2L))
                .isEqualTo(ThreadMatch.FOREIGN);
    }

    @Test
    void prefersOursWhenBothThreadsRecordedTheSameInstant() {
        SecurityEventCorrelationRegistry registry = new SecurityEventCorrelationRegistry(10);
        registry.record(new SecurityEventCorrelation(1_000L, "exec-2", "AUTHORIZATION_FAILURE", "admin"));
        registry.record(new SecurityEventCorrelation(1_000L, "exec-1", "AUTHORIZATION_FAILURE", "admin"));

        assertThat(registry.classify("exec-1", "AUTHORIZATION_FAILURE", 1_000L, 2L))
                .isEqualTo(ThreadMatch.OURS);
    }

    @Test
    void returnsUnknownWhenNoCaptureMatchesTypeOrWindow() {
        SecurityEventCorrelationRegistry registry = new SecurityEventCorrelationRegistry(10);
        registry.record(new SecurityEventCorrelation(1_000L, "exec-1", "AUTHENTICATION_SUCCESS", "admin"));

        // Different type.
        assertThat(registry.classify("exec-1", "AUTHORIZATION_FAILURE", 1_000L, 2L))
                .isEqualTo(ThreadMatch.UNKNOWN);
        // Outside the slack window.
        assertThat(registry.classify("exec-1", "AUTHENTICATION_SUCCESS", 1_100L, 2L))
                .isEqualTo(ThreadMatch.UNKNOWN);
        // No serving thread to anchor on.
        assertThat(registry.classify(null, "AUTHENTICATION_SUCCESS", 1_000L, 2L))
                .isEqualTo(ThreadMatch.UNKNOWN);
    }

    @Test
    void evictsOldestWhenCapacityIsExceeded() {
        SecurityEventCorrelationRegistry registry = new SecurityEventCorrelationRegistry(2);
        registry.record(new SecurityEventCorrelation(1L, "a", "T", "p"));
        registry.record(new SecurityEventCorrelation(2L, "b", "T", "p"));
        registry.record(new SecurityEventCorrelation(3L, "c", "T", "p"));

        assertThat(registry.snapshot())
                .extracting(SecurityEventCorrelation::thread)
                .containsExactly("b", "c");
    }

    @Test
    void ignoresNullRecords() {
        SecurityEventCorrelationRegistry registry = new SecurityEventCorrelationRegistry(2);
        registry.record(null);

        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void suspendForIdleClearsAndStopsRecordingUntilResumed() {
        SecurityEventCorrelationRegistry registry = new SecurityEventCorrelationRegistry(10);
        registry.record(new SecurityEventCorrelation(1L, "a", "T", "p"));

        registry.suspendForIdle();
        assertThat(registry.snapshot()).isEmpty();

        registry.record(new SecurityEventCorrelation(2L, "b", "T", "p"));
        assertThat(registry.snapshot()).isEmpty();

        registry.resumeFromIdle();
        registry.record(new SecurityEventCorrelation(3L, "c", "T", "p"));
        assertThat(registry.snapshot())
                .extracting(SecurityEventCorrelation::thread)
                .containsExactly("c");
    }
}
