package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One method probe ({@code docs/PLAN-v2.md} §5.14, M5-8): its method, its state and bounds, and the invocations it
 * recorded, metadata only.
 *
 * @param id the probe's id, as {@code get_method_probe} takes it
 * @param method the method key, {@code class#name(descriptor)}, the descriptor resolved once the probe was installed
 * @param className the class's binary name
 * @param methodName the method's name
 * @param descriptor the method's descriptor, or {@code null} until resolved
 * @param state {@code starting}, {@code active}, {@code ending} (its advice is being removed), {@code ended}, or
 *     {@code failed}
 * @param endReason why it ended: {@code invocations}, {@code window}, {@code stopped}, or {@code run-ended}; or
 *     {@code null}
 * @param failure why it failed, or {@code null}
 * @param removal {@code removed}, or why its advice could not be removed (it records nothing either way), or
 *     {@code null} while installed
 * @param waitingForClass whether it is active but its class has not been loaded by this run yet
 * @param async whether the method returns a reactive or asynchronous result, so its durations time the assembly only
 * @param maxInvocations the most invocations it records
 * @param windowSeconds the longest it records, from its activation
 * @param requestedAt when it was started, ISO-8601
 * @param endsAt when its window ends, ISO-8601, or {@code null} until activated
 * @param endedAt when it ended, ISO-8601, or {@code null}
 * @param invocations how many invocations it counted, at most {@code maxInvocations}
 * @param recorded how many it recorded
 * @param dropped how many it could not record: the agent's transport was full
 * @param hits the invocations recorded, oldest first
 */
public record CodePathsProbeDto(
        String id,
        String method,
        String className,
        String methodName,
        String descriptor,
        String state,
        String endReason,
        String failure,
        String removal,
        boolean waitingForClass,
        boolean async,
        int maxInvocations,
        long windowSeconds,
        String requestedAt,
        String endsAt,
        String endedAt,
        int invocations,
        int recorded,
        int dropped,
        List<CodePathsProbeHitDto> hits) {

    public CodePathsProbeDto {
        hits = DtoCollections.immutableCopy(hits);
    }
}
