package io.github.jdubois.bootui.autoconfigure.faulttolerance;

import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyDto;
import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyMetricsDto;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Reads Resilience4j thread-pool bulkheads from the application's {@code ThreadPoolBulkheadRegistry} and
 * subscribes BootUI's metadata-only consumers to each bulkhead's native event publisher.
 *
 * <p>Loaded only when {@code resilience4j-bulkhead} is on the classpath (see
 * {@link Resilience4jPolicyProvider}).</p>
 */
final class Resilience4jThreadPoolBulkheadReader implements Resilience4jRegistryReader {

    private final ObjectProvider<?> registryProvider;
    private final Set<String> capturing = ConcurrentHashMap.newKeySet();

    Resilience4jThreadPoolBulkheadReader(ObjectProvider<?> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public boolean available() {
        return registry() != null;
    }

    private ThreadPoolBulkheadRegistry registry() {
        return (ThreadPoolBulkheadRegistry) Resilience4jRegistryReader.uniqueBean(registryProvider);
    }

    @Override
    public List<FaultTolerancePolicyDto> policies() {
        ThreadPoolBulkheadRegistry registry = registry();
        if (registry == null) {
            return List.of();
        }
        List<FaultTolerancePolicyDto> policies = new ArrayList<>();
        for (ThreadPoolBulkhead bulkhead : registry.getAllBulkheads()) {
            policies.add(toPolicy(bulkhead));
        }
        return policies;
    }

    private static FaultTolerancePolicyDto toPolicy(ThreadPoolBulkhead bulkhead) {
        ThreadPoolBulkheadConfig config = bulkhead.getBulkheadConfig();

        FaultToleranceSettings settings = new FaultToleranceSettings()
                .addUnknownProvenance("coreThreadPoolSize", config.getCoreThreadPoolSize())
                .addUnknownProvenance("maxThreadPoolSize", config.getMaxThreadPoolSize())
                .add("queueCapacity", config.getQueueCapacity(), 100)
                .add("keepAliveDuration", config.getKeepAliveDuration(), java.time.Duration.ofMillis(20));

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
        ThreadPoolBulkheadRegistry registry = registry();
        if (registry == null) {
            return;
        }
        Resilience4jRegistryReader.registerRegistryCapture(
                registry.getEventPublisher(),
                registry.getAllBulkheads(),
                entry -> subscribe(entry, recorder),
                entry -> forget(entry));
    }

    /** Only rejections are captured, for the same buffer-pressure reason as the semaphore bulkhead. */
    private void subscribe(ThreadPoolBulkhead bulkhead, FaultToleranceEventRecorder recorder) {
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
    private void forget(ThreadPoolBulkhead bulkhead) {
        if (bulkhead != null) {
            capturing.remove(bulkhead.getName());
        }
    }
}
