package io.github.jdubois.bootui.engine.journal;

import java.util.Objects;

/**
 * One of a run's slowest startup steps ({@code docs/PLAN-v2.md} §5.18), such as a bean's instantiation.
 *
 * @param name the step's name, such as {@code spring.beans.instantiate}
 * @param bean the bean it created, or {@code null}
 * @param durationNanos how long it took, its nested steps included
 */
public record StartupStepTiming(String name, String bean, long durationNanos) {

    public StartupStepTiming {
        Objects.requireNonNull(name, "name");
    }
}
