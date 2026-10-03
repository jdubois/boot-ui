package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunningHandoffsTests {

    @Test
    void handoffsSharingAnExecutionIdAreKeptApartAndEndedByTheirOwnKey() {
        RunningHandoffs registry = new RunningHandoffs(8);

        long first = registry.started(running("job-1", "First"));
        long second = registry.started(running("job-1", "Second"));

        assertThat(first).isNotEqualTo(second);
        assertThat(registry.snapshot())
                .extracting(RunningHandoffs.Running::taskClass, RunningHandoffs.Running::id)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("First", first),
                        org.assertj.core.groups.Tuple.tuple("Second", second));
        registry.ended(first);
        assertThat(registry.snapshot())
                .extracting(RunningHandoffs.Running::taskClass)
                .containsExactly("Second");
        registry.ended(RunningHandoffs.NONE);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void beyondItsBoundTheOldestAreForgotten() {
        RunningHandoffs registry = new RunningHandoffs(2);

        registry.started(running("async-1", "A"));
        registry.started(running("async-2", "B"));
        registry.started(running("async-3", "C"));

        assertThat(registry.snapshot())
                .extracting(RunningHandoffs.Running::taskClass)
                .containsExactly("B", "C");
        assertThat(registry.started(running(null, "unkeyed"))).isEqualTo(RunningHandoffs.NONE);
    }

    private static RunningHandoffs.Running running(String executionId, String taskClass) {
        return new RunningHandoffs.Running("r1", executionId, null, null, "pool-1-thread-1", 1_000, taskClass, "hook");
    }
}
