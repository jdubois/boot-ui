package io.github.jdubois.bootui.quarkus4.faulttolerance;

import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Wires SmallRye Fault Tolerance's circuit-breaker state changes into the bounded, metadata-only
 * {@link FaultToleranceEventRecorder} that backs both the Fault Tolerance panel's event list and Live Activity's
 * {@code FAULT_TOLERANCE} entries.
 *
 * <p>State transitions are the only fault tolerance events SmallRye exposes to an observer: unlike Resilience4j it
 * publishes no per-call retry/rejection/timeout stream, so the Quarkus adapter reports exactly what the
 * library supports rather than wrapping application beans or replacing interceptors to synthesize the rest.
 * Only breakers the application named with {@code @CircuitBreakerName} can be observed, which is why the
 * build-time scan records that name.</p>
 *
 * <p>Registration happens once at startup and is entirely fail-open: a breaker SmallRye does not know is
 * skipped, and the listener body cannot throw into the library because {@link FaultToleranceEventRecorder#record}
 * swallows its own failures. When capture is disabled ({@code bootui.fault-tolerance.enabled=false} or the panel
 * is off) the recorder is disabled, so no listener is registered at all.</p>
 */
@Dependent
public class QuarkusFaultToleranceCapture {

    private static final Logger LOG = Logger.getLogger(QuarkusFaultToleranceCapture.class);

    private final Instance<QuarkusFaultTolerancePolicies> capturedPolicies;

    private final Instance<QuarkusCircuitBreakerStates> circuitBreakerStates;

    private final java.util.function.Supplier<FaultToleranceEventRecorder> recorder;

    @Inject
    public QuarkusFaultToleranceCapture(
            Instance<QuarkusFaultTolerancePolicies> capturedPolicies,
            Instance<QuarkusCircuitBreakerStates> circuitBreakerStates,
            Instance<FaultToleranceEventRecorder> recorder) {
        this.capturedPolicies = capturedPolicies;
        this.circuitBreakerStates = circuitBreakerStates;
        this.recorder = recorder::get;
    }

    public QuarkusFaultToleranceCapture(
            Instance<QuarkusFaultTolerancePolicies> capturedPolicies,
            Instance<QuarkusCircuitBreakerStates> circuitBreakerStates,
            FaultToleranceEventRecorder recorder) {
        this.capturedPolicies = capturedPolicies;
        this.circuitBreakerStates = circuitBreakerStates;
        this.recorder = () -> recorder;
    }

    void onStart(@Observes StartupEvent event) {
        if (BootUiNativeActivation.isNativeInactive()
                || !recorder.get().isEnabled()
                || capturedPolicies.isUnsatisfied()
                || circuitBreakerStates.isUnsatisfied()) {
            return;
        }
        QuarkusCircuitBreakerStates states = circuitBreakerStates.get();
        Set<String> registered = new LinkedHashSet<>();
        for (RawFaultTolerancePolicy policy : capturedPolicies.get().policies()) {
            if (!FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER.equals(policy.type())
                    || policy.circuitBreakerName().isBlank()
                    || !registered.add(policy.circuitBreakerName())) {
                continue;
            }
            String name = policy.name();
            String target = policy.target();
            states.onStateChange(policy.circuitBreakerName(), state -> {
                if (!BootUiNativeActivation.isNativeInactive()) {
                    recorder.get()
                            .recordStateTransition(
                                    name,
                                    FaultToleranceVocabulary.PROVIDER_SMALLRYE_FAULT_TOLERANCE,
                                    target.isBlank() ? null : target,
                                    state);
                }
            });
        }
        LOG.debugf("BootUI observes %d named circuit breaker(s)", registered.size());
    }
}
