package io.github.jdubois.bootui.autoconfigure.faulttolerance;

import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyDto;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.jdubois.bootui.spi.FaultTolerancePolicyProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.util.ClassUtils;

/**
 * Exposes the application's Resilience4j registries to BootUI, and subscribes BootUI's metadata-only event
 * consumers to their native event publishers.
 *
 * <p>Resilience4j ships each protection as a separate Maven module, so this class deliberately references no
 * Resilience4j type at all. Each module's binding lives in its own {@link Resilience4jRegistryReader}
 * implementation that is loaded only after {@link ClassUtils#isPresent} confirms that module's registry type
 * — because verifying a class eagerly resolves the types in its method signatures, a single reader holding
 * all five modules would fail to load whenever any one of them is absent.</p>
 *
 * <p>Capture is registration-only: BootUI adds consumers to Resilience4j's additive event publishers and
 * never replaces the application's own consumers, decorates a call, or changes a policy's behaviour.</p>
 */
public class Resilience4jPolicyProvider implements FaultTolerancePolicyProvider, SmartInitializingSingleton {

    private static final String CIRCUIT_BREAKER_REGISTRY =
            "io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry";
    private static final String RETRY_REGISTRY = "io.github.resilience4j.retry.RetryRegistry";
    private static final String RATE_LIMITER_REGISTRY = "io.github.resilience4j.ratelimiter.RateLimiterRegistry";
    private static final String BULKHEAD_REGISTRY = "io.github.resilience4j.bulkhead.BulkheadRegistry";
    private static final String THREAD_POOL_BULKHEAD_REGISTRY =
            "io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry";
    private static final String TIME_LIMITER_REGISTRY = "io.github.resilience4j.timelimiter.TimeLimiterRegistry";

    private final ListableBeanFactory beanFactory;
    private final FaultToleranceEventRecorder recorder;
    private final List<Resilience4jRegistryReader> readers;

    public Resilience4jPolicyProvider(ListableBeanFactory beanFactory, FaultToleranceEventRecorder recorder) {
        this.beanFactory = beanFactory;
        this.recorder = recorder;
        this.readers = createReaders();
    }

    private List<Resilience4jRegistryReader> createReaders() {
        List<Resilience4jRegistryReader> created = new ArrayList<>();
        // Each reader is constructed only after its own module is confirmed present. The `new` expressions are
        // deliberately not method references: a method reference would be linked before the guard runs, which
        // would load the reader — and therefore its Resilience4j types — even when the module is absent.
        ObjectProvider<?> circuitBreakers = registryProvider(CIRCUIT_BREAKER_REGISTRY);
        if (circuitBreakers != null) {
            created.add(new Resilience4jCircuitBreakerReader(circuitBreakers));
        }
        ObjectProvider<?> retries = registryProvider(RETRY_REGISTRY);
        if (retries != null) {
            created.add(new Resilience4jRetryReader(retries));
        }
        ObjectProvider<?> rateLimiters = registryProvider(RATE_LIMITER_REGISTRY);
        if (rateLimiters != null) {
            created.add(new Resilience4jRateLimiterReader(rateLimiters));
        }
        ObjectProvider<?> bulkheads = registryProvider(BULKHEAD_REGISTRY);
        if (bulkheads != null) {
            created.add(new Resilience4jBulkheadReader(bulkheads));
        }
        ObjectProvider<?> threadPoolBulkheads = registryProvider(THREAD_POOL_BULKHEAD_REGISTRY);
        if (threadPoolBulkheads != null) {
            created.add(new Resilience4jThreadPoolBulkheadReader(threadPoolBulkheads));
        }
        ObjectProvider<?> timeLimiters = registryProvider(TIME_LIMITER_REGISTRY);
        if (timeLimiters != null) {
            created.add(new Resilience4jTimeLimiterReader(timeLimiters));
        }
        return List.copyOf(created);
    }

    /** Resolves a bean provider for a registry type by name, or {@code null} when that module is absent. */
    private ObjectProvider<?> registryProvider(String registryClassName) {
        ClassLoader classLoader = beanFactory.getClass().getClassLoader();
        if (!ClassUtils.isPresent(registryClassName, classLoader)) {
            return null;
        }
        try {
            return beanFactory.getBeanProvider(ClassUtils.forName(registryClassName, classLoader));
        } catch (ClassNotFoundException | LinkageError | RuntimeException ex) {
            // A partially present Resilience4j module must never break the rest of the panel.
            return null;
        }
    }

    @Override
    public String providerId() {
        return FaultToleranceVocabulary.PROVIDER_RESILIENCE4J;
    }

    /**
     * Always {@code true}: this bean only exists when Resilience4j is on the classpath, the same signal the
     * panel catalog reports availability on. An application that has the library but no registry bean yet
     * (or only modules BootUI does not read) is reported as a present provider with no policies, rather than
     * as an absent library — and its captured events are kept rather than dropped by the engine.
     */
    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<FaultTolerancePolicyDto> policies() {
        List<FaultTolerancePolicyDto> policies = new ArrayList<>();
        for (Resilience4jRegistryReader reader : readers) {
            policies.addAll(reader.policies());
        }
        return List.copyOf(policies);
    }

    /**
     * Subscribes once every singleton exists, so resolving a registry bean here cannot pull application beans
     * into existence earlier than the application intended.
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (!recorder.isEnabled()) {
            // Capture is off (panel or feature disabled): do not attach consumers to the application's
            // publishers at all, rather than attaching consumers that would discard every event.
            return;
        }
        for (Resilience4jRegistryReader reader : readers) {
            try {
                reader.registerCapture(recorder);
            } catch (RuntimeException | LinkageError ex) {
                // Capture is best-effort: an unavailable publisher must not fail application startup.
            }
        }
    }
}
