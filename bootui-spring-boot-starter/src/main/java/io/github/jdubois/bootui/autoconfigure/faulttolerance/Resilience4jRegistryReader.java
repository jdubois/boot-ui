package io.github.jdubois.bootui.autoconfigure.faulttolerance;

import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyDto;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.resilience4j.core.Registry;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;

/**
 * One Resilience4j module's read + capture binding.
 *
 * <p>Resilience4j ships each protection as its own Maven module, so an application may have
 * {@code resilience4j-retry} without {@code resilience4j-circuitbreaker}. Each reader therefore lives in
 * its own class file and is instantiated by {@link Resilience4jPolicyProvider} only after that module's
 * registry type is confirmed present — the classloading-safety pattern the repository uses for every
 * optional dependency. No always-loaded class imports a Resilience4j type.</p>
 */
interface Resilience4jRegistryReader {

    /** Maps this module's registry entries to neutral policies; empty when no registry bean exists. */
    List<FaultTolerancePolicyDto> policies();

    /**
     * Registers BootUI's metadata-only event consumers on this module's native event publishers.
     * Resilience4j's publishers are additive, so this composes with the application's own consumers and
     * never replaces them. Implementations must be idempotent and fail-open.
     */
    void registerCapture(FaultToleranceEventRecorder recorder);

    /** Whether a registry bean for this module is actually present. */
    boolean available();

    /**
     * Resolves the single registry bean behind {@code provider}, or {@code null} when none (or more than one,
     * which Resilience4j's own Spring integration never produces) is available.
     *
     * <p>The provider is deliberately untyped: {@link Resilience4jPolicyProvider} obtains it reflectively so
     * that no always-loaded class names a Resilience4j type.</p>
     */
    static Object uniqueBean(ObjectProvider<?> provider) {
        try {
            return provider.getIfAvailable();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Subscribes {@code subscribe} to every entry a Resilience4j registry holds now and to every entry it
     * gains later, and drops the bookkeeping for entries the registry loses.
     *
     * <p>Registries are mutable: {@code replace(name, entry)} and {@code remove(name)} are part of their
     * public API and Spring Cloud CircuitBreaker uses them. Listening to {@code onEntryAdded} alone would
     * leave a replaced entry uncaptured forever — the reader's name-keyed guard would treat the replacement
     * as already captured — and would let the guard grow without bound for applications that create
     * dynamically named entries. Handling all three registry events keeps capture attached to whatever the
     * registry currently holds.</p>
     */
    static <E> void registerRegistryCapture(
            Registry.EventPublisher<E> publisher,
            Iterable<? extends E> existing,
            Consumer<E> subscribe,
            Consumer<E> forget) {
        publisher
                .onEntryAdded(event -> subscribe.accept(event.getAddedEntry()))
                .onEntryRemoved(event -> forget.accept(event.getRemovedEntry()))
                .onEntryReplaced(event -> {
                    forget.accept(event.getOldEntry());
                    subscribe.accept(event.getNewEntry());
                });
        for (E entry : existing) {
            subscribe.accept(entry);
        }
    }
}
