package io.github.jdubois.bootui.spi;

import io.github.jdubois.bootui.core.dto.ScheduledTaskDto;
import java.util.List;

/**
 * Framework-neutral seam behind the Scheduled Tasks panel: it reports the host application's registered
 * scheduled tasks, already mapped to one {@link ScheduledTaskDto} per task and with BootUI's own internal
 * tasks filtered out.
 *
 * <p>The Spring Boot adapter implements this over {@code org.springframework.scheduling.config.
 * ScheduledTaskHolder} (the {@code CronTask}/{@code FixedRateTask}/{@code FixedDelayTask}/{@code OneTimeTask}
 * trigger model). The mapping and self-data filtering stay in the adapter on purpose: the raw runnable
 * description that the self-data filter inspects, and the framework-specific trigger types, are lost once a
 * row is flattened to a {@link ScheduledTaskDto} (the DTO is the stable UI contract). Performing the mapping
 * and filter where the framework types still exist is provably byte-identical to the original controller; the
 * engine {@code ScheduledTasksService} therefore owns only the framework-neutral concerns (sorting and the
 * {@code schedulingPresent}/{@code total} wrapping).</p>
 *
 * <p>The Quarkus adapter implements this over {@code @io.quarkus.scheduler.Scheduled} metadata captured at
 * <em>build time</em> (the runtime {@code io.quarkus.scheduler.Scheduler} only exposes trigger ids and
 * next-fire times, neither of which the {@link ScheduledTaskDto} contract carries), so the same contract is
 * served on both platforms.</p>
 */
public interface ScheduledTaskProvider {

    /**
     * Whether a scheduling backend is currently available. {@code false} means no scheduled-task source
     * exists (for example the scheduling infrastructure type is present but no holder beans are registered);
     * the engine then serves an empty report with {@code schedulingPresent=false}.
     */
    boolean available();

    /**
     * The mapped, self-filtered, <em>unsorted</em> scheduled tasks: one {@link ScheduledTaskDto} per task,
     * with BootUI's own tasks already removed. The engine applies the stable ordering on top of this. Returns
     * an empty list when {@link #available()} is {@code false}.
     */
    List<ScheduledTaskDto> tasks();
}
