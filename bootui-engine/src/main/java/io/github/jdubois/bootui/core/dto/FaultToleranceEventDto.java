package io.github.jdubois.bootui.core.dto;

/**
 * One captured, metadata-only fault tolerance event.
 *
 * <p>Events explain <em>why</em> a call was retried, rejected, timed out or short-circuited. They never
 * carry method arguments, return values, payloads or raw exception messages: only the safe failure
 * <em>category</em> (an exception's simple class name) is retained.</p>
 *
 * @param id stable identifier, unique within the capture buffer
 * @param timestamp epoch milliseconds when the event occurred
 * @param policyName the owning policy's name
 * @param policyType neutral policy type, matching {@link FaultTolerancePolicyDto#type()}
 * @param provider library that emitted the event, matching {@link FaultTolerancePolicyDto#provider()}
 * @param target protected operation when known, or {@code null}
 * @param outcome neutral outcome: {@code SUCCESS}, {@code ERROR}, {@code RETRY}, {@code RETRY_EXHAUSTED},
 *     {@code REJECTED}, {@code TIMEOUT}, {@code SHORT_CIRCUITED}, {@code STATE_TRANSITION} or
 *     {@code FALLBACK}
 * @param attempt 1-based attempt number for retry events, or {@code null} when not applicable
 * @param durationMillis wall-clock duration the library reported, or {@code null}
 * @param failureCategory safe failure category (an exception's simple class name), never a raw exception
 *     message; {@code null} for non-failure outcomes
 * @param state the circuit-breaker state the policy entered, for {@code STATE_TRANSITION} events only;
 *     {@code null} otherwise
 * @param traceId trace id active when the event was captured, or {@code null}
 */
public record FaultToleranceEventDto(
        String id,
        long timestamp,
        String policyName,
        String policyType,
        String provider,
        String target,
        String outcome,
        Integer attempt,
        Long durationMillis,
        String failureCategory,
        String state,
        String traceId) {}
