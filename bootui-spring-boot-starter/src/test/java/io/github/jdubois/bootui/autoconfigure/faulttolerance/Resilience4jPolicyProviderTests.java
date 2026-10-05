package io.github.jdubois.bootui.autoconfigure.faulttolerance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyDto;
import io.github.jdubois.bootui.core.dto.FaultTolerancePolicySettingDto;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder.CapturedEvent;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Verifies the Resilience4j binding: every module's registry is read live (so lazily created entries appear),
 * settings carry honest provenance, metrics are normalized rather than invented, and capture registers on
 * Resilience4j's own additive event publishers without decorating a call.
 */
class Resilience4jPolicyProviderTests {

    private final FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 50);

    private static DefaultListableBeanFactory beanFactory(Object... registries) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        int index = 0;
        for (Object registry : registries) {
            beanFactory.registerSingleton("registry-" + index++, registry);
        }
        return beanFactory;
    }

    private static Optional<FaultTolerancePolicySettingDto> setting(FaultTolerancePolicyDto policy, String name) {
        return policy.settings().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst();
    }

    private static FaultTolerancePolicyDto policy(List<FaultTolerancePolicyDto> policies, String name) {
        return policies.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No policy named " + name + " in " + policies));
    }

    @Test
    void staysAvailableWithNoPoliciesWhenNoRegistryBeanExists() {
        Resilience4jPolicyProvider provider = new Resilience4jPolicyProvider(beanFactory(), recorder);

        assertThat(provider.providerId()).isEqualTo(FaultToleranceVocabulary.PROVIDER_RESILIENCE4J);
        // The library is on the classpath, which is exactly what the panel catalog reports availability on:
        // saying "unavailable" here would contradict the sidebar and would make the engine drop captured
        // events for an application that simply has not created a registry bean yet.
        assertThat(provider.available()).isTrue();
        assertThat(provider.policies()).isEmpty();
    }

    @Test
    void readsCircuitBreakersLiveIncludingEntriesCreatedAfterTheFirstRead() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        Resilience4jPolicyProvider provider = new Resilience4jPolicyProvider(beanFactory(registry), recorder);
        assertThat(provider.policies()).isEmpty();

        registry.circuitBreaker("payments");

        List<FaultTolerancePolicyDto> policies = provider.policies();
        assertThat(provider.available()).isTrue();
        assertThat(policies).hasSize(1);
        FaultTolerancePolicyDto breaker = policies.get(0);
        assertThat(breaker.type()).isEqualTo(FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER);
        assertThat(breaker.provider()).isEqualTo(FaultToleranceVocabulary.PROVIDER_RESILIENCE4J);
        assertThat(breaker.source()).isEqualTo(FaultToleranceVocabulary.SOURCE_REGISTRY);
        assertThat(breaker.state()).isEqualTo(FaultToleranceVocabulary.STATE_CLOSED);
    }

    @Test
    void derivesSettingProvenanceByComparingWithResilience4jsOwnDefaults() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker(
                "payments",
                CircuitBreakerConfig.custom().failureRateThreshold(25f).build());
        Resilience4jPolicyProvider provider = new Resilience4jPolicyProvider(beanFactory(registry), recorder);

        FaultTolerancePolicyDto breaker = provider.policies().get(0);

        assertThat(setting(breaker, "failureRateThreshold")).get().satisfies(configured -> {
            assertThat(configured.value()).isEqualTo("25%");
            assertThat(configured.provenance()).isEqualTo(FaultToleranceVocabulary.PROVENANCE_CONFIGURED);
        });
        assertThat(setting(breaker, "slidingWindowSize"))
                .get()
                .extracting(FaultTolerancePolicySettingDto::provenance)
                .isEqualTo(FaultToleranceVocabulary.PROVENANCE_DEFAULT);
        // Resilience4j models the open-state wait as an IntervalFunction, so BootUI reports the first
        // interval and refuses to claim whether it was configured.
        assertThat(setting(breaker, "waitDurationInOpenState"))
                .get()
                .extracting(FaultTolerancePolicySettingDto::provenance)
                .isEqualTo(FaultToleranceVocabulary.PROVENANCE_UNKNOWN);
    }

    @Test
    void reportsAnUnfilledFailureRateAsAbsentRatherThanMinusOne() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker("payments");
        Resilience4jPolicyProvider provider = new Resilience4jPolicyProvider(beanFactory(registry), recorder);

        FaultTolerancePolicyDto breaker = provider.policies().get(0);

        assertThat(breaker.metrics().failureRatePercent()).isNull();
        assertThat(breaker.metrics().successfulCalls()).isZero();
    }

    @Test
    void readsEveryModuleThroughOneProvider() {
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
        breakers.circuitBreaker("breaker");
        RetryRegistry retries = RetryRegistry.ofDefaults();
        retries.retry("retry", RetryConfig.custom().maxAttempts(5).build());
        RateLimiterRegistry rateLimiters = RateLimiterRegistry.ofDefaults();
        rateLimiters.rateLimiter(
                "limiter", RateLimiterConfig.custom().limitForPeriod(7).build());
        BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
        bulkheads.bulkhead(
                "bulkhead", BulkheadConfig.custom().maxConcurrentCalls(3).build());
        ThreadPoolBulkheadRegistry threadPoolBulkheads = ThreadPoolBulkheadRegistry.ofDefaults();
        threadPoolBulkheads.bulkhead("pool");
        TimeLimiterRegistry timeLimiters = TimeLimiterRegistry.ofDefaults();
        timeLimiters.timeLimiter(
                "limit",
                TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(2))
                        .build());

        Resilience4jPolicyProvider provider = new Resilience4jPolicyProvider(
                beanFactory(breakers, retries, rateLimiters, bulkheads, threadPoolBulkheads, timeLimiters), recorder);
        List<FaultTolerancePolicyDto> policies = provider.policies();

        assertThat(policies)
                .extracting(FaultTolerancePolicyDto::type)
                .contains(
                        FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER,
                        FaultToleranceVocabulary.TYPE_RETRY,
                        FaultToleranceVocabulary.TYPE_RATE_LIMITER,
                        FaultToleranceVocabulary.TYPE_BULKHEAD,
                        FaultToleranceVocabulary.TYPE_TIME_LIMITER);
        assertThat(setting(policy(policies, "retry"), "maxAttempts"))
                .get()
                .extracting(FaultTolerancePolicySettingDto::value)
                .isEqualTo("5");
        assertThat(setting(policy(policies, "limiter"), "limitForPeriod"))
                .get()
                .extracting(FaultTolerancePolicySettingDto::value)
                .isEqualTo("7");
        assertThat(setting(policy(policies, "bulkhead"), "maxConcurrentCalls"))
                .get()
                .extracting(FaultTolerancePolicySettingDto::value)
                .isEqualTo("3");
        assertThat(setting(policy(policies, "limit"), "timeoutDuration"))
                .get()
                .extracting(FaultTolerancePolicySettingDto::value)
                .isEqualTo("2000 ms");
    }

    @Test
    void capturesCircuitBreakerStateTransitionsAndShortCircuitsAsMetadataOnly() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker breaker = registry.circuitBreaker("payments");
        Resilience4jPolicyProvider provider = new Resilience4jPolicyProvider(beanFactory(registry), recorder);
        provider.afterSingletonsInstantiated();

        breaker.transitionToOpenState();
        breaker.tryAcquirePermission();

        List<CapturedEvent> events = recorder.recent();
        assertThat(events)
                .extracting(CapturedEvent::outcome)
                .contains(
                        FaultToleranceVocabulary.OUTCOME_STATE_TRANSITION,
                        FaultToleranceVocabulary.OUTCOME_SHORT_CIRCUITED);
        CapturedEvent transition = events.stream()
                .filter(event -> FaultToleranceVocabulary.OUTCOME_STATE_TRANSITION.equals(event.outcome()))
                .findFirst()
                .orElseThrow();
        assertThat(transition.policyName()).isEqualTo("payments");
        assertThat(transition.state()).isEqualTo(FaultToleranceVocabulary.STATE_OPEN);
        assertThat(transition.failureCategory()).isNull();
    }

    @Test
    void capturesFailuresAsExceptionSimpleNamesWithoutTheirMessages() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker breaker = registry.circuitBreaker("payments");
        Resilience4jPolicyProvider provider = new Resilience4jPolicyProvider(beanFactory(registry), recorder);
        provider.afterSingletonsInstantiated();

        breaker.onError(5, java.util.concurrent.TimeUnit.MILLISECONDS, new IOException("connect to db-42 failed"));

        CapturedEvent event = recorder.recent().stream()
                .filter(candidate -> FaultToleranceVocabulary.OUTCOME_ERROR.equals(candidate.outcome()))
                .findFirst()
                .orElseThrow();
        assertThat(event.failureCategory()).isEqualTo("IOException");
        assertThat(recorder.recent())
                .noneMatch(candidate -> String.valueOf(candidate).contains("db-42"));
    }

    @Test
    void capturesBreakersCreatedLazilyAfterRegistration() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        Resilience4jPolicyProvider provider = new Resilience4jPolicyProvider(beanFactory(registry), recorder);
        provider.afterSingletonsInstantiated();

        CircuitBreaker created = registry.circuitBreaker("late");
        created.transitionToOpenState();

        assertThat(recorder.recent()).anySatisfy(event -> {
            assertThat(event.policyName()).isEqualTo("late");
            assertThat(event.outcome()).isEqualTo(FaultToleranceVocabulary.OUTCOME_STATE_TRANSITION);
        });
    }

    @Test
    void capturesTheReplacementWhenARegistryEntryIsReplaced() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker("payments");
        Resilience4jPolicyProvider provider = new Resilience4jPolicyProvider(beanFactory(registry), recorder);
        provider.afterSingletonsInstantiated();

        // replace(name, entry) is part of the registry's public API; the replacement is a different object
        // and carries none of the original's consumers, so capture has to follow it.
        CircuitBreaker replacement = CircuitBreaker.ofDefaults("payments");
        registry.replace("payments", replacement);
        replacement.transitionToOpenState();

        assertThat(recorder.recent()).anySatisfy(event -> {
            assertThat(event.policyName()).isEqualTo("payments");
            assertThat(event.outcome()).isEqualTo(FaultToleranceVocabulary.OUTCOME_STATE_TRANSITION);
        });
    }

    @Test
    void capturesAnEntryRegisteredAgainUnderARemovedName() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker("payments");
        Resilience4jPolicyProvider provider = new Resilience4jPolicyProvider(beanFactory(registry), recorder);
        provider.afterSingletonsInstantiated();

        registry.remove("payments");
        registry.circuitBreaker("payments").transitionToOpenState();

        assertThat(recorder.recent()).anySatisfy(event -> {
            assertThat(event.policyName()).isEqualTo("payments");
            assertThat(event.outcome()).isEqualTo(FaultToleranceVocabulary.OUTCOME_STATE_TRANSITION);
        });
    }

    @Test
    void doesNotSubscribeTwiceWhenRegistrationRunsAgain() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker breaker = registry.circuitBreaker("payments");
        Resilience4jPolicyProvider provider = new Resilience4jPolicyProvider(beanFactory(registry), recorder);
        provider.afterSingletonsInstantiated();
        provider.afterSingletonsInstantiated();

        breaker.transitionToOpenState();

        assertThat(recorder.recent())
                .filteredOn(event -> FaultToleranceVocabulary.OUTCOME_STATE_TRANSITION.equals(event.outcome()))
                .hasSize(1);
    }

    @Test
    void capturesRetryOutcomesFromResilience4jsRetryPublisher() {
        RetryRegistry registry = RetryRegistry.ofDefaults();
        io.github.resilience4j.retry.Retry retry = registry.retry(
                "orders",
                RetryConfig.custom()
                        .maxAttempts(2)
                        .waitDuration(Duration.ofMillis(1))
                        .build());
        Resilience4jPolicyProvider provider = new Resilience4jPolicyProvider(beanFactory(registry), recorder);
        provider.afterSingletonsInstantiated();

        try {
            retry.executeCallable(() -> {
                throw new IOException("boom");
            });
        } catch (Exception expected) {
            // The protected call still fails exactly as it would without BootUI.
        }

        assertThat(recorder.recent()).isNotEmpty();
        assertThat(recorder.recent())
                .allSatisfy(event -> assertThat(event.policyName()).isEqualTo("orders"));
        assertThat(recorder.recent()).extracting(CapturedEvent::failureCategory).contains("IOException");
    }
}
