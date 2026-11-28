package io.github.jdubois.bootui.quarkus4.faulttolerance;

import java.util.function.Consumer;

/**
 * Neutral read-only view over SmallRye Fault Tolerance's circuit-breaker maintenance API, so
 * {@link QuarkusFaultTolerancePolicyProvider} and {@link QuarkusFaultToleranceCapture} never name a
 * {@code io.smallrye.faulttolerance} type and stay loadable in an application without the extension (R2).
 *
 * <p>The only implementation, {@link SmallRyeCircuitBreakerStates}, is kept out of bean discovery unless the
 * {@code SMALLRYE_FAULT_TOLERANCE} capability is present.</p>
 *
 * <p>Deliberately read-only: SmallRye's {@code CircuitBreakerMaintenance} also exposes {@code reset} and
 * {@code resetAll}, which are policy mutations and therefore out of scope for this panel.</p>
 */
public interface QuarkusCircuitBreakerStates {

    /**
     * The named breaker's current state as a {@code FaultToleranceVocabulary} constant, or {@code null} when the
     * name is blank or SmallRye does not know it (only breakers carrying {@code @CircuitBreakerName} are
     * queryable).
     */
    String state(String circuitBreakerName);

    /**
     * Registers a fail-open state-change listener for the named breaker. The consumer receives the new state
     * as a {@code FaultToleranceVocabulary} constant. Unknown names are ignored.
     */
    void onStateChange(String circuitBreakerName, Consumer<String> listener);
}
