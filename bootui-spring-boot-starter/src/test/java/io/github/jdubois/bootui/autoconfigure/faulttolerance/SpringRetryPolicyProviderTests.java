package io.github.jdubois.bootui.autoconfigure.faulttolerance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyDto;
import io.github.jdubois.bootui.core.dto.FaultTolerancePolicySettingDto;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

/**
 * Verifies the Spring Retry binding: {@code @Retryable} declarations are discovered from bean types without
 * instantiating them, expressions are reported literally rather than evaluated, and no runtime state or
 * counter is invented for a library that keeps none.
 */
class SpringRetryPolicyProviderTests {

    static class OrderService {

        @Retryable(maxAttempts = 5, backoff = @Backoff(delay = 250))
        public void placeOrder() {}

        public void notRetryable() {}
    }

    static class LabelledService {

        @Retryable(label = "payments", recover = "fallback")
        public void charge() {}
    }

    static class ExpressionService {

        @Retryable(maxAttemptsExpression = "#{@retryProperties.attempts}")
        public void charge() {}
    }

    @Retryable
    static class TypeLevelService {

        public void first() {}

        public void second() {}
    }

    static class ExplodingFactoryBean implements org.springframework.beans.factory.FactoryBean<Object> {

        static boolean instantiated;

        @Override
        public Object getObject() {
            instantiated = true;
            return new Object();
        }

        @Override
        public Class<?> getObjectType() {
            return Object.class;
        }
    }

    private static SpringRetryPolicyProvider provider(Class<?>... beanTypes) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        int index = 0;
        for (Class<?> beanType : beanTypes) {
            beanFactory.registerBeanDefinition("bean-" + index++, new RootBeanDefinition(beanType));
        }
        return new SpringRetryPolicyProvider(beanFactory);
    }

    private static Optional<FaultTolerancePolicySettingDto> setting(FaultTolerancePolicyDto policy, String name) {
        return policy.settings().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst();
    }

    @Test
    void staysAvailableWithNoPoliciesWhenNoBeanDeclaresRetryable() {
        SpringRetryPolicyProvider provider = provider(OrderService.class.getSuperclass());

        assertThat(provider.providerId()).isEqualTo(FaultToleranceVocabulary.PROVIDER_SPRING_RETRY);
        // Spring Retry is present, so the provider reports itself and the engine keeps the retry events its
        // listener captures, even before the application annotates its first method.
        assertThat(provider.available()).isTrue();
        assertThat(provider.policies()).isEmpty();
    }

    @Test
    void discoversAnnotatedMethodsWithTheirAttemptBudgetAndBackoff() {
        SpringRetryPolicyProvider provider = provider(OrderService.class);

        List<FaultTolerancePolicyDto> policies = provider.policies();

        assertThat(provider.available()).isTrue();
        assertThat(policies).hasSize(1);
        FaultTolerancePolicyDto policy = policies.get(0);
        assertThat(policy.name()).isEqualTo("OrderService#placeOrder");
        assertThat(policy.type()).isEqualTo(FaultToleranceVocabulary.TYPE_RETRY);
        assertThat(policy.provider()).isEqualTo(FaultToleranceVocabulary.PROVIDER_SPRING_RETRY);
        assertThat(policy.source()).isEqualTo(FaultToleranceVocabulary.SOURCE_ANNOTATION);
        assertThat(policy.target()).isEqualTo("OrderService#placeOrder");
        assertThat(setting(policy, "maxAttempts")).get().satisfies(attempts -> {
            assertThat(attempts.value()).isEqualTo("5");
            assertThat(attempts.provenance()).isEqualTo(FaultToleranceVocabulary.PROVENANCE_CONFIGURED);
        });
        assertThat(setting(policy, "backoffDelay"))
                .get()
                .extracting(FaultTolerancePolicySettingDto::value)
                .isEqualTo("250 ms");
    }

    @Test
    void reportsNoRuntimeStateOrCountersForALibraryThatKeepsNone() {
        FaultTolerancePolicyDto policy = provider(OrderService.class).policies().get(0);

        assertThat(policy.state()).isNull();
        assertThat(policy.metrics().successfulCalls()).isNull();
        assertThat(policy.metrics().failedCalls()).isNull();
        assertThat(policy.metrics().retriedCalls()).isNull();
    }

    @Test
    void prefersAnExplicitLabelOverTheDerivedTargetName() {
        FaultTolerancePolicyDto policy =
                provider(LabelledService.class).policies().get(0);

        assertThat(policy.name()).isEqualTo("payments");
        assertThat(policy.target()).isEqualTo("LabelledService#charge");
        assertThat(setting(policy, "recover"))
                .get()
                .extracting(FaultTolerancePolicySettingDto::value)
                .isEqualTo("fallback");
    }

    @Test
    void reportsAnUnevaluatedExpressionRatherThanRunningApplicationCodeOnARead() {
        FaultTolerancePolicyDto policy =
                provider(ExpressionService.class).policies().get(0);

        assertThat(setting(policy, "maxAttempts"))
                .get()
                .extracting(FaultTolerancePolicySettingDto::value)
                .isEqualTo("#{@retryProperties.attempts}");
    }

    @Test
    void appliesATypeLevelAnnotationToEveryDeclaredMethod() {
        List<FaultTolerancePolicyDto> policies =
                provider(TypeLevelService.class).policies();

        assertThat(policies)
                .extracting(FaultTolerancePolicyDto::name)
                .contains("TypeLevelService#first", "TypeLevelService#second");
    }

    @Test
    void neverInstantiatesFactoryBeansWhileScanning() {
        ExplodingFactoryBean.instantiated = false;

        provider(ExplodingFactoryBean.class).policies();

        assertThat(ExplodingFactoryBean.instantiated).isFalse();
    }

    @Test
    void memoizesTheScanBecauseDeclarationsAreFixedOnceTheContextHasStarted() {
        SpringRetryPolicyProvider provider = provider(OrderService.class);

        assertThat(provider.policies()).isSameAs(provider.policies());
    }
}
