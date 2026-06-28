package io.github.jdubois.bootui.quarkus.scheduled;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.util.List;

/**
 * Quarkus recorder that replays the build-time-captured {@code @Scheduled} metadata into a runtime
 * {@link QuarkusScheduledTasks} holder.
 *
 * <p>The deployment processor's {@code registerScheduledTasks} build step scans the application's bean archive
 * for {@code @io.quarkus.scheduler.Scheduled} (and its {@code @Schedules} repeatable container) at build time,
 * builds the {@link RawScheduledTask} list, and calls {@link #create(List)} from a {@code @Record(STATIC_INIT)}
 * step; the returned {@link RuntimeValue} backs a synthetic {@code QuarkusScheduledTasks} bean. This is the
 * same build-time-capture strategy the Architecture (base packages) and Vulnerabilities (dependency inventory)
 * panels use, chosen here because the runtime {@code io.quarkus.scheduler.Scheduler} exposes only trigger ids
 * and next-fire times — neither of which the {@code ScheduledTaskDto} contract carries — while the cron/every
 * expressions and target method are known only at build time.</p>
 */
@Recorder
public class ScheduledTasksRecorder {

    /** Wraps the captured rows in a runtime holder backing the synthetic {@link QuarkusScheduledTasks} bean. */
    public RuntimeValue<QuarkusScheduledTasks> create(List<RawScheduledTask> tasks) {
        return new RuntimeValue<>(new QuarkusScheduledTasks(tasks));
    }
}
