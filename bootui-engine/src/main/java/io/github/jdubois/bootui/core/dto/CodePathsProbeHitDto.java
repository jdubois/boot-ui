package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One invocation a method probe recorded ({@code docs/PLAN-v2.md} §5.14, M5-8): metadata, and for a probe that records
 * shapes, its arguments' and return value's shapes (D44); never an argument or a return value.
 *
 * @param time when the invocation returned or threw, ISO-8601
 * @param durationMicros how long it ran, in microseconds; for a method returning a reactive or asynchronous result, the
 *     time to assemble it, not the work that runs later
 * @param threadKind {@code platform} or {@code virtual}
 * @param requestId the request it ran for, 16 hexadecimal digits, or {@code null} when unknown (no request, or a thread
 *     BootUI could not correlate)
 * @param outcome {@code returned} or {@code threw}
 * @param exceptionType the thrown exception's class name, or {@code null}
 * @param caller the calling frame, {@code class#method:line}: the first frame of the application's packages above the
 *     method, past proxies and interceptors, else the frame right above it; {@code null} when unknown
 * @param arguments the shapes of its first arguments, at most nine, in order; empty when its method takes none, its
 *     probe records no shapes, or this read does not show them (the probe's {@code shapesHiddenReason} says why)
 * @param argumentsNotRecorded how many arguments past the ninth have no shape; 0 when this read shows no shape
 * @param returned the return value's shape; {@code null} when it threw, returns nothing, or this read shows no shape
 * @param shapesIncomplete whether some of its shapes were lost, shown as {@code unknown}: the agent's transport was full,
 *     or a <b>Clear recording</b> ran while it was running
 */
public record CodePathsProbeHitDto(
        String time,
        double durationMicros,
        String threadKind,
        String requestId,
        String outcome,
        String exceptionType,
        String caller,
        List<CodePathsValueShapeDto> arguments,
        int argumentsNotRecorded,
        CodePathsValueShapeDto returned,
        boolean shapesIncomplete) {

    public CodePathsProbeHitDto {
        arguments = DtoCollections.immutableCopy(arguments);
    }

    /** An invocation without shapes. */
    public CodePathsProbeHitDto(
            String time,
            double durationMicros,
            String threadKind,
            String requestId,
            String outcome,
            String exceptionType,
            String caller) {
        this(time, durationMicros, threadKind, requestId, outcome, exceptionType, caller, List.of(), 0, null, false);
    }
}
