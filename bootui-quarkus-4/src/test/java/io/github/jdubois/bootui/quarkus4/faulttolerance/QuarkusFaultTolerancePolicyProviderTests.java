package io.github.jdubois.bootui.quarkus4.faulttolerance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyDto;
import io.github.jdubois.bootui.core.dto.FaultTolerancePolicySettingDto;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.jdubois.bootui.quarkus4.StubConfig;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test of the Quarkus fault tolerance binding: build-time-captured SmallRye Fault Tolerance annotations
 * are mapped onto the shared contract, MicroProfile configuration overrides win over the captured literal
 * with honest {@code CONFIGURED} provenance, and a breaker SmallRye cannot name reports {@code UNKNOWN}
 * rather than a guessed {@code CLOSED}.
 */
class QuarkusFaultTolerancePolicyProviderTests {

    private static RawFaultTolerancePolicy circuitBreaker(String className, String methodName, String breakerName) {
        return new RawFaultTolerancePolicy(
                breakerName == null ? className + "#" + methodName : breakerName,
                FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER,
                "CircuitBreaker",
                className,
                methodName,
                breakerName,
                List.of(
                        new RawFaultToleranceSetting(
                                "requestVolumeThreshold", "20", FaultToleranceVocabulary.PROVENANCE_DEFAULT),
                        new RawFaultToleranceSetting(
                                "failureRatio", "0.5", FaultToleranceVocabulary.PROVENANCE_DEFAULT)));
    }

    private static RawFaultTolerancePolicy retry(String className, String methodName) {
        return new RawFaultTolerancePolicy(
                className + "#" + methodName,
                FaultToleranceVocabulary.TYPE_RETRY,
                "Retry",
                className,
                methodName,
                null,
                List.of(new RawFaultToleranceSetting("maxRetries", "3", FaultToleranceVocabulary.PROVENANCE_DEFAULT)));
    }

    private static RawFaultTolerancePolicy fallback(String className, String methodName) {
        return new RawFaultTolerancePolicy(
                className + "#" + methodName,
                FaultToleranceVocabulary.TYPE_FALLBACK,
                "Fallback",
                className,
                methodName,
                null,
                List.of(new RawFaultToleranceSetting(
                        "fallbackMethod", "recover", FaultToleranceVocabulary.PROVENANCE_CONFIGURED)));
    }

    private static QuarkusFaultTolerancePolicyProvider provider(
            List<RawFaultTolerancePolicy> policies, QuarkusCircuitBreakerStates states, Map<String, String> config) {
        return new QuarkusFaultTolerancePolicyProvider(
                policies == null
                        ? new UnsatisfiedInstance<>()
                        : new SatisfiedInstance<>(new QuarkusFaultTolerancePolicies(policies)),
                states == null ? new UnsatisfiedInstance<>() : new SatisfiedInstance<>(states),
                new StubConfig(config));
    }

    private static Optional<FaultTolerancePolicySettingDto> setting(FaultTolerancePolicyDto policy, String name) {
        return policy.settings().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst();
    }

    private static QuarkusCircuitBreakerStates states(Map<String, String> byName) {
        return new QuarkusCircuitBreakerStates() {
            @Override
            public String state(String name) {
                return name == null ? null : byName.get(name);
            }

            @Override
            public void onStateChange(String name, Consumer<String> listener) {}
        };
    }

    @Test
    void reportsUnavailableWhenTheFaultToleranceExtensionIsAbsent() {
        QuarkusFaultTolerancePolicyProvider provider = provider(null, null, Map.of());

        assertThat(provider.providerId()).isEqualTo(FaultToleranceVocabulary.PROVIDER_SMALLRYE_FAULT_TOLERANCE);
        assertThat(provider.available()).isFalse();
        assertThat(provider.policies()).isEmpty();
    }

    @Test
    void mapsCapturedAnnotationsOntoTheSharedContract() {
        QuarkusFaultTolerancePolicyProvider provider =
                provider(List.of(retry("com.example.OrderService", "place")), null, Map.of());

        assertThat(provider.available()).isTrue();
        FaultTolerancePolicyDto policy = provider.policies().get(0);
        assertThat(policy.type()).isEqualTo(FaultToleranceVocabulary.TYPE_RETRY);
        assertThat(policy.provider()).isEqualTo(FaultToleranceVocabulary.PROVIDER_SMALLRYE_FAULT_TOLERANCE);
        assertThat(policy.source()).isEqualTo(FaultToleranceVocabulary.SOURCE_ANNOTATION);
        assertThat(policy.target()).isEqualTo("com.example.OrderService#place");
        assertThat(policy.state()).isNull();
        assertThat(setting(policy, "maxRetries")).get().satisfies(retries -> {
            assertThat(retries.value()).isEqualTo("3");
            assertThat(retries.provenance()).isEqualTo(FaultToleranceVocabulary.PROVENANCE_DEFAULT);
        });
    }

    @Test
    void marksAPolicyDisabledThroughMicroProfileConfigurationAsSuch() {
        Map<String, String> config = new HashMap<>();
        config.put("com.example.OrderService/place/Retry/enabled", "false");
        FaultTolerancePolicyDto policy = provider(List.of(retry("com.example.OrderService", "place")), null, config)
                .policies()
                .get(0);

        assertThat(setting(policy, "enabled")).get().satisfies(enabled -> {
            assertThat(enabled.value()).isEqualTo("false");
            assertThat(enabled.provenance()).isEqualTo(FaultToleranceVocabulary.PROVENANCE_CONFIGURED);
        });
    }

