package io.github.jdubois.bootui.quarkus4.faulttolerance;

import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * The SmallRye Fault Tolerance implementation of {@link QuarkusCircuitBreakerStates}.
 *
 * <p>This is the only BootUI class that imports {@code io.smallrye.faulttolerance}, so the deployment
 * processor keeps it out of bean discovery (via {@code ExcludedTypeBuildItem}) unless the
 * {@code SMALLRYE_FAULT_TOLERANCE} capability is present — the same R2 classloading gate the Cache and Kafka
 * ports use. Consumers inject the neutral interface through an {@code Instance} and tolerate its absence.</p>
 *
 * <p>SmallRye can answer for a circuit breaker only when the application gave it an explicit
 * {@code @CircuitBreakerName}; every other breaker is anonymous and raises {@code IllegalArgumentException}.
 * Rather than guessing, both methods degrade to "state unknown" so the panel can say so explicitly.</p>
 */
@Singleton
public class SmallRyeCircuitBreakerStates implements QuarkusCircuitBreakerStates {

    private static final Logger LOG = Logger.getLogger(SmallRyeCircuitBreakerStates.class);

    private final CircuitBreakerMaintenance maintenance;

    @Inject
    public SmallRyeCircuitBreakerStates(CircuitBreakerMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    @Override
    public String state(String circuitBreakerName) {
        if (circuitBreakerName == null || circuitBreakerName.isBlank()) {
            return null;
        }
        try {
            return map(maintenance.currentState(circuitBreakerName));
        } catch (RuntimeException e) {
            LOG.debugf(e, "BootUI could not read the state of circuit breaker '%s'", circuitBreakerName);
            return null;
        }
    }

    @Override
    public void onStateChange(String circuitBreakerName, Consumer<String> listener) {
        if (circuitBreakerName == null || circuitBreakerName.isBlank() || listener == null) {
            return;
        }
        try {
            maintenance.onStateChange(circuitBreakerName, state -> listener.accept(map(state)));
        } catch (RuntimeException e) {
            LOG.debugf(e, "BootUI could not observe circuit breaker '%s'", circuitBreakerName);
        }
    }

    private static String map(CircuitBreakerState state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case CLOSED -> FaultToleranceVocabulary.STATE_CLOSED;
            case OPEN -> FaultToleranceVocabulary.STATE_OPEN;
            case HALF_OPEN -> FaultToleranceVocabulary.STATE_HALF_OPEN;
        };
    }
}
