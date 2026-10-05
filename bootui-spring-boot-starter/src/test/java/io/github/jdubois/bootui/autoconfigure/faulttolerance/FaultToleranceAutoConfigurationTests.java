package io.github.jdubois.bootui.autoconfigure.faulttolerance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.core.dto.FaultToleranceReport;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceService;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * Verifies the fault tolerance backend the auto-configuration assembles, rather than the individual providers:
 * capture must be attached to the application's own Resilience4j publishers by the time the application is
 * running, and it must not be attached at all when the panel or the feature is switched off.
 */
class FaultToleranceAutoConfigurationTests {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
            .withUserConfiguration(SampleRegistryConfiguration.class)
            .withPropertyValues("bootui.enabled=ON");

    @Test
    void capturesEventsProducedBeforeThePanelIsEverOpened() {
        runner.run(context -> {
            // No BootUI request has happened yet: the panel must already be listening.
            context.getBean(CircuitBreakerRegistry.class)
                    .circuitBreaker("orders")
                    .transitionToOpenState();

            FaultToleranceReport report =
                    context.getBean(FaultToleranceService.class).report();

            assertThat(report.faultTolerancePresent()).isTrue();
            assertThat(report.captureEnabled()).isTrue();
            assertThat(report.events()).hasSize(1);
            assertThat(report.events().get(0).outcome()).isEqualTo(FaultToleranceVocabulary.OUTCOME_STATE_TRANSITION);
            assertThat(report.events().get(0).policyName()).isEqualTo("orders");
            assertThat(report.events().get(0).state()).isEqualTo(FaultToleranceVocabulary.STATE_OPEN);
        });
    }

    @Test
    void listsTheApplicationRegistryPoliciesWithLiveState() {
        runner.run(context -> {
            FaultToleranceReport report =
                    context.getBean(FaultToleranceService.class).report();

            assertThat(report.providers()).contains(FaultToleranceVocabulary.PROVIDER_RESILIENCE4J);
            assertThat(report.policies())
                    .anySatisfy(policy -> assertThat(policy.name()).isEqualTo("orders"));
            assertThat(report.policyCountsByType()).containsEntry(FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER, 1);
        });
    }

    @Test
    void attachesNoConsumerWhenCaptureIsDisabled() {
        runner.withPropertyValues("bootui.fault-tolerance.enabled=false").run(context -> {
            assertThat(context.getBean(FaultToleranceEventRecorder.class).isEnabled())
                    .isFalse();

            context.getBean(CircuitBreakerRegistry.class)
                    .circuitBreaker("orders")
                    .transitionToOpenState();

            FaultToleranceReport report =
                    context.getBean(FaultToleranceService.class).report();
            assertThat(report.captureEnabled()).isFalse();
            assertThat(report.events()).isEmpty();
            // The inventory is still read live; only capture is off.
            assertThat(report.policies()).isNotEmpty();
        });
    }

    @Test
    void disablingThePanelAlsoDisablesCapture() {
        runner.withPropertyValues("bootui.panels.fault-tolerance.enabled=false")
                .run(context -> assertThat(context.getBean(FaultToleranceEventRecorder.class)
                                .isEnabled())
                        .isFalse());
    }

    @Test
    void keepsTheRetryListenerInertWhenCaptureIsDisabled() {
        runner.withPropertyValues("bootui.fault-tolerance.enabled=false").run(context -> {
            RetryTemplate template = new RetryTemplate();
            template.setRetryPolicy(new SimpleRetryPolicy(2));
            template.setBackOffPolicy(new NoBackOffPolicy());
            template.registerListener(context.getBean(BootUiRetryListener.class));

            assertThatThrownBy(() -> template.execute(retryContext -> {
                        retryContext.setAttribute(RetryContext.NAME, "payments");
                        throw new IOException("boom");
                    }))
                    .isInstanceOf(IOException.class);

            assertThat(context.getBean(FaultToleranceEventRecorder.class).recent())
                    .isEmpty();
        });
    }

    @Test
    void reportsNoFaultToleranceLibraryWhenNeitherIsOnTheClasspath() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=ON")
                .withClassLoader(new FilteredClassLoader("io.github.resilience4j", "org.springframework.retry"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(Resilience4jPolicyProvider.class);
                    assertThat(context).doesNotHaveBean(SpringRetryPolicyProvider.class);
                    // No library means no source: the recorder stays off so Live Activity never advertises
                    // a Fault Tolerance source an application without any fault tolerance library cannot have.
                    assertThat(context.getBean(FaultToleranceEventRecorder.class)
                                    .isEnabled())
                            .isFalse();

                    FaultToleranceReport report =
                            context.getBean(FaultToleranceService.class).report();
                    assertThat(report.faultTolerancePresent()).isFalse();
                    assertThat(report.policies()).isEmpty();
                    assertThat(report.events()).isEmpty();
                });
    }

    @Test
    void reportsSpringRetryAsPresentEvenBeforeAnyMethodIsAnnotated() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=ON")
                .withClassLoader(new FilteredClassLoader("io.github.resilience4j"))
                .run(context -> {
                    FaultToleranceReport report =
                            context.getBean(FaultToleranceService.class).report();

                    assertThat(report.faultTolerancePresent()).isTrue();
                    assertThat(report.providers()).containsExactly(FaultToleranceVocabulary.PROVIDER_SPRING_RETRY);
                    assertThat(report.policies()).isEmpty();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class SampleRegistryConfiguration {

        @Bean
        CircuitBreakerRegistry circuitBreakerRegistry() {
            CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                    .failureRateThreshold(50f)
                    .waitDurationInOpenState(Duration.ofSeconds(5))
                    .build());
            registry.circuitBreaker("orders");
            return registry;
        }
    }
}
