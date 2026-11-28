package io.github.jdubois.bootui.quarkus4.faulttolerance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuarkusFaultToleranceCaptureTest {

    @Test
    void lifecycleCreatedBeforeFreezeRegistersNoListenerUntilAdmittedStartup() {
        Instance<QuarkusFaultTolerancePolicies> policies =
                instance(new QuarkusFaultTolerancePolicies(List.of(new RawFaultTolerancePolicy(
                        "Order#send",
                        FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER,
                        "CircuitBreaker",
                        "example.Order",
                        "send",
                        "orders",
                        List.of()))));
        QuarkusCircuitBreakerStates states = mock(QuarkusCircuitBreakerStates.class);
        Instance<QuarkusCircuitBreakerStates> stateInstances = instance(states);
        Instance<FaultToleranceEventRecorder> recorders = instance(new FaultToleranceEventRecorder(true, 10));
        try (var activation = mockStatic(BootUiNativeActivation.class)) {
            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(true);
            QuarkusFaultToleranceCapture capture =
                    new QuarkusFaultToleranceCapture(policies, stateInstances, recorders);
            capture.onStart(null);
            verifyNoInteractions(policies, stateInstances, recorders, states);

            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(false);
            capture.onStart(null);
            verify(states).onStateChange(eq("orders"), any());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance(T value) {
        Instance<T> instance = mock(Instance.class);
        when(instance.get()).thenReturn(value);
        return instance;
    }
}
