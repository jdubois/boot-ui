package io.github.jdubois.bootui.autoconfigure.faulttolerance;

import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyDto;
import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyMetricsDto;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Reads Resilience4j time limiters from the application's {@code TimeLimiterRegistry} and subscribes
 * BootUI's metadata-only consumers to each limiter's native event publisher.
 *
 * <p>Loaded only when {@code resilience4j-timelimiter} is on the classpath (see
 * {@link Resilience4jPolicyProvider}).</p>
 */
final class Resilience4jTimeLimiterReader implements Resilience4jRegistryReader {

    private final ObjectProvider<?> registryProvider;
    private final Set<String> capturing = ConcurrentHashMap.newKeySet();

    Resilience4jTimeLimiterReader(ObjectProvider<?> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public boolean available() {
        return registry() != null;
    }

    private TimeLimiterRegistry registry() {
        return (TimeLimiterRegistry) Resilience4jRegistryReader.uniqueBean(registryProvider);
    }

    @Override
    public List<FaultTolerancePolicyDto> policies() {
        TimeLimiterRegistry registry = registry();
        if (registry == null) {
            return List.of();
        }
        List<FaultTolerancePolicyDto> policies = new ArrayList<>();
        for (TimeLimiter limiter : registry.getAllTimeLimiters()) {
            policies.add(toPolicy(limiter));
        }
        return policies;
    }

    private static FaultTolerancePolicyDto toPolicy(TimeLimiter limiter) {
        FaultToleranceSettings settings = new FaultToleranceSettings()
                .add("timeoutDuration", limiter.getTimeLimiterConfig().getTimeoutDuration(), Duration.ofSeconds(1));

        return new FaultTolerancePolicyDto(
                limiter.getName(),
                FaultToleranceVocabulary.TYPE_TIME_LIMITER,
                FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                FaultToleranceVocabulary.SOURCE_REGISTRY,
                null,
                null,
                settings.build(),
                FaultTolerancePolicyMetricsDto.none());
    }

    @Override
    public void registerCapture(FaultToleranceEventRecorder recorder) {
        TimeLimiterRegistry registry = registry();
        if (registry == null) {
            return;
        }
        Resilience4jRegistryReader.registerRegistryCapture(
                registry.getEventPublisher(),
                registry.getAllTimeLimiters(),
                entry -> subscribe(entry, recorder),
                entry -> forget(entry));
    }

    /** Only timeouts are captured; successes fire on every completed call. */
    private void subscribe(TimeLimiter limiter, FaultToleranceEventRecorder recorder) {
        if (limiter == null || !capturing.add(limiter.getName())) {
            return;
        }
        limiter.getEventPublisher()
                .onTimeout(event -> recorder.record(
                        event.getTimeLimiterName(),
                        FaultToleranceVocabulary.TYPE_TIME_LIMITER,
                        FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                        null,
                        FaultToleranceVocabulary.OUTCOME_TIMEOUT,
                        null,
                        null,
                        null));
    }

    /**
     * Drops the name guard for an entry the registry no longer holds, so a later entry registered
     * under the same name is subscribed again instead of being mistaken for one already captured.
     */
    private void forget(TimeLimiter limiter) {
        if (limiter != null) {
            capturing.remove(limiter.getName());
        }
    }
}
