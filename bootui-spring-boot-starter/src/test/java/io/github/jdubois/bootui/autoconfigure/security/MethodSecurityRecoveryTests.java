package io.github.jdubois.bootui.autoconfigure.security;

import static io.github.jdubois.bootui.autoconfigure.security.SecurityScannerTests.assertNoMethodTypeResolutionCallbacks;
import static io.github.jdubois.bootui.autoconfigure.security.SecurityScannerTests.clearMethodInvocations;
import static io.github.jdubois.bootui.autoconfigure.security.SecurityScannerTests.methodApplicationContext;
import static io.github.jdubois.bootui.autoconfigure.security.SecurityScannerTests.methodInventoryEvidence;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Pointcut;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.aop.support.DefaultBeanFactoryPointcutAdvisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.aop.target.HotSwappableTargetSource;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;
import org.springframework.core.NativeDetector;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.SingleResultAuthorizationManager;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.authorization.method.SecuredAuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.test.util.AopTestUtils;

class MethodSecurityRecoveryTests {
    @Test
    void cachedClassProxyTargetsNeedStaticProvenanceIncludingInsideJdkProxies() throws Exception {
        for (boolean jdkOuter : List.of(false, true)) {
            for (boolean dynamic : List.of(false, true)) {
                try (var application = methodApplicationContext(PrePostConfiguration.class)) {
                    AtomicInteger callbacks = new AtomicInteger();
                    Object product = classProxy(dynamic, callbacks);
                    if (jdkOuter) {
                        var outer = new ProxyFactory();
                        outer.setInterfaces(GuardedContract.class);
                        outer.setTarget(product);
                        product = outer.getProxy();
                    }
                    var factory = application.getDefaultListableBeanFactory();
                    cacheProduct(factory, product, callbacks);
                    var scanner = scanner(factory);
                    callbacks.set(0);
                    clearMethodInvocations(factory);

                    var evidence = methodInventoryEvidence(factory);
                    var report = scanner.scan();
                    scanner.lastReport();

                    assertThat(evidence.methodFamiliesKnown())
                            .as("dynamic=%s, JDK outer=%s", dynamic, jdkOuter)
                            .isEqualTo(!dynamic);
                    assertThat(evidence.usedMethodFamilies().contains("secured"))
                            .isEqualTo(!dynamic);
                    assertThat(report.evidence().limitations().stream()
                                    .anyMatch(value -> value.startsWith("SEC-METHOD-001:")))
                            .isEqualTo(dynamic);
                    assertThat(report.results().stream()
                                    .anyMatch(result -> result.id().equals("SEC-METHOD-001")))
                            .isEqualTo(!dynamic);
                    assertThat(callbacks).hasValue(0);
                    assertNoMethodTypeResolutionCallbacks(factory);
                }
            }
        }
    }

    @Test
    void nativeClassProxyTargetsStayUnknownWithoutGeneratedCallbackFieldMetadata() throws Exception {
        try (var application = methodApplicationContext(PrePostConfiguration.class)) {
            AtomicInteger callbacks = new AtomicInteger();
            var factory = application.getDefaultListableBeanFactory();
            cacheProduct(factory, classProxy(false, callbacks), callbacks);
            callbacks.set(0);
            clearMethodInvocations(factory);
            try (var nativeImage = mockStatic(NativeDetector.class)) {
                nativeImage.when(NativeDetector::inNativeImage).thenReturn(true);
                assertThat(methodInventoryEvidence(factory).methodFamiliesKnown())
                        .isFalse();
            }
            assertThat(callbacks).hasValue(0);
            assertNoMethodTypeResolutionCallbacks(factory);
        }
    }

