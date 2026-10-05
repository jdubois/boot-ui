package io.github.jdubois.bootui.autoconfigure.faulttolerance;

import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyDto;
import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyMetricsDto;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Reads Resilience4j retries from the application's {@code RetryRegistry} and subscribes BootUI's
 * metadata-only consumers to each retry's native event publisher.
 *
 * <p>Loaded only when {@code resilience4j-retry} is on the classpath (see
 * {@link Resilience4jPolicyProvider}).</p>
 */
final class Resilience4jRetryReader implements Resilience4jRegistryReader {

    private final ObjectProvider<?> registryProvider;
    private final Set<String> capturing = ConcurrentHashMap.newKeySet();

    Resilience4jRetryReader(ObjectProvider<?> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public boolean available() {
        return registry() != null;
    }

    private RetryRegistry registry() {
        return (RetryRegistry) Resilience4jRegistryReader.uniqueBean(registryProvider);
    }

    @Override
    public List<FaultTolerancePolicyDto> policies() {
        RetryRegistry registry = registry();
        if (registry == null) {
            return List.of();
        }
        List<FaultTolerancePolicyDto> policies = new ArrayList<>();
        for (Retry retry : registry.getAllRetries()) {
            policies.add(toPolicy(retry));
        }
        return policies;
    }

    private static FaultTolerancePolicyDto toPolicy(Retry retry) {
        RetryConfig config = retry.getRetryConfig();
        Retry.Metrics metrics = retry.getMetrics();

        FaultToleranceSettings settings = new FaultToleranceSettings()
                .add("maxAttempts", config.getMaxAttempts(), RetryConfig.DEFAULT_MAX_ATTEMPTS)
                .addUnknownProvenance("firstRetryDelay", firstRetryDelay(config))
                .add("failAfterMaxAttempts", config.isFailAfterMaxAttempts(), Boolean.FALSE);

        long retried =
                metrics.getNumberOfSuccessfulCallsWithRetryAttempt() + metrics.getNumberOfFailedCallsWithRetryAttempt();

        return new FaultTolerancePolicyDto(
                retry.getName(),
                FaultToleranceVocabulary.TYPE_RETRY,
                FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                FaultToleranceVocabulary.SOURCE_REGISTRY,
                null,
                null,
                settings.build(),
                new FaultTolerancePolicyMetricsDto(
                        metrics.getNumberOfSuccessfulCallsWithoutRetryAttempt()
                                + metrics.getNumberOfSuccessfulCallsWithRetryAttempt(),
                        metrics.getNumberOfFailedCallsWithoutRetryAttempt()
                                + metrics.getNumberOfFailedCallsWithRetryAttempt(),
                        retried,
                        null,
                        null,
                        null,
                        null,
                        null));
    }

    /**
     * Resilience4j models the retry delay as an interval function, so BootUI reports the first attempt's
     * interval with unknown provenance rather than guessing whether a custom backoff was configured.
     */
    private static String firstRetryDelay(RetryConfig config) {
        try {
            return config.getIntervalFunction().apply(1) + " ms";
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Override
    public void registerCapture(FaultToleranceEventRecorder recorder) {
        RetryRegistry registry = registry();
        if (registry == null) {
            return;
        }
        Resilience4jRegistryReader.registerRegistryCapture(
                registry.getEventPublisher(),
                registry.getAllRetries(),
                entry -> subscribe(entry, recorder),
                entry -> forget(entry));
    }

    private void subscribe(Retry retry, FaultToleranceEventRecorder recorder) {
        if (retry == null || !capturing.add(retry.getName())) {
            return;
        }
        retry.getEventPublisher()
                .onRetry(event -> recorder.record(
                        event.getName(),
                        FaultToleranceVocabulary.TYPE_RETRY,
                        FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                        null,
                        FaultToleranceVocabulary.OUTCOME_RETRY,
                        event.getNumberOfRetryAttempts(),
                        event.getWaitInterval() == null
                                ? null
                                : event.getWaitInterval().toMillis(),
                        FaultToleranceVocabulary.failureCategory(event.getLastThrowable())))
                .onError(event -> recorder.record(
                        event.getName(),
                        FaultToleranceVocabulary.TYPE_RETRY,
                        FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                        null,
                        FaultToleranceVocabulary.OUTCOME_RETRY_EXHAUSTED,
                        event.getNumberOfRetryAttempts(),
                        null,
                        FaultToleranceVocabulary.failureCategory(event.getLastThrowable())));
    }

    /**
     * Drops the name guard for an entry the registry no longer holds, so a later entry registered
     * under the same name is subscribed again instead of being mistaken for one already captured.
     */
    private void forget(Retry retry) {
        if (retry != null) {
            capturing.remove(retry.getName());
        }
    }
}
