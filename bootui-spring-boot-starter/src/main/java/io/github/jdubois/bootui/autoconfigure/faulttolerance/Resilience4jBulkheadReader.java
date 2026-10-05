package io.github.jdubois.bootui.autoconfigure.faulttolerance;

import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyDto;
import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyMetricsDto;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Reads Resilience4j semaphore bulkheads from the application's {@code BulkheadRegistry} and subscribes
 * BootUI's metadata-only consumers to each bulkhead's native event publisher.
 *
 * <p>Loaded only when {@code resilience4j-bulkhead} is on the classpath (see
 * {@link Resilience4jPolicyProvider}). Thread-pool bulkheads live in the same Maven module but a different
 * registry type, and are read by {@link Resilience4jThreadPoolBulkheadReader}.</p>
 */
final class Resilience4jBulkheadReader implements Resilience4jRegistryReader {

    private final ObjectProvider<?> registryProvider;
    private final Set<String> capturing = ConcurrentHashMap.newKeySet();

    Resilience4jBulkheadReader(ObjectProvider<?> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public boolean available() {
        return registry() != null;
    }

    private BulkheadRegistry registry() {
        return (BulkheadRegistry) Resilience4jRegistryReader.uniqueBean(registryProvider);
    }

    @Override
    public List<FaultTolerancePolicyDto> policies() {
        BulkheadRegistry registry = registry();
        if (registry == null) {
            return List.of();
        }
        List<FaultTolerancePolicyDto> policies = new ArrayList<>();
        for (Bulkhead bulkhead : registry.getAllBulkheads()) {
            policies.add(toPolicy(bulkhead));
        }
        return policies;
    }

    private static FaultTolerancePolicyDto toPolicy(Bulkhead bulkhead) {
        BulkheadConfig config = bulkhead.getBulkheadConfig();
        Bulkhead.Metrics metrics = bulkhead.getMetrics();

        FaultToleranceSettings settings = new FaultToleranceSettings()
                .add("maxConcurrentCalls", config.getMaxConcurrentCalls(), 25)
                .add("maxWaitDuration", config.getMaxWaitDuration(), Duration.ZERO)
                .add("availableConcurrentCalls", metrics.getAvailableConcurrentCalls(), null);

        return new FaultTolerancePolicyDto(
                bulkhead.getName(),
                FaultToleranceVocabulary.TYPE_BULKHEAD,
                FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                FaultToleranceVocabulary.SOURCE_REGISTRY,
                null,
                null,
                settings.build(),
                FaultTolerancePolicyMetricsDto.none());
    }

    @Override
    public void registerCapture(FaultToleranceEventRecorder recorder) {
        BulkheadRegistry registry = registry();
        if (registry == null) {
            return;
        }
        Resilience4jRegistryReader.registerRegistryCapture(
                registry.getEventPublisher(),
                registry.getAllBulkheads(),
                entry -> subscribe(entry, recorder),
                entry -> forget(entry));
    }

    /**
     * Only rejections are captured. Permitted and finished events fire on every call through the bulkhead,
     * which would flood the bounded buffer without adding diagnostic value.
     */
    private void subscribe(Bulkhead bulkhead, FaultToleranceEventRecorder recorder) {
        if (bulkhead == null || !capturing.add(bulkhead.getName())) {
            return;
        }
        bulkhead.getEventPublisher()
                .onCallRejected(event -> recorder.record(
                        event.getBulkheadName(),
                        FaultToleranceVocabulary.TYPE_BULKHEAD,
                        FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                        null,
                        FaultToleranceVocabulary.OUTCOME_REJECTED,
                        null,
                        null,
                        null));
    }

    /**
     * Drops the name guard for an entry the registry no longer holds, so a later entry registered
     * under the same name is subscribed again instead of being mistaken for one already captured.
     */
    private void forget(Bulkhead bulkhead) {
        if (bulkhead != null) {
            capturing.remove(bulkhead.getName());
        }
    }
}