    @Test
    void honoursTheGlobalNonFallbackSwitchWithoutTouchingFallback() {
        Map<String, String> config = new HashMap<>();
        config.put("MP_Fault_Tolerance_NonFallback_Enabled", "false");

        FaultTolerancePolicyDto retry = provider(List.of(retry("com.example.OrderService", "place")), null, config)
                .policies()
                .get(0);
        assertThat(setting(retry, "enabled"))
                .get()
                .extracting(FaultTolerancePolicySettingDto::value)
                .isEqualTo("false");

        FaultTolerancePolicyDto fallback = provider(
                        List.of(fallback("com.example.OrderService", "place")), null, config)
                .policies()
                .get(0);
        assertThat(setting(fallback, "enabled")).isEmpty();
    }

    @Test
    void letsAnAnnotationSpecificSwitchOverrideTheGlobalOne() {
        Map<String, String> config = new HashMap<>();
        config.put("MP_Fault_Tolerance_NonFallback_Enabled", "false");
        config.put("com.example.OrderService/place/Retry/enabled", "true");
        FaultTolerancePolicyDto policy = provider(List.of(retry("com.example.OrderService", "place")), null, config)
                .policies()
                .get(0);

        assertThat(setting(policy, "enabled")).isEmpty();
    }

    @Test
    void keepsAPolicyEffectiveWhenTheSwitchIsNotABoolean() {
        Map<String, String> config = new HashMap<>();
        config.put("Retry/enabled", "${maybe}");
        FaultTolerancePolicyDto policy = provider(List.of(retry("com.example.OrderService", "place")), null, config)
                .policies()
                .get(0);

        assertThat(setting(policy, "enabled")).isEmpty();
    }

    @Test
    void reportsNoCountersBecauseSmallRyeExposesNone() {
        FaultTolerancePolicyDto policy = provider(List.of(retry("com.example.OrderService", "place")), null, Map.of())
                .policies()
                .get(0);

        assertThat(policy.metrics().successfulCalls()).isNull();
        assertThat(policy.metrics().failedCalls()).isNull();
        assertThat(policy.metrics().retriedCalls()).isNull();
    }

    @Test
    void prefersTheMostSpecificMicroProfileConfigurationOverride() {
        Map<String, String> config = new HashMap<>();
        config.put("Retry/maxRetries", "9");
        config.put("com.example.OrderService/Retry/maxRetries", "8");
        config.put("com.example.OrderService/place/Retry/maxRetries", "7");

        FaultTolerancePolicyDto policy = provider(List.of(retry("com.example.OrderService", "place")), null, config)
                .policies()
                .get(0);

        assertThat(setting(policy, "maxRetries")).get().satisfies(retries -> {
            assertThat(retries.value()).isEqualTo("7");
            assertThat(retries.provenance()).isEqualTo(FaultToleranceVocabulary.PROVENANCE_CONFIGURED);
        });
    }

    @Test
    void fallsBackToTheClassAndThenTheGlobalOverrideKey() {
        FaultTolerancePolicyDto classScoped = provider(
                        List.of(retry("com.example.OrderService", "place")),
                        null,
                        Map.of("com.example.OrderService/Retry/maxRetries", "8", "Retry/maxRetries", "9"))
                .policies()
                .get(0);
        assertThat(setting(classScoped, "maxRetries"))
                .get()
                .extracting(FaultTolerancePolicySettingDto::value)
                .isEqualTo("8");

        FaultTolerancePolicyDto globalScoped = provider(
                        List.of(retry("com.example.OrderService", "place")), null, Map.of("Retry/maxRetries", "9"))
                .policies()
                .get(0);
        assertThat(setting(globalScoped, "maxRetries")).get().satisfies(retries -> {
            assertThat(retries.value()).isEqualTo("9");
            assertThat(retries.provenance()).isEqualTo(FaultToleranceVocabulary.PROVENANCE_CONFIGURED);
        });
    }

    @Test
    void readsLiveBreakerStateForANamedBreaker() {
        FaultTolerancePolicyDto policy = provider(
                        List.of(circuitBreaker("com.example.PayService", "charge", "payments")),
                        states(Map.of("payments", FaultToleranceVocabulary.STATE_OPEN)),
                        Map.of())
                .policies()
                .get(0);

        assertThat(policy.name()).isEqualTo("payments");
        assertThat(policy.state()).isEqualTo(FaultToleranceVocabulary.STATE_OPEN);
    }

    @Test
    void reportsUnknownStateForAnAnonymousBreakerRatherThanGuessingClosed() {
        FaultTolerancePolicyDto policy = provider(
                        List.of(circuitBreaker("com.example.PayService", "charge", null)), states(Map.of()), Map.of())
                .policies()
                .get(0);

        assertThat(policy.state()).isEqualTo(FaultToleranceVocabulary.STATE_UNKNOWN);
    }

    @Test
    void reportsUnknownStateWhenTheStateSeamItselfIsAbsent() {
        FaultTolerancePolicyDto policy = provider(
                        List.of(circuitBreaker("com.example.PayService", "charge", "payments")), null, Map.of())
                .policies()
                .get(0);

        assertThat(policy.state()).isEqualTo(FaultToleranceVocabulary.STATE_UNKNOWN);
    }

    private static final class UnsatisfiedInstance<T> implements Instance<T> {

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return true;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance.Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Instance.Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T get() {
            throw new UnsatisfiedResolutionException("no bean produced in this test");
        }
    }

    private static final class SatisfiedInstance<T> implements Instance<T> {

        private final T value;

        SatisfiedInstance(T value) {
            this.value = value;
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance.Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Instance.Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T get() {
            return value;
        }
    }
}
