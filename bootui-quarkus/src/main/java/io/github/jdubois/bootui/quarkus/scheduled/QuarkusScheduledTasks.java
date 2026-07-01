package io.github.jdubois.bootui.quarkus.scheduled;

import java.util.List;

/**
 * Build-time-captured holder for the host application's {@code @io.quarkus.scheduler.Scheduled} methods,
 * produced by {@code ScheduledTasksRecorder} and exposed as a synthetic CDI bean by the deployment processor
 * only when the {@code quarkus-scheduler} capability is present (and the launch mode is non-production).
 *
 * <p>{@code QuarkusScheduledTaskProvider} injects an {@code Instance<QuarkusScheduledTasks>}: when the bean is
 * absent (scheduler not on the classpath) the provider reports the panel unavailable; when present it maps the
 * {@link RawScheduledTask} rows to the neutral {@code ScheduledTaskDto} contract. The holder exists (rather
 * than injecting a raw {@code List}) so it is an unambiguous synthetic-bean type.</p>
 *
 * @param tasks the captured scheduled methods, in Jandex discovery order (the engine applies the stable sort)
 */
public record QuarkusScheduledTasks(List<RawScheduledTask> tasks) {

    public QuarkusScheduledTasks(List<RawScheduledTask> tasks) {
        this.tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