    @Test
    void nativeInfrastructureCreatorExcludesApplicationRoleAdvisorProducts() throws Exception {
        for (Class<?> configuration :
                List.of(InfrastructureConfiguration.class, WrappedInfrastructureConfiguration.class)) {
            for (boolean infrastructure : List.of(false, true)) {
                try (var application = methodApplicationContext()) {
                    application.register(configuration);
                    application.addBeanFactoryPostProcessor(factory -> factory.getBeanDefinition("candidateAdvisor")
                            .setRole(
                                    infrastructure
                                            ? BeanDefinition.ROLE_INFRASTRUCTURE
                                            : BeanDefinition.ROLE_APPLICATION));
                    application.refresh();
                    verifyAdvisorActivation(application, infrastructure);
                }
            }
        }
    }

    @Test
    void nativeDefaultCreatorAcceptsApplicationAdvisorsAndRespectsItsPrefix() throws Exception {
        for (Class<?> configuration :
                List.of(DefaultCreatorConfiguration.class, WrappedDefaultCreatorConfiguration.class)) {
            for (String prefix : List.of("", "candidate", "unmatched.")) {
                try (var application = methodApplicationContext()) {
                    application.register(configuration);
                    application.addBeanFactoryPostProcessor(factory -> {
                        if (!prefix.isEmpty())
                            factory.getBeanDefinition("defaultCreator")
                                    .getPropertyValues()
                                    .add("usePrefix", true)
                                    .add("advisorBeanNamePrefix", prefix);
                    });
                    application.refresh();
                    verifyAdvisorActivation(application, !prefix.equals("unmatched."));
                }
            }
        }
    }

    @Test
    void customCreatorEligibilityRemainsUnknownWithoutInvokingItsCallbacks() throws Exception {
        try (var application = methodApplicationContext(CustomCreatorConfiguration.class)) {
            application.getBean("candidateAdvisor");
            var producer = (AdvisorFactory) application.getBean("&candidateAdvisor");
            var factory = application.getDefaultListableBeanFactory();
            var scanner = scanner(factory);
            producer.callbacks.set(0);
            CustomCreator.callbacks.set(0);
            clearMethodInvocations(factory);

            var evidence = methodInventoryEvidence(factory);
            var report = scanner.scan();
            scanner.lastReport();

            assertThat(evidence.methodFamiliesKnown()).isFalse();
            assertThat(report.results()).noneMatch(result -> result.id().equals("SEC-METHOD-001"));
            assertThat(report.evidence().limitations())
                    .contains("SEC-METHOD-001: Method-security family metadata is incomplete.");
            assertThat(producer.callbacks).hasValue(0);
            assertThat(CustomCreator.callbacks).hasValue(0);
            assertNoMethodTypeResolutionCallbacks(factory);
        }
    }

    @Test
    void borrowedAndUnregisteredCreatorsDoNotQualifyAnotherFactorysAdvisors() throws Exception {
        try (var donor = methodApplicationContext(PrePostConfiguration.class)) {
            for (boolean borrowed : List.of(false, true)) {
                var factory = new SecurityScannerTests.RecordingMethodBeanFactory();
                if (borrowed) {
                    var creator = donor.getDefaultListableBeanFactory().getBeanPostProcessors().stream()
                            .filter(AbstractAutoProxyCreator.class::isInstance)
                            .map(AbstractAutoProxyCreator.class::cast)
                            .findFirst()
                            .orElseThrow();
                    factory.addBeanPostProcessor(creator);
                } else {
                    var creator = new DefaultAdvisorAutoProxyCreator();
                    creator.setBeanFactory(factory);
                    factory.registerSingleton("notInstalledCreator", creator);
                }
                var definition = new RootBeanDefinition(AdvisorFactory.class);
                definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
                var producer = new AdvisorFactory();
                factory.registerBeanDefinition("candidateAdvisor", definition);
                factory.registerSingleton("candidateAdvisor", producer);
                factory.getBean("candidateAdvisor");
                factory.registerBeanDefinition("service", new RootBeanDefinition(SecuredBean.class));
                factory.registerSingleton("service", new SecuredBean());
                var scanner = scanner(factory);
                producer.callbacks.set(0);
                clearMethodInvocations(factory);

                var evidence = methodInventoryEvidence(factory);
                var report = scanner.scan();

                assertThat(evidence.methodFamiliesKnown())
                        .as("borrowed=%s", borrowed)
                        .isFalse();
                assertThat(report.results()).noneMatch(result -> result.id().equals("SEC-METHOD-001"));
                assertThat(producer.callbacks).hasValue(0);
                assertNoMethodTypeResolutionCallbacks(factory);
            }
        }
    }

