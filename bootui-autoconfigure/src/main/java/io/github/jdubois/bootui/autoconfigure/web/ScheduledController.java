package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiDtos.ScheduledReport;
import io.github.jdubois.bootui.core.BootUiDtos.ScheduledTaskDto;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.scheduling.config.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnClass(name = "org.springframework.scheduling.config.ScheduledTaskHolder")
@RequestMapping("/bootui/api/scheduled")
public class ScheduledController {

    private final ObjectProvider<ScheduledTaskHolder> scheduledTaskHolderProvider;

    private final int maxScheduled;

    public ScheduledController(ObjectProvider<ScheduledTaskHolder> scheduledTaskHolderProvider) {
        this(scheduledTaskHolderProvider, new BootUiProperties());
    }

    @Autowired
    public ScheduledController(ObjectProvider<ScheduledTaskHolder> scheduledTaskHolderProvider, BootUiProperties properties) {
        this.scheduledTaskHolderProvider = scheduledTaskHolderProvider;
        this.maxScheduled = Math.max(0, properties.getLimits().getMaxScheduled());
    }

    @GetMapping
    public ScheduledReport scheduled() {
        ScheduledTaskHolder holder = scheduledTaskHolderProvider.getIfAvailable();
        if (holder == null) {
            return new ScheduledReport(false, 0, false, List.of());
        }
        Set<ScheduledTask> scheduledTasks = holder.getScheduledTasks();
        List<ScheduledTaskDto> tasks = scheduledTasks == null ? List.of() : scheduledTasks.stream()
                .map(this::toDto)
                .sorted(Comparator.comparing(ScheduledTaskDto::runnable, Comparator.nullsLast(String::compareTo)))
                .toList();
        int total = tasks.size();
        boolean truncated = total > maxScheduled;
        if (truncated) {
            tasks = new ArrayList<>(tasks.subList(0, maxScheduled));
        }
        return new ScheduledReport(true, total, truncated, tasks);
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
            return new ScheduledTaskDto(runnableName, "FIXED_RATE", Long.toString(intervalMs), initialDelayMs,
                    intervalUnit(intervalMs, initialDelayMs));
        }
        if (task instanceof FixedDelayTask fixedDelayTask) {
            long intervalMs = fixedDelayTask.getIntervalDuration().toMillis();
            Long initialDelayMs = toMillis(fixedDelayTask.getInitialDelayDuration());
            return new ScheduledTaskDto(runnableName, "FIXED_DELAY", Long.toString(intervalMs), initialDelayMs,
                    intervalUnit(intervalMs, initialDelayMs));
        }
        if (task instanceof OneTimeTask oneTimeTask) {
            Long initialDelayMs = toMillis(oneTimeTask.getInitialDelayDuration());
            return new ScheduledTaskDto(runnableName, "ONE_SHOT", null, initialDelayMs,
                    intervalUnit(null, initialDelayMs));
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
        if (description != null && !description.isBlank()
                && !description.equals(typeName)
                && !description.startsWith(typeName + "@")) {
            return description;
        }
        return typeName;
    }

    private Long toMillis(Duration duration) {
        return duration == null ? null : duration.toMillis();
    }

    private String intervalUnit(Long intervalMs, Long initialDelayMs) {
        if (isWholeSeconds(intervalMs) && isWholeSeconds(initialDelayMs)) {
            return "s";
        }
        return "ms";
    }

    private boolean isWholeSeconds(Long value) {
        return value == null || value % 1000 == 0;
    }
}
