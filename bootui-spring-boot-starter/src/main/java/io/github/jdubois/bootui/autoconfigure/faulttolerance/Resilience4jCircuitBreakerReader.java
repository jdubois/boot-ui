package io.github.jdubois.bootui.autoconfigure.faulttolerance;

import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyDto;
import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyMetricsDto;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Reads Resilience4j circuit breakers from the application's {@code CircuitBreakerRegistry} and subscribes
 * BootUI's metadata-only consumers to each breaker's native event publisher.
 *
 * <p>Loaded only when {@code resilience4j-circuitbreaker} is on the classpath (see
 * {@link Resilience4jPolicyProvider}), so an application without that module never links these types.</p>
 */
final class Resilience4jCircuitBreakerReader implements Resilience4jRegistryReader {

    private final ObjectProvider<?> registryProvider;
    private final Set<String> capturing = ConcurrentHashMap.newKeySet();

    Resilience4jCircuitBreakerReader(ObjectProvider<?> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public boolean available() {
        return registry() != null;
    }

    private CircuitBreakerRegistry registry() {
        return (CircuitBreakerRegistry) Resilience4jRegistryReader.uniqueBean(registryProvider);
    }

    @Override
    public List<FaultTolerancePolicyDto> policies() {
        CircuitBreakerRegistry registry = registry();
        if (registry == null) {
            return List.of();
        }
        List<FaultTolerancePolicyDto> policies = new ArrayList<>();
        for (CircuitBreaker breaker : registry.getAllCircuitBreakers()) {
            policies.add(toPolicy(breaker));
        }
        return policies;
    }

    private static FaultTolerancePolicyDto toPolicy(CircuitBreaker breaker) {
        CircuitBreakerConfig config = breaker.getCircuitBreakerConfig();
        CircuitBreaker.Metrics metrics = breaker.getMetrics();

        FaultToleranceSettings settings = new FaultToleranceSettings()
                .addPercent(
                        "failureRateThreshold",
                        config.getFailureRateThreshold(),
                        CircuitBreakerConfig.DEFAULT_FAILURE_RATE_THRESHOLD)
                .addPercent(
                        "slowCallRateThreshold",
                        config.getSlowCallRateThreshold(),
                        CircuitBreakerConfig.DEFAULT_SLOW_CALL_RATE_THRESHOLD)
                .add(
                        "slowCallDurationThreshold",
                        config.getSlowCallDurationThreshold(),
                        Duration.ofSeconds(CircuitBreakerConfig.DEFAULT_SLOW_CALL_DURATION_THRESHOLD))
                .add(
                        "slidingWindowType",
                        config.getSlidingWindowType(),
                        CircuitBreakerConfig.DEFAULT_SLIDING_WINDOW_TYPE)
                .add(
                        "slidingWindowSize",
                        config.getSlidingWindowSize(),
                        CircuitBreakerConfig.DEFAULT_SLIDING_WINDOW_SIZE)
                .add(
                        "minimumNumberOfCalls",
                        config.getMinimumNumberOfCalls(),
                        CircuitBreakerConfig.DEFAULT_MINIMUM_NUMBER_OF_CALLS)
                .add(
                        "permittedCallsInHalfOpenState",
                        config.getPermittedNumberOfCallsInHalfOpenState(),
                        CircuitBreakerConfig.DEFAULT_PERMITTED_CALLS_IN_HALF_OPEN_STATE)
                .addUnknownProvenance("waitDurationInOpenState", waitDurationInOpenState(config))
                .add(
                        "automaticTransitionFromOpenToHalfOpen",
                        config.isAutomaticTransitionFromOpenToHalfOpenEnabled(),
                        Boolean.FALSE);

        return new FaultTolerancePolicyDto(
                breaker.getName(),
                FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER,
                FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                FaultToleranceVocabulary.SOURCE_REGISTRY,
                null,
                breaker.getState().name(),
                settings.build(),
                new FaultTolerancePolicyMetricsDto(
                        (long) metrics.getNumberOfSuccessfulCalls(),
                        (long) metrics.getNumberOfFailedCalls(),
                        null,
                        null,
                        null,
                        metrics.getNumberOfNotPermittedCalls(),
                        normalizeRate(metrics.getFailureRate()),
                        (long) metrics.getNumberOfBufferedCalls()));
    }

    /**
     * Resilience4j models the open-state wait as an {@code IntervalFunction} rather than a plain duration,
     * so BootUI reports the first attempt's interval and marks the provenance unknown rather than guessing
     * whether a custom function was configured. A misbehaving custom function is swallowed.
     */
    private static String waitDurationInOpenState(CircuitBreakerConfig config) {
        try {
            return config.getWaitIntervalFunctionInOpenState().apply(1) + " ms";
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Resilience4j reports {@code -1} until its sliding window holds the minimum number of calls. */
    private static Double normalizeRate(float rate) {
        return rate < 0 ? null : (double) rate;
    }

    @Override
    public void registerCapture(FaultToleranceEventRecorder recorder) {
        CircuitBreakerRegistry registry = registry();
        if (registry == null) {
            return;
        }
        Resilience4jRegistryReader.registerRegistryCapture(
                registry.getEventPublisher(),
                registry.getAllCircuitBreakers(),
                entry -> subscribe(entry, recorder),
                entry -> forget(entry));
    }

    private void subscribe(CircuitBreaker breaker, FaultToleranceEventRecorder recorder) {
        if (breaker == null || !capturing.add(breaker.getName())) {
            return;
        }
        breaker.getEventPublisher()
                .onError(event -> recorder.recordNanos(
                        event.getCircuitBreakerName(),
                        FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER,
                        FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                        null,
                        FaultToleranceVocabulary.OUTCOME_ERROR,
                        null,
                        event.getElapsedDuration() == null
                                ? null
                                : event.getElapsedDuration().toNanos(),
                        FaultToleranceVocabulary.failureCategory(event.getThrowable())))
                .onCallNotPermitted(event -> recorder.record(
                        event.getCircuitBreakerName(),
                        FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER,
                        FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                        null,
                        FaultToleranceVocabulary.OUTCOME_SHORT_CIRCUITED,
                        null,
                        null,
                        null))
                .onStateTransition(event -> recorder.recordStateTransition(
                        event.getCircuitBreakerName(),
                        FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                        null,
                        event.getStateTransition().getToState().name()));
    }

    /**
     * Drops the name guard for an entry the registry no longer holds, so a later entry registered
     * under the same name is subscribed again instead of being mistaken for one already captured.
     */
    private void forget(CircuitBreaker breaker) {
        if (breaker != null) {
            capturing.remove(breaker.getName());
        }
    }
}
