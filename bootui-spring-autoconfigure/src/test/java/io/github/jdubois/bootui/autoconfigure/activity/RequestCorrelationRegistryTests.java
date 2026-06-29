package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationRegistry.RequestCorrelation;
import org.junit.jupiter.api.Test;

class RequestCorrelationRegistryTests {

    @Test
    void matchesUniqueRequestByWindowOverlap() {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        registry.record(new RequestCorrelation(1000, 1100, "exec-1", "GET", "/a"));
        registry.record(new RequestCorrelation(2000, 2100, "exec-2", "GET", "/a"));

        RequestCorrelation match = registry.match("get", "/a", 1010, 1090);

        assertThat(match).isNotNull();
        assertThat(match.thread()).isEqualTo("exec-1");
    }

    @Test
    void returnsNullWhenMethodOrPathDiffer() {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        registry.record(new RequestCorrelation(1000, 1100, "exec-1", "GET", "/a"));

        assertThat(registry.match("POST", "/a", 1000, 1100)).isNull();
        assertThat(registry.match("GET", "/b", 1000, 1100)).isNull();
    }

    @Test
    void returnsNullWhenMultipleCandidatesOverlap() {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        registry.record(new RequestCorrelation(1000, 1100, "exec-1", "GET", "/a"));
        registry.record(new RequestCorrelation(1050, 1150, "exec-2", "GET", "/a"));

        assertThat(registry.match("GET", "/a", 1000, 1100)).isNull();
    }

    @Test
    void doesNotMatchWhenWindowsDoNotOverlap() {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        registry.record(new RequestCorrelation(1000, 1100, "exec-1", "GET", "/a"));

        assertThat(registry.match("GET", "/a", 5000, 5100)).isNull();
    }

    @Test
    void evictsOldestBeyondCapacity() {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(2);
        registry.record(new RequestCorrelation(1000, 1100, "exec-1", "GET", "/a"));
        registry.record(new RequestCorrelation(2000, 2100, "exec-2", "GET", "/b"));
        registry.record(new RequestCorrelation(3000, 3100, "exec-3", "GET", "/c"));

        assertThat(registry.snapshot()).hasSize(2);
        assertThat(registry.match("GET", "/a", 1000, 1100)).isNull();
        assertThat(registry.match("GET", "/c", 3000, 3100)).isNotNull();
    }

    @Test
    void suspendForIdleClearsAndStopsRecordingUntilResumed() {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        registry.record(new RequestCorrelation(1000, 1100, "exec-1", "GET", "/a"));

        registry.suspendForIdle();
        assertThat(registry.snapshot()).isEmpty();

        registry.record(new RequestCorrelation(2000, 2100, "exec-2", "GET", "/b"));
        assertThat(registry.snapshot()).isEmpty();

        registry.resumeFromIdle();
        registry.record(new RequestCorrelation(3000, 3100, "exec-3", "GET", "/c"));
        assertThat(registry.snapshot()).hasSize(1);
    }
}