    @Test
    void proxyLocalMethodAdviceDoesNotProveGlobalActivationOrMissingActivation() throws Exception {
        for (int wrapped = 0; wrapped < 3; wrapped++) {
            try (var application = methodApplicationContext(PrePostConfiguration.class)) {
                var target = new SecuredBean();
                var builder = new ProxyFactory(target);
                builder.setProxyTargetClass(true);
                if (wrapped == 1) builder.addAdvice(AuthorizationManagerBeforeMethodInterceptor.secured());
                else if (wrapped == 2) {
                    var advisor = new DefaultBeanFactoryPointcutAdvisor();
                    advisor.setAdvice(AuthorizationManagerBeforeMethodInterceptor.secured());
                    builder.addAdvisor(advisor);
                } else builder.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.secured());
                var product = (SecuredBean) builder.getProxy();
                SecurityContextHolder.getContext()
                        .setAuthentication(new TestingAuthenticationToken("reader", "unused", "ROLE_READER"));
                try {
                    org.assertj.core.api.Assertions.assertThatThrownBy(product::guarded)
                            .isInstanceOf(AccessDeniedException.class);
                    var factory = application.getDefaultListableBeanFactory();
                    var callbacks = new AtomicInteger();
                    cacheProduct(factory, product, callbacks);
                    var scanner = scanner(factory);
                    callbacks.set(0);
                    clearMethodInvocations(factory);

                    var evidence = methodInventoryEvidence(factory);
                    var report = scanner.scan();

                    assertThat(evidence.methodFamiliesKnown()).isFalse();
                    assertThat(evidence.usedMethodFamilies()).contains("secured");
                    assertThat(report.results()).noneMatch(result -> result.id().equals("SEC-METHOD-001"));
                    assertThat(report.evidence().limitations())
                            .contains("SEC-METHOD-001: Method-security family metadata is incomplete.");
                    assertThat(target.calls).hasValue(0);
                    assertThat(callbacks).hasValue(0);
                    assertNoMethodTypeResolutionCallbacks(factory);
                    SecurityContextHolder.getContext()
                            .setAuthentication(new TestingAuthenticationToken("admin", "unused", "ROLE_ADMIN"));
                    product.guarded();
                    assertThat(target.calls).hasValue(1);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
        }
    }

    @Test
    void cachedNativeAdviceWithUnprovenPointcutsDoesNotProveActivation() throws Exception {
        for (Class<?> configuration :
                List.of(NarrowCreatorConfiguration.class, NarrowInnerCreatorConfiguration.class)) {
            try (var application = methodApplicationContext(configuration)) {
                application.getBean("candidateAdvisor");
                var service = application.getBean(SecuredBean.class);
                var target = AopTestUtils.<SecuredBean>getUltimateTargetObject(service);
                SecurityContextHolder.getContext()
                        .setAuthentication(new TestingAuthenticationToken("reader", "unused", "ROLE_READER"));
                try {
                    service.guarded();
                    assertThat(target.calls).hasValue(1);
                    var factory = application.getDefaultListableBeanFactory();
                    var scanner = scanner(factory);
                    target.calls.set(0);
                    clearMethodInvocations(factory);

                    var evidence = methodInventoryEvidence(factory);
                    var report = scanner.scan();

                    assertThat(evidence.methodFamiliesKnown()).isFalse();
                    assertThat(evidence.enabledMethodFamilies()).doesNotContain("secured");
                    assertThat(report.results()).noneMatch(result -> result.id().equals("SEC-METHOD-001"));
                    assertThat(target.calls).hasValue(0);
                    assertNoMethodTypeResolutionCallbacks(factory);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
        }
    }

    @Test
    void lateAdvisorBeansOutsideTheCreatorsNameSnapshotDoNotProveActivation() throws Exception {
        try (var application = methodApplicationContext(PrePostConfiguration.class)) {
            var factory = application.getDefaultListableBeanFactory();
            var definition = new RootBeanDefinition(AdvisorFactory.class);
            definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            var producer = new AdvisorFactory();
            factory.registerBeanDefinition("lateAdvisor", definition);
            factory.registerSingleton("lateAdvisor", producer);
            factory.getBean("lateAdvisor");
            factory.registerBeanDefinition("lateService", new RootBeanDefinition(SecuredBean.class));
            var service = application.getBean(SecuredBean.class);
            SecurityContextHolder.getContext()
                    .setAuthentication(new TestingAuthenticationToken("reader", "unused", "ROLE_READER"));
            try {
                service.guarded();
                var scanner = scanner(factory);
                producer.callbacks.set(0);
                clearMethodInvocations(factory);

                var evidence = methodInventoryEvidence(factory);
                var report = scanner.scan();

                assertThat(evidence.methodFamiliesKnown()).isTrue();
                assertThat(evidence.enabledMethodFamilies()).doesNotContain("secured");
                assertThat(report.results()).anyMatch(result -> result.id().equals("SEC-METHOD-001"));
                assertThat(producer.callbacks).hasValue(0);
                assertNoMethodTypeResolutionCallbacks(factory);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    @Test
    void unsupportedCreatorsRemainUnknownBeforeTheySynthesizeAnyAdvisor() throws Exception {
        try (var application = methodApplicationContext()) {
            application.register(SynthesizingConfiguration.class);
            var definition = new RootBeanDefinition(LazySecuredService.class);
            definition.setLazyInit(true);
            application.registerBeanDefinition("lazyService", definition);
            application.refresh();
            var factory = application.getDefaultListableBeanFactory();
            var scanner = scanner(factory);
            SynthesizingCreator.callbacks.set(0);
            clearMethodInvocations(factory);

            var evidence = methodInventoryEvidence(factory);
            var report = scanner.scan();

            assertThat(evidence.methodFamiliesKnown()).isFalse();
            assertThat(factory.containsSingleton("lazyService")).isFalse();
            assertThat(report.results()).noneMatch(result -> result.id().equals("SEC-METHOD-001"));
            assertThat(SynthesizingCreator.callbacks).hasValue(0);
            assertNoMethodTypeResolutionCallbacks(factory);
            var service = application.getBean(GuardedContract.class);
            SecurityContextHolder.getContext()
                    .setAuthentication(new TestingAuthenticationToken("reader", "unused", "ROLE_READER"));
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(service::guarded)
                        .isInstanceOf(AccessDeniedException.class);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    @Test
    void frozenClassProxyDispatchCannotBorrowRetargetedCallbackZero() throws Exception {
        try (var application = methodApplicationContext(PrePostConfiguration.class)) {
            var source = new HotSwappableTargetSource(new DispatchBase());
            var builder = new ProxyFactory();
            builder.setTargetSource(source);
            builder.setProxyTargetClass(true);
            builder.setFrozen(true);
            var product = (DispatchBase) builder.getProxy();
            source.swap(new DispatchSecured());
            builder.setFrozen(false);
            builder.setTarget(new DispatchBase());
            assertThat(product.value()).isEqualTo("secured");
            var factory = application.getDefaultListableBeanFactory();
            var callbacks = new AtomicInteger();
            cacheProduct(factory, product, callbacks);
            var scanner = scanner(factory);
            callbacks.set(0);
            clearMethodInvocations(factory);

            var evidence = methodInventoryEvidence(factory);
            var report = scanner.scan();

            assertThat(evidence.methodFamiliesKnown()).isFalse();
            assertThat(report.evidence().limitations())
                    .contains("SEC-METHOD-001: Method-security family metadata is incomplete.");
            assertThat(callbacks).hasValue(0);
            assertNoMethodTypeResolutionCallbacks(factory);
        }
    }

    @Test
    void eligiblePrototypeAdvisorsAreUnknownWithoutInstantiatingThem() throws Exception {
        try (var application = methodApplicationContext()) {
            application.register(PrePostConfiguration.class);
            var advisor = new RootBeanDefinition(AuthorizationManagerBeforeMethodInterceptor.class);
            advisor.setScope(BeanDefinition.SCOPE_PROTOTYPE);
            advisor.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            advisor.getConstructorArgumentValues()
                    .addIndexedArgumentValue(
                            0,
                            AuthorizationManagerBeforeMethodInterceptor.secured()
                                    .getPointcut());
            advisor.getConstructorArgumentValues().addIndexedArgumentValue(1, new SecuredAuthorizationManager());
            application.registerBeanDefinition("prototypeAdvisor", advisor);
            var service = new RootBeanDefinition(SecuredBean.class);
            service.setLazyInit(true);
            application.registerBeanDefinition("lazyPrototypeService", service);
            application.refresh();
            var factory = application.getDefaultListableBeanFactory();
            var scanner = scanner(factory);
            clearMethodInvocations(factory);

            var evidence = methodInventoryEvidence(factory);
            var report = scanner.scan();

            assertThat(evidence.methodFamiliesKnown()).isFalse();
            assertThat(factory.containsSingleton("prototypeAdvisor")).isFalse();
            assertThat(factory.containsSingleton("lazyPrototypeService")).isFalse();
            assertThat(report.results()).noneMatch(result -> result.id().equals("SEC-METHOD-001"));
            assertNoMethodTypeResolutionCallbacks(factory);
            var guarded = application.getBean(SecuredBean.class);
            SecurityContextHolder.getContext()
                    .setAuthentication(new TestingAuthenticationToken("reader", "unused", "ROLE_READER"));
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(guarded::guarded)
                        .isInstanceOf(AccessDeniedException.class);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    @Test
    void mutableAdviceWrappersCannotContradictAlreadyCachedInvocationChains() throws Exception {
        for (boolean beanFactoryWrapper : List.of(false, true)) {
            try (var application = methodApplicationContext(PrePostConfiguration.class)) {
                var target = new SecuredBean();
                var builder = new ProxyFactory(target);
                builder.setProxyTargetClass(true);
                org.springframework.aop.Advisor wrapper;
                if (beanFactoryWrapper) {
                    var value = new DefaultBeanFactoryPointcutAdvisor();
                    value.setAdvice(AuthorizationManagerBeforeMethodInterceptor.secured());
                    wrapper = value;
                } else wrapper = new DefaultPointcutAdvisor(AuthorizationManagerBeforeMethodInterceptor.secured());
                builder.addAdvisor(wrapper);
                var product = (SecuredBean) builder.getProxy();
                SecurityContextHolder.getContext()
                        .setAuthentication(new TestingAuthenticationToken("reader", "unused", "ROLE_READER"));
                try {
                    org.assertj.core.api.Assertions.assertThatThrownBy(product::guarded)
                            .isInstanceOf(AccessDeniedException.class);
                    org.aopalliance.intercept.MethodInterceptor passThrough = invocation -> invocation.proceed();
                    if (wrapper instanceof DefaultBeanFactoryPointcutAdvisor value) value.setAdvice(passThrough);
                    else ((DefaultPointcutAdvisor) wrapper).setAdvice(passThrough);
                    org.assertj.core.api.Assertions.assertThatThrownBy(product::guarded)
                            .isInstanceOf(AccessDeniedException.class);
                    var factory = application.getDefaultListableBeanFactory();
                    var callbacks = new AtomicInteger();
                    cacheProduct(factory, product, callbacks);
                    var scanner = scanner(factory);
                    callbacks.set(0);
                    clearMethodInvocations(factory);

                    var evidence = methodInventoryEvidence(factory);
                    var report = scanner.scan();

                    assertThat(evidence.methodFamiliesKnown()).isFalse();
                    assertThat(report.results()).noneMatch(result -> result.id().equals("SEC-METHOD-001"));
                    assertThat(target.calls).hasValue(0);
                    assertThat(callbacks).hasValue(0);
                    assertNoMethodTypeResolutionCallbacks(factory);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
        }
    }

    private static void verifyAdvisorActivation(
            org.springframework.context.annotation.AnnotationConfigApplicationContext application, boolean enabled)
            throws Exception {
        Object candidate = application.getBean("candidateAdvisor");
        var service = application.getBean(SecuredBean.class);
        var target = AopTestUtils.<SecuredBean>getUltimateTargetObject(service);
        var producer = (AdvisorFactory) application.getBean("&candidateAdvisor");
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("reader", "unused", "ROLE_READER"));
        try {
            boolean denied = false;
            try {
                service.guarded();
            } catch (AccessDeniedException expected) {
                denied = true;
            }
            assertThat(denied).isEqualTo(enabled);
            target.calls.set(0);
            var factory = application.getDefaultListableBeanFactory();
            var scanner = scanner(factory);
            producer.callbacks.set(0);
            clearMethodInvocations(factory);

            var evidence = methodInventoryEvidence(factory);
            var report = scanner.scan();
            scanner.lastReport();

            boolean complete = !enabled || !(candidate instanceof DefaultPointcutAdvisor);
            assertThat(evidence.methodFamiliesKnown()).isEqualTo(complete);
            assertThat(evidence.enabledMethodFamilies().contains("secured")).isEqualTo(enabled);
            assertThat(report.results().stream().anyMatch(result -> result.id().equals("SEC-METHOD-001")))
                    .isEqualTo(!enabled);
            assertThat(report.evidence().limitations().stream().anyMatch(value -> value.startsWith("SEC-METHOD-001:")))
                    .isEqualTo(!complete);
            assertThat(producer.callbacks).hasValue(0);
            assertThat(target.calls).hasValue(0);
            assertNoMethodTypeResolutionCallbacks(factory);
            SecurityContextHolder.getContext()
                    .setAuthentication(new TestingAuthenticationToken("admin", "unused", "ROLE_ADMIN"));
            service.guarded();
            assertThat(target.calls).hasValue(1);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void subclassedAdviceWrappersRemainUnknownWithoutCallingTheirGetter() throws Exception {
        try (var application = methodApplicationContext(PrePostConfiguration.class)) {
            AtomicInteger getters = new AtomicInteger();
            var wrapper = new DefaultPointcutAdvisor(AuthorizationManagerBeforeMethodInterceptor.secured()) {
                @Override
                public org.aopalliance.aop.Advice getAdvice() {
                    getters.incrementAndGet();
                    return super.getAdvice();
                }
            };
            var builder = new ProxyFactory(new SecuredBean());
            builder.setProxyTargetClass(true);
            builder.addAdvisor(wrapper);
            var product = builder.getProxy();
            var factory = application.getDefaultListableBeanFactory();
            var callbacks = new AtomicInteger();
            cacheProduct(factory, product, callbacks);
            var scanner = scanner(factory);
            getters.set(0);
            callbacks.set(0);
            clearMethodInvocations(factory);

            var evidence = methodInventoryEvidence(factory);
            var report = scanner.scan();

            assertThat(evidence.methodFamiliesKnown()).isFalse();
            assertThat(report.results()).noneMatch(result -> result.id().equals("SEC-METHOD-001"));
            assertThat(getters).hasValue(0);
            assertThat(callbacks).hasValue(0);
            assertNoMethodTypeResolutionCallbacks(factory);
        }
    }

    private static Object classProxy(boolean dynamic, AtomicInteger callbacks) {
        var builder = new ProxyFactory();
        builder.setProxyTargetClass(true);
        if (dynamic) {
            builder.setTargetSource(new TargetSource() {
                @Override
                public Class<?> getTargetClass() {
                    callbacks.incrementAndGet();
                    return GuardedBase.class;
                }

                @Override
                public boolean isStatic() {
                    callbacks.incrementAndGet();
                    return false;
                }

                @Override
                public Object getTarget() {
                    callbacks.incrementAndGet();
                    return new SecuredTarget();
                }

                @Override
                public void releaseTarget(Object target) {
                    callbacks.incrementAndGet();
                }
            });
        } else builder.setTarget(new SecuredTarget());
        return builder.getProxy();
    }

    private static void cacheProduct(DefaultListableBeanFactory factory, Object product, AtomicInteger callbacks) {
        factory.registerBeanDefinition("cachedProduct", new RootBeanDefinition(ProductFactory.class));
        factory.registerSingleton("cachedProduct", new ProductFactory(product, callbacks));
        factory.getBean("cachedProduct");
    }

    private static SecurityScanner scanner(DefaultListableBeanFactory factory) {
        var chains = new FilterChainProxy(List.of(
                new DefaultSecurityFilterChain(
                        PathPatternRequestMatcher.pathPattern("/missing/**"),
                        new UsernamePasswordAuthenticationFilter()),
                new DefaultSecurityFilterChain(
                        AnyRequestMatcher.INSTANCE,
                        new UsernamePasswordAuthenticationFilter(),
                        new AuthorizationFilter(SingleResultAuthorizationManager.permitAll()))));
        factory.registerSingleton("springSecurityFilterChain", chains);
        var providers = new DefaultListableBeanFactory();
        providers.registerSingleton("observedFactory", factory);
        return new SecurityScanner(
                factory.getBeanProvider(FilterChainProxy.class),
                providers.getBeanProvider(ListableBeanFactory.class),
                applicationEnvironment(),
                Clock.systemUTC());
    }

    private static org.springframework.core.env.Environment applicationEnvironment() {
        return new org.springframework.mock.env.MockEnvironment();
    }

    interface GuardedContract {
        void guarded();
    }

    public static class GuardedBase implements GuardedContract {
        @Override
        public void guarded() {
            throw new AssertionError("Must not invoke application methods");
        }
    }

    public static class SecuredTarget extends GuardedBase {
        @Override
        @Secured("ROLE_ADMIN")
        public void guarded() {
            throw new AssertionError("Must not invoke an inspected target");
        }
    }

    static final class ProductFactory implements FactoryBean<Object> {
        private final Object product;
        private final AtomicInteger callbacks;

        ProductFactory(Object product, AtomicInteger callbacks) {
            this.product = product;
            this.callbacks = callbacks;
        }

        @Override
        public Object getObject() {
            callbacks.incrementAndGet();
            return product;
        }

        @Override
        public Class<?> getObjectType() {
            callbacks.incrementAndGet();
            return product.getClass();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class PrePostConfiguration {
        @Bean
        PrePostBean prePostBean() {
            return new PrePostBean();
        }
    }

    static class PrePostBean {
        @PreAuthorize("hasRole('ADMIN')")
        public void guarded() {
            throw new AssertionError("Must not invoke a guarded bean");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @Import(AdvisorBeans.class)
    static class InfrastructureConfiguration {}

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @Import(WrappedAdvisorBeans.class)
    static class WrappedInfrastructureConfiguration {}

    @Configuration(proxyBeanMethods = false)
    @Import(AdvisorBeans.class)
    static class DefaultCreatorConfiguration {
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        static DefaultAdvisorAutoProxyCreator defaultCreator() {
            return new DefaultAdvisorAutoProxyCreator();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(WrappedAdvisorBeans.class)
    static class WrappedDefaultCreatorConfiguration {
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        static DefaultAdvisorAutoProxyCreator defaultCreator() {
            return new DefaultAdvisorAutoProxyCreator();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(AdvisorBeans.class)
    static class CustomCreatorConfiguration {
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        static CustomCreator customCreator() {
            return new CustomCreator();
        }
    }

    static class CustomCreator extends DefaultAdvisorAutoProxyCreator {
        static final AtomicInteger callbacks = new AtomicInteger();

        @Override
        protected boolean isEligibleAdvisorBean(String name) {
            callbacks.incrementAndGet();
            return super.isEligibleAdvisorBean(name);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AdvisorBeans {
        @Bean
        AdvisorFactory candidateAdvisor() {
            return new AdvisorFactory();
        }

        @Bean
        SecuredBean securedBean() {
            return new SecuredBean();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WrappedAdvisorBeans {
        @Bean
        AdvisorFactory candidateAdvisor() {
            return new AdvisorFactory(true);
        }

        @Bean
        SecuredBean securedBean() {
            return new SecuredBean();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(NarrowAdvisorBeans.class)
    static class NarrowCreatorConfiguration {
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        static DefaultAdvisorAutoProxyCreator defaultCreator() {
            return new DefaultAdvisorAutoProxyCreator();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NarrowAdvisorBeans {
        @Bean
        AdvisorFactory candidateAdvisor() {
            return new AdvisorFactory(true, true);
        }

        @Bean
        SecuredBean securedBean() {
            return new SecuredBean();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SynthesizingConfiguration {
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        static SynthesizingCreator creator() {
            return new SynthesizingCreator();
        }
    }

    static class SynthesizingCreator extends AbstractAutoProxyCreator {
        static final AtomicInteger callbacks = new AtomicInteger();

        @Override
        protected Object[] getAdvicesAndAdvisorsForBean(Class<?> type, String name, TargetSource source) {
            callbacks.incrementAndGet();
            return type == LazySecuredService.class
                    ? new Object[] {AuthorizationManagerBeforeMethodInterceptor.secured()}
                    : null;
        }
    }

    public static final class LazySecuredService implements GuardedContract {
        @Override
        @Secured("ROLE_ADMIN")
        public void guarded() {}
    }

    public static class DispatchBase {
        public String value() {
            return "base";
        }
    }

    public static class DispatchSecured extends DispatchBase {
        @Override
        @Secured("ROLE_ADMIN")
        public String value() {
            return "secured";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(NarrowInnerAdvisorBeans.class)
    static class NarrowInnerCreatorConfiguration {
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        static DefaultAdvisorAutoProxyCreator defaultCreator() {
            return new DefaultAdvisorAutoProxyCreator();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NarrowInnerAdvisorBeans {
        @Bean
        AdvisorFactory candidateAdvisor() {
            return new AdvisorFactory(false, false, true);
        }

        @Bean
        SecuredBean securedBean() {
            return new SecuredBean();
        }
    }

    static class SecuredBean {
        final AtomicInteger calls = new AtomicInteger();

        @Secured("ROLE_ADMIN")
        public void guarded() {
            calls.incrementAndGet();
        }
    }

    static final class AdvisorFactory implements FactoryBean<org.springframework.aop.Advisor> {
        final AtomicInteger callbacks = new AtomicInteger();
        private final boolean wrapped;
        private final boolean narrow;
        private final boolean narrowInner;

        AdvisorFactory() {
            this(false, false);
        }

        AdvisorFactory(boolean wrapped) {
            this(wrapped, false);
        }

        AdvisorFactory(boolean wrapped, boolean narrow) {
            this(wrapped, narrow, false);
        }

        AdvisorFactory(boolean wrapped, boolean narrow, boolean narrowInner) {
            this.wrapped = wrapped;
            this.narrow = narrow;
            this.narrowInner = narrowInner;
        }

        @Override
        public org.springframework.aop.Advisor getObject() {
            callbacks.incrementAndGet();
            var interceptor = narrowInner
                    ? new AuthorizationManagerBeforeMethodInterceptor(
                            new NameMatchMethodPointcut(),
                            new org.springframework.security.authorization.method.SecuredAuthorizationManager())
                    : AuthorizationManagerBeforeMethodInterceptor.secured();
            return wrapped
                    ? new DefaultPointcutAdvisor(narrow ? new NameMatchMethodPointcut() : Pointcut.TRUE, interceptor)
                    : interceptor;
        }

        @Override
        public Class<?> getObjectType() {
            callbacks.incrementAndGet();
            return wrapped ? DefaultPointcutAdvisor.class : AuthorizationManagerBeforeMethodInterceptor.class;
        }
    }
}
