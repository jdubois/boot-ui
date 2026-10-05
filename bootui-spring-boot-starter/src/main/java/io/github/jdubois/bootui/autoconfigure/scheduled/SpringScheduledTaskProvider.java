package io.github.jdubois.bootui.autoconfigure.scheduled;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.ScheduledTaskDto;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTimeUnits;
import io.github.jdubois.bootui.spi.ScheduledTaskProvider;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.OneTimeTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.scheduling.config.TriggerTask;

/**
 * Spring Boot {@link ScheduledTaskProvider} backed by {@link ScheduledTaskHolder} beans.
 *
 * <p>This class is the single touch-point for the {@code org.springframework.scheduling.config.*} trigger
 * model, and it is only instantiated inside the {@code @ConditionalOnClass} nested configuration in
 * {@code BootUiEngineConfiguration}, so the scheduling types are never linked in an application without the
 * scheduling infrastructure on its classpath.</p>
 *
 * <p>The mapping and BootUI self-data filtering live here (not in the engine) on purpose: the framework
 * trigger types ({@link CronTask}/{@link FixedRateTask}/…) and the raw runnable description that
 * {@link BootUiSelfDataFilter#shouldIncludeScheduledTask} inspects are lost once a row is mapped to a
 * {@link ScheduledTaskDto}. Mapping and filtering where those still exist is byte-identical to the original
 * {@code ScheduledController}; the engine {@code ScheduledTasksService} then only sorts and wraps.</p>
 */
public final class SpringScheduledTaskProvider implements ScheduledTaskProvider {

    private final List<ScheduledTaskHolder> scheduledTaskHolders;

    private final BootUiSelfDataFilter selfDataFilter;

    public SpringScheduledTaskProvider(
            List<ScheduledTaskHolder> scheduledTaskHolders, BootUiSelfDataFilter selfDataFilter) {
        this.scheduledTaskHolders = scheduledTaskHolders;
        this.selfDataFilter = selfDataFilter;
    }

    @Override
    public boolean available() {
        return !scheduledTaskHolders.isEmpty();
    }

    @Override
    public List<ScheduledTaskDto> tasks() {
        return scheduledTaskHolders.stream()
                .map(ScheduledTaskHolder::getScheduledTasks)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(this::toDto)
                .filter(task -> selfDataFilter.shouldIncludeScheduledTask(task.runnable()))
                .toList();
    }

    private ScheduledTaskDto toDto(ScheduledTask scheduledTask) {
        Task task = scheduledTask.getTask();
        Runnable runnable = task.getRunnable();
        String runnableName = runnableName(runnable);
        if (task instanceof CronTask cronTask) {
            return new ScheduledTaskDto(runnableName, "CRON", cronTask.getExpression(), null, null);
        }
        if (task instanceof FixedRateTask fixedRateTask) {
            long intervalMs = fixedRateTask.getIntervalDuration().toMillis();
            Long initialDelayMs = toMillis(fixedRateTask.getInitialDelayDuration());
            return new ScheduledTaskDto(
                    runnableName,
                    "FIXED_RATE",
                    Long.toString(intervalMs),
                    initialDelayMs,
                    ScheduledTimeUnits.intervalUnit(intervalMs, initialDelayMs));
        }
        if (task instanceof FixedDelayTask fixedDelayTask) {
            long intervalMs = fixedDelayTask.getIntervalDuration().toMillis();
            Long initialDelayMs = toMillis(fixedDelayTask.getInitialDelayDuration());
            return new ScheduledTaskDto(
                    runnableName,
                    "FIXED_DELAY",
                    Long.toString(intervalMs),
                    initialDelayMs,
                    ScheduledTimeUnits.intervalUnit(intervalMs, initialDelayMs));
        }
        if (task instanceof OneTimeTask oneTimeTask) {
            Long initialDelayMs = toMillis(oneTimeTask.getInitialDelayDuration());
            return new ScheduledTaskDto(
                    runnableName,
                    "ONE_SHOT",
                    null,
                    initialDelayMs,
                    ScheduledTimeUnits.intervalUnit(null, initialDelayMs));
        }
        if (task instanceof TriggerTask triggerTask) {
            return new ScheduledTaskDto(runnableName, "ONE_SHOT", String.valueOf(triggerTask.getTrigger()), null, null);
        }
        return new ScheduledTaskDto(runnableName, "ONE_SHOT", null, null, null);
    }

    private String runnableName(Runnable runnable) {
        if (runnable == null) {
            return null;
        }
        String typeName = runnable.getClass().getName();
        String description = runnable.toString();
        if (description != null
                && !description.isBlank()
                && !description.equals(typeName)
                && !description.startsWith(typeName + "@")) {
            return description;
        }
        return typeName;
    }

    private Long toMillis(Duration duration) {
        return duration == null ? null : duration.toMillis();
    }
}
