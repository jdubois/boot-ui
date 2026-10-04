package io.github.jdubois.bootui.core.dto;

/**
 * One invocation a method probe recorded ({@code docs/PLAN-v2.md} §5.14, M5-8): metadata only, never an argument or a
 * return value.
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
 */
public record CodePathsProbeHitDto(
        String time,
        double durationMicros,
        String threadKind,
        String requestId,
        String outcome,
        String exceptionType,
        String caller) {}
