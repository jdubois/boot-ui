package io.github.jdubois.bootui.core.dto;

/**
 * Summary of a @Scheduled task registered in the application context.
 */
public record ScheduledTaskDto(
        String runnable, String triggerType, String expression, Long initialDelayMs, String timeUnit) {}
