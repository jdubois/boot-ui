package io.github.jdubois.bootui.core.dto;

/**
 * One observed proxy invocation, not proof that the target body ran. Times are relative milliseconds;
 * nested durations overlap. Exception type is omitted when the Exceptions source is disabled.
 */
public record ExplorerInvocationDto(
        String id,
        String parentId,
        String beanName,
        String typeName,
        String method,
        String role,
        double offsetMs,
        double durationMs,
        boolean failed,
        boolean slow,
        String exceptionType) {}
