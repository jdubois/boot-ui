package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One configured fault tolerance policy: which protection applies to which operation, how it is configured, and
 * what its current runtime state is.
 *
 * <p>The contract is deliberately library-neutral so a Resilience4j circuit breaker, a Spring Retry
 * {@code @Retryable} method and a SmallRye Fault Tolerance {@code @CircuitBreaker} render through the same
 * shape. A concept a library does not model is reported as {@code null}/{@code UNKNOWN} rather than
 * guessed.</p>
 *
 * @param name policy name; the registry entry name where the library has one, otherwise the annotated
 *     operation's derived name. Unique together with {@code type} and {@code provider}, with one exception
 *     the libraries themselves impose: Resilience4j keeps semaphore bulkheads and thread-pool bulkheads in
 *     two independent registries that may both hold the same name, and both map to {@code BULKHEAD} because
 *     that is what they are. Such a pair renders as two rows telling their settings apart (a semaphore
 *     bulkhead reports {@code maxConcurrentCalls}, a thread-pool bulkhead reports its pool sizes) rather
 *     than as one merged approximation.
 * @param type neutral policy type: {@code CIRCUIT_BREAKER}, {@code RETRY}, {@code RATE_LIMITER},
 *     {@code BULKHEAD}, {@code TIME_LIMITER} or {@code FALLBACK}
 * @param provider library that owns the policy: {@code resilience4j}, {@code spring-retry} or
 *     {@code smallrye-fault-tolerance}
 * @param source how BootUI discovered it: {@code REGISTRY}, {@code ANNOTATION} or {@code CONFIGURATION}
 * @param target protected bean/class and method (for example {@code com.example.PaymentClient#charge}), or
 *     {@code null} when the library exposes a registry entry with no single owning operation
 * @param state current runtime state for a circuit breaker ({@code CLOSED}, {@code OPEN},
 *     {@code HALF_OPEN}, {@code DISABLED}, {@code FORCED_OPEN}), {@code UNKNOWN} when the library exposes
 *     no state, or {@code null} for policy types that have no state concept
 * @param settings effective configuration with provenance, in stable order
 * @param metrics bounded runtime counters; every counter is nullable when unreported
 */
public record FaultTolerancePolicyDto(
        String name,
        String type,
        String provider,
        String source,
        String target,
        String state,
        List<FaultTolerancePolicySettingDto> settings,
        FaultTolerancePolicyMetricsDto metrics) {

    public FaultTolerancePolicyDto {
        settings = DtoCollections.immutableCopy(settings);
    }
}
