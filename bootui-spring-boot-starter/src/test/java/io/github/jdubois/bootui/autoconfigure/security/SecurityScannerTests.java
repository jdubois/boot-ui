package io.github.jdubois.bootui.autoconfigure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.FilterChainModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.PasswordEncoderModel;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.aot.BeanInstanceSupplier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ObjectFactoryCreatingFactoryBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.InstanceSupplier;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.StandardMethodMetadata;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.ObservationAuthorizationManager;
import org.springframework.security.authorization.SingleResultAuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.access.intercept.RequestMatcherDelegatingAuthorizationManager;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.debug.DebugFilter;
import org.springframework.security.web.firewall.FirewalledRequest;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

class SecurityScannerTests {

    private static final int RULE_COUNT = 51;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-04T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path projectRoot;

    @Test
    void noSecurityContextStillReportsStaticAdvisoryWithoutClaimingRuntimeEvidence() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        SecurityScanner scanner = new SecurityScanner((SecurityContext) null, CLOCK, () -> projectRoot);

        SecurityReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.rulesEvaluated()).isEqualTo(1);
        assertThat(report.evidence().usable()).isFalse();
        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo("SEC-STATIC-001");
            assertThat(result.severity()).isEqualTo("INFO");
        });
        assertThat(scanner.ruleViolations(
                                "SEC-STATIC-001", report.violationDetails().scanId(), 0, null)
                        .violationCount())
                .isOne();
    }

    @Test
    void staticInfoDoesNotChangeServletRuntimeEvidence() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        SecurityContext context = contextWith(hardenedChain(), new MockEnvironment());
        SecurityReport baseline = new SecurityScanner(context, CLOCK).scan();
        SecurityReport withStaticCheck = new SecurityScanner(context, CLOCK, () -> projectRoot).scan();

        assertThat(baseline.evidence().usable()).isTrue();
        assertThat(withStaticCheck.evidence()).isEqualTo(baseline.evidence());
        assertThat(withStaticCheck.rulesEvaluated()).isEqualTo(baseline.rulesEvaluated() + 1);
        assertThat(withStaticCheck.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-STATIC-001");
    }

    @Test
    void reportsStaticOnlyAssessmentWhenServletChainIsUnavailable() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");

        SecurityReport report =
                scannerFor(null, null, new MockEnvironment(), projectRoot).scan();

        assertThat(report.scan().message())
                .contains("No Spring Security filter chain was found", "No local static analyzer");
        assertThat(report.scan().analyzer()).isEqualTo("BootUI Static Security Advisor");
        assertThat(report.disclaimer()).contains("local analyzer configuration only");
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-STATIC-001");
        assertThat(report.evidence().usable()).isFalse();
    }

    @Test
    void preservesStaticAssessmentLimitationsWithoutServletChains() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project");

        SecurityReport report =
                scannerFor(null, null, new MockEnvironment(), projectRoot).scan();

        assertThat(report.scan().analyzer()).isEqualTo("BootUI Static Security Advisor");
        assertThat(report.evidence().limitations())
                .contains("Static analyzer configuration could not be assessed from bounded local files.");
    }

    @Test
    void aotFactoryMetadataExcludesOnlyBootUisChainWithoutInvokingItsGenerator() throws Exception {
        String configuration = "io.github.jdubois.bootui.autoconfigure.BootUiSpringSecurityAutoConfiguration";
        var definition = new RootBeanDefinition(SecurityFilterChain.class);
        definition.setFactoryBeanName(configuration);
        definition.setInstanceSupplier(BeanInstanceSupplier.forFactoryMethod(
                        Class.forName(configuration),
                        "bootUiSecurityFilterChain",
                        HttpSecurity.class,
                        BootUiProperties.class)
                .withGenerator((registeredBean, arguments) -> {
                    throw new AssertionError("Must not invoke the AOT bean generator");
                }));
        assertThat(definition.getFactoryMethodName()).isNull();

        assertThat(scanWithNamedBootUiChain(definition).filterChainsAnalyzed()).isEqualTo(1);
    }

    @Test
    void coincidentalBootUiBeanNameAndCustomSupplierDoNotHideAnApplicationChain() {
        var definition = new RootBeanDefinition(SecurityFilterChain.class);
        definition.setFactoryBeanName("io.github.jdubois.bootui.autoconfigure.BootUiSpringSecurityAutoConfiguration");
        definition.setInstanceSupplier(new InstanceSupplier<SecurityFilterChain>() {
            @Override
            public SecurityFilterChain get(RegisteredBean registeredBean) {
                throw new AssertionError("Must not initialize the bean");
            }

            @Override
            public java.lang.reflect.Method getFactoryMethod() {
                throw new AssertionError("Must not invoke custom provenance callbacks");
            }
        });

        assertThat(scanWithNamedBootUiChain(definition).filterChainsAnalyzed()).isEqualTo(2);
    }

    private static SecurityReport scanWithNamedBootUiChain(RootBeanDefinition definition) {
        var bootUi = new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE);
        var application = new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE);
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("bootUiSecurityFilterChain", definition);
        factory.registerSingleton("bootUiSecurityFilterChain", bootUi);
        return scannerFor(new FilterChainProxy(List.of(bootUi, application)), factory)
                .scan();
    }

    @Test
    void unresolvedLazyMethodMetadataRetainsKnownFamiliesWithoutQualifyingHttpFindings() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            try (var application = new AnnotationConfigApplicationContext(MethodInventoryConfiguration.class)) {
                var factory = spy(application.getDefaultListableBeanFactory());
                MethodInventoryService service =
                        AopTestUtils.getUltimateTargetObject(application.getBean(MethodInventoryService.class));
                var scanner = scannerFor(methodInventoryChains(), factory);
                var known = methodInventoryEvidence(factory);
                assertThat(known.methodFamiliesKnown()).isTrue();
                assertThat(known.usedMethodFamilies()).containsExactly("pre-post");
                assertThat(known.enabledMethodFamilies()).contains("pre-post");
                assertHttpMethodQualification(scanner.scan(), "MEDIUM");

                AtomicInteger supplierCalls = new AtomicInteger();
                AtomicInteger factoryCalls = new AtomicInteger();
                FactoryBean<MethodInventoryService> opaqueFactory = new FactoryBean<>() {
                    @Override
                    public MethodInventoryService getObject() {
                        factoryCalls.incrementAndGet();
                        return new MethodInventoryService();
                    }

                    @Override
                    public Class<?> getObjectType() {
                        factoryCalls.incrementAndGet();
                        return MethodInventoryService.class;
                    }
                };
                var unresolved = new RootBeanDefinition();
                unresolved.setLazyInit(true);
                unresolved.setInstanceSupplier(() -> {
                    supplierCalls.incrementAndGet();
                    return opaqueFactory;
                });
                if (variant == 1) unresolved.setBeanClassName("com.example.UnresolvableMethodInventoryBean");
                if (variant == 2) unresolved.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
                factory.registerBeanDefinition("unresolvedApplication", unresolved);
                factory.registerSingleton("existingUnprobedFactory", opaqueFactory);
                // Native bootstrap may resolve types; only the collection window forbids those calls.
                clearInvocations(factory);

                var partial = methodInventoryEvidence(factory);
                var report = scanner.scan();

                assertThat(partial.methodFamiliesKnown())
                        .as("variant %s", variant)
                        .isFalse();
                assertThat(partial.usedMethodFamilies()).isEqualTo(known.usedMethodFamilies());
                assertThat(partial.enabledMethodFamilies()).isEqualTo(known.enabledMethodFamilies());
                assertHttpMethodQualification(report, "HIGH");
                assertThat(report.scan().status()).isEqualTo("PARTIAL");
                assertThat(report.evidence().usable()).isTrue();
                assertThat(report.evidence().coverageComplete()).isFalse();
                assertThat(report.evidence().limitations())
                        .contains("SEC-METHOD-001: Method-security family metadata is incomplete.");
                assertThat(report.analysisErrors()).isEmpty();
                assertThat(factory.containsSingleton("unresolvedApplication")).isFalse();
                assertThat(supplierCalls).hasValue(0);
                assertThat(factoryCalls).hasValue(0);
                assertThat(service.calls).hasValue(0);
                assertNoMethodTypeResolutionCallbacks(factory);
            }
        }
    }

    @Test
    void knownLazyAndNativeInfrastructureMethodMetadataDoesNotBecomeUnknown() throws Exception {
        try (var application = new AnnotationConfigApplicationContext(MethodInventoryConfiguration.class)) {
            var factory = spy(application.getDefaultListableBeanFactory());
            var lazy = new RootBeanDefinition(MethodInventoryService.class);
            lazy.setLazyInit(true);
            factory.registerBeanDefinition("knownLazyApplication", lazy);
            var infrastructure = new RootBeanDefinition(
                    org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator.class);
            infrastructure.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            infrastructure.setLazyInit(true);
            factory.registerBeanDefinition("knownNativeInfrastructure", infrastructure);
            var template = new RootBeanDefinition();
            template.setAbstract(true);
            factory.registerBeanDefinition("abstractTemplate", template);
            factory.registerBeanDefinition("existingApplication", new RootBeanDefinition());
            factory.registerSingleton("existingApplication", new MethodInventoryService());
            var scanner = scannerFor(methodInventoryChains(), factory);
            clearInvocations(factory);

            var evidence = methodInventoryEvidence(factory);
            var report = scanner.scan();

            assertThat(evidence.methodFamiliesKnown()).isTrue();
            assertThat(evidence.usedMethodFamilies()).containsExactly("pre-post");
            assertThat(evidence.enabledMethodFamilies()).contains("pre-post");
            assertHttpMethodQualification(report, "MEDIUM");
            assertThat(report.evidence().limitations()).noneMatch(value -> value.startsWith("SEC-METHOD-"));
            assertThat(report.analysisErrors()).isEmpty();
            assertThat(factory.containsSingleton("knownLazyApplication")).isFalse();
            assertThat(factory.containsSingleton("knownNativeInfrastructure")).isFalse();
            assertNoMethodTypeResolutionCallbacks(factory);
        }
    }

    @Test
    void abstractMethodDefinitionDoesNotHideAnExistingSingletonsAdditionalFamily() throws Exception {
        try (var application = new AnnotationConfigApplicationContext(MethodInventoryConfiguration.class)) {
            var factory = spy(application.getDefaultListableBeanFactory());
            AtomicInteger supplierCalls = new AtomicInteger();
            var definition = new RootBeanDefinition();
            definition.setAbstract(true);
            definition.setInstanceSupplier(() -> {
                supplierCalls.incrementAndGet();
                throw new AssertionError("Must not create a bean from the abstract definition");
            });
            factory.registerBeanDefinition("existingSecuredApplication", definition);
            var singleton = new SecuredMethodInventoryService();
            factory.registerSingleton("existingSecuredApplication", singleton);
            var scanner = scannerFor(methodInventoryChains(), factory);
            clearInvocations(factory);

            var evidence = methodInventoryEvidence(factory);
            var report = scanner.scan();

            assertThat(evidence.methodFamiliesKnown()).isTrue();
            assertThat(evidence.usedMethodFamilies()).containsExactlyInAnyOrder("pre-post", "secured");
            assertThat(evidence.enabledMethodFamilies()).contains("pre-post").doesNotContain("secured");
            assertHttpMethodQualification(report, "HIGH");
            assertThat(report.results())
                    .filteredOn(result -> result.id().equals("SEC-METHOD-001"))
                    .singleElement()
                    .satisfies(result -> {
                        assertThat(result.status()).isEqualTo("VIOLATION");
                        assertThat(result.severity()).isEqualTo("HIGH");
                        assertThat(result.violationCount()).isOne();
                        assertThat(result.sampleViolations()).anyMatch(value -> value.contains("secured"));
                    });
            assertThat(report.evidence().usable()).isTrue();
            assertThat(report.evidence().limitations()).noneMatch(value -> value.startsWith("SEC-METHOD-"));
            assertThat(report.analysisErrors()).isEmpty();
            assertThat(factory.getSingleton("existingSecuredApplication")).isSameAs(singleton);
            assertThat(supplierCalls).hasValue(0);
            assertThat(singleton.calls).hasValue(0);
            assertNoMethodTypeResolutionCallbacks(factory);
        }
    }

    @Test
    void knownEmptyMethodMetadataIsNeitherMissingEvidenceNorMethodAuthorization() throws Exception {
        var factory = spy(new DefaultListableBeanFactory());
        var definition = new RootBeanDefinition(Object.class);
        definition.setLazyInit(true);
        factory.registerBeanDefinition("knownUnannotatedApplication", definition);
        var scanner = scannerFor(methodInventoryChains(), factory);
        clearInvocations(factory);

        var evidence = methodInventoryEvidence(factory);
        var report = scanner.scan();

        assertThat(evidence.methodFamiliesKnown()).isTrue();
        assertThat(evidence.usedMethodFamilies()).isEmpty();
        assertThat(evidence.enabledMethodFamilies()).isEmpty();
        assertHttpMethodQualification(report, "HIGH");
        assertThat(report.evidence().limitations()).noneMatch(value -> value.startsWith("SEC-METHOD-"));
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(factory.containsSingleton("knownUnannotatedApplication")).isFalse();
        assertNoMethodTypeResolutionCallbacks(factory);
    }

    @Test
    void nativeFinalFactoryProductsAreObservedWithoutCreatingLazyOrPrototypeBeans() throws Exception {
        for (String product : List.of("finalProduct", "staticFinalProduct")) {
            try (var application = methodProductApplication(product)) {
                var factory = spy(application.getDefaultListableBeanFactory());
                assertThat(factory.containsSingleton(product)).isFalse();
                var scanner = scannerFor(methodInventoryChains(), factory);
                clearInvocations(factory);

                var evidence = methodInventoryEvidence(factory);
                var report = scanner.scan();

                assertThat(evidence.methodFamiliesKnown()).as("%s", product).isTrue();
                assertThat(evidence.usedMethodFamilies()).containsExactlyInAnyOrder("pre-post", "secured");
                assertHttpMethodQualification(report, "HIGH");
                assertThat(report.results())
                        .filteredOn(result -> result.id().equals("SEC-METHOD-001"))
                        .singleElement()
                        .satisfies(result ->
                                assertThat(result.sampleViolations()).anyMatch(value -> value.contains("secured")));
                assertThat(factory.containsSingleton(product)).isFalse();
                assertThat(MethodProductConfiguration.calls).hasValue(0);
                assertNoMethodTypeResolutionCallbacks(factory);
            }
        }
    }

    @Test
    void nonFinalAndUnsupportedFactoryProductsRemainUnknownWithoutCallbacks() throws Exception {
        for (String product : List.of(
                "nonFinalProduct",
                "factoryProduct",
                "arrayProduct",
                "primitiveProduct",
                "interfaceProduct",
                "objectProduct")) {
            try (var application = methodProductApplication(product)) {
                var factory = spy(application.getDefaultListableBeanFactory());
                var scanner = scannerFor(methodInventoryChains(), factory);
                clearInvocations(factory);

                var evidence = methodInventoryEvidence(factory);
                var report = scanner.scan();

                assertThat(evidence.methodFamiliesKnown()).as("%s", product).isFalse();
                assertThat(evidence.usedMethodFamilies()).containsExactly("pre-post");
                assertHttpMethodQualification(report, "HIGH");
                assertThat(report.evidence().coverageComplete()).isFalse();
                assertThat(report.evidence().limitations())
                        .contains("SEC-METHOD-001: Method-security family metadata is incomplete.");
                assertThat(factory.containsSingleton(product)).isFalse();
                assertThat(MethodProductConfiguration.calls).hasValue(0);
                assertNoMethodTypeResolutionCallbacks(factory);
            }
        }
    }

    @Test
    void contradictoryFactoryPathsAndFactoryObjectsCannotStandInForProducts() throws Exception {
        for (int variant = 0; variant < 8; variant++) {
            try (var application = methodProductApplication("finalProduct")) {
                var factory = spy(application.getDefaultListableBeanFactory());
                var definition = (RootBeanDefinition) factory.getBeanDefinition("finalProduct");
                AtomicInteger callbacks = new AtomicInteger();
                if (variant == 0) {
                    definition.setInstanceSupplier(() -> {
                        callbacks.incrementAndGet();
                        throw new AssertionError("Must not invoke a custom supplier");
                    });
                } else if (variant == 1) {
                    definition.setInstanceSupplier(BeanInstanceSupplier.forFactoryMethod(
                                    MethodProductConfiguration.class, "finalProduct")
                            .withGenerator((registeredBean, arguments) -> {
                                callbacks.incrementAndGet();
                                throw new AssertionError("Native metadata does not prove a generator's product");
                            }));
                } else if (variant == 2) {
                    definition.setInstanceSupplier(new InstanceSupplier<FinalMethodProduct>() {
                        @Override
                        public FinalMethodProduct get(RegisteredBean bean) {
                            callbacks.incrementAndGet();
                            throw new AssertionError("Must not initialize a product");
                        }

                        @Override
                        public java.lang.reflect.Method getFactoryMethod() {
                            callbacks.incrementAndGet();
                            throw new AssertionError("Must not ask a custom supplier for provenance");
                        }
                    });
                } else if (variant == 3) {
                    definition.setFactoryMethodName("nonFinalProduct");
                } else if (variant == 4) {
                    definition.setFactoryBeanName("differentFactory");
                    factory.registerSingleton("differentFactory", new Object());
                } else if (variant == 5) {
                    factory.removeBeanDefinition("finalProduct");
                    var unbound = new RootBeanDefinition(ObjectFactoryCreatingFactoryBean.class);
                    unbound.setFactoryMethodName("product");
                    factory.registerBeanDefinition("finalProduct", unbound);
                } else if (variant == 6) {
                    factory.removeBeanDefinition("finalProduct");
                    factory.registerBeanDefinition("finalProduct", new RootBeanDefinition(FinalMethodProduct.class));
                    factory.registerSingleton("finalProduct", new FinalMethodProductFactory(callbacks));
                } else {
                    factory.removeBeanDefinition("finalProduct");
                    var overloaded = new AnnotatedGenericBeanDefinition(
                            AnnotationMetadata.introspect(OverloadedMethodFactory.class),
                            new StandardMethodMetadata(OverloadedMethodFactory.class.getDeclaredMethod("product")));
                    overloaded.setFactoryBeanName("overloadedFactory");
                    overloaded.setFactoryMethodName("product");
                    factory.registerBeanDefinition("finalProduct", overloaded);
                    factory.registerSingleton("overloadedFactory", new OverloadedMethodFactory());
                }
                var scanner = scannerFor(methodInventoryChains(), factory);
                clearInvocations(factory);

                assertThat(methodInventoryEvidence(factory).methodFamiliesKnown())
                        .as("variant %s", variant)
                        .isFalse();
                var report = scanner.scan();
                assertHttpMethodQualification(report, "HIGH");
                assertThat(report.evidence().coverageComplete()).isFalse();
                assertThat(callbacks).hasValue(0);
                assertThat(MethodProductConfiguration.calls).hasValue(0);
                assertNoMethodTypeResolutionCallbacks(factory);
            }
        }
    }

    @Test
    void applicationDefinitionUsesItsBeanClassLoaderWithoutInitializingTheClass() throws Exception {
        Path source = projectRoot.resolve("OnlyApplicationService.java");
        Files.writeString(source, """
                package isolated.application;
                public class OnlyApplicationService {
                    static {
                        if (Boolean.parseBoolean("true")) throw new AssertionError("Must not initialize");
                    }
                    @org.springframework.security.access.annotation.Secured("ROLE_ADMIN")
                    public void guarded() {}
                }
                """);
        assertThat(ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "--release",
                                "17",
                                "-classpath",
                                System.getProperty("java.class.path"),
                                "-d",
                                projectRoot.toString(),
                                source.toString()))
                .isZero();
        try (var loader =
                new URLClassLoader(new URL[] {projectRoot.toUri().toURL()}, SecurityScanner.class.getClassLoader())) {
            assertThatThrownBy(() -> Class.forName(
                            "isolated.application.OnlyApplicationService",
                            false,
                            SecurityScanner.class.getClassLoader()))
                    .isInstanceOf(ClassNotFoundException.class);
            var factory = spy(new DefaultListableBeanFactory());
            factory.setBeanClassLoader(loader);
            var definition = new RootBeanDefinition();
            definition.setBeanClassName("isolated.application.OnlyApplicationService");
            definition.setLazyInit(true);
            factory.registerBeanDefinition("applicationService", definition);
            var scanner = scannerFor(methodInventoryChains(), factory);
            clearInvocations(factory);

            var evidence = methodInventoryEvidence(factory);
            var report = scanner.scan();

            assertThat(evidence.methodFamiliesKnown()).isTrue();
            assertThat(evidence.usedMethodFamilies()).containsExactly("secured");
            assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-METHOD-001");
            assertThat(factory.containsSingleton("applicationService")).isFalse();
            assertNoMethodTypeResolutionCallbacks(factory);
        }
    }

    @Test
    void unenumeratedSingletonAdvisorsPreventMethodFamilyCompleteness() throws Exception {
        try (var application = new AnnotationConfigApplicationContext(MethodInventoryConfiguration.class)) {
            var factory = application.getDefaultListableBeanFactory();
            for (int index = factory.getSingletonCount(); index <= 5000; index++) {
                factory.registerSingleton("inventoryPadding" + index, new Object());
            }
            var evidence = methodInventoryEvidence(factory);

            assertThat(evidence.methodFamiliesKnown()).isFalse();
            assertThat(evidence.usedMethodFamilies()).containsExactly("pre-post");
            assertThat(evidence.enabledMethodFamilies()).contains("pre-post");
        }
    }

    @Test
    void opaqueProductCannotProveAnObservedMethodFamilyIsUnactivated() throws Exception {
        try (var application = new AnnotationConfigApplicationContext(MethodInventoryConfiguration.class)) {
            var factory = spy(application.getDefaultListableBeanFactory());
            factory.registerBeanDefinition(
                    "observedSecured", new RootBeanDefinition(SecuredMethodInventoryService.class));
            var observed = new SecuredMethodInventoryService();
            factory.registerSingleton("observedSecured", observed);
            AtomicInteger callbacks = new AtomicInteger();
            var unresolved = new RootBeanDefinition();
            unresolved.setLazyInit(true);
            unresolved.setInstanceSupplier(() -> {
                callbacks.incrementAndGet();
                throw new AssertionError("Opaque code is not activation evidence");
            });
            factory.registerBeanDefinition("opaqueProduct", unresolved);
            var scanner = scannerFor(methodInventoryChains(), factory);
            clearInvocations(factory);

            var evidence = methodInventoryEvidence(factory);
            var report = scanner.scan();

            assertThat(evidence.methodFamiliesKnown()).isFalse();
            assertThat(evidence.usedMethodFamilies()).containsExactlyInAnyOrder("pre-post", "secured");
            assertThat(evidence.enabledMethodFamilies()).contains("pre-post").doesNotContain("secured");
            assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-METHOD-001");
            assertThat(report.evidence().limitations())
                    .contains("SEC-METHOD-001: Method-security family metadata is incomplete.");
            assertHttpMethodQualification(report, "HIGH");
            assertThat(callbacks).hasValue(0);
            assertThat(observed.calls).hasValue(0);
            assertNoMethodTypeResolutionCallbacks(factory);
        }
    }

    @Test
    void nativeJdkMethodProxyDoesNotProveImplementationFamilyCompleteness() throws Exception {
        JdkMethodConfiguration.calls.set(0);
        try (var application = new AnnotationConfigApplicationContext(
                MethodInventoryConfiguration.class, JdkMethodConfiguration.class)) {
            assertThat(Proxy.isProxyClass(
                            application.getBean("jdkMethodService").getClass()))
                    .isTrue();
            var factory = spy(application.getDefaultListableBeanFactory());
            var scanner = scannerFor(methodInventoryChains(), factory);
            clearInvocations(factory);

            var evidence = methodInventoryEvidence(factory);
            var report = scanner.scan();

            assertThat(JdkMethodConfiguration.calls).hasValue(0);
            assertNoMethodTypeResolutionCallbacks(factory);
            assertThat(evidence.usedMethodFamilies()).contains("pre-post");
            assertThat(evidence.methodFamiliesKnown())
                    .as("A native JDK proxy hides implementation-only @Secured; HTTP findings: %s", report.results())
                    .isFalse();
            assertHttpMethodQualification(report, "HIGH");
            assertThat(report.evidence().coverageComplete()).isFalse();
        }
    }

    @Test
    void nativeJdkProxyRetainsObservedInterfaceFamiliesWithoutTargetProof() throws Exception {
        JdkMethodConfiguration.calls.set(0);
        try (var application = new AnnotationConfigApplicationContext(JdkMethodConfiguration.class)) {
            assertThat(Proxy.isProxyClass(
                            application.getBean("jdkMethodService").getClass()))
                    .isTrue();
            var factory = spy(application.getDefaultListableBeanFactory());
            var scanner = scannerFor(methodInventoryChains(), factory);
            clearInvocations(factory);

            var evidence = methodInventoryEvidence(factory);
            var report = scanner.scan();

            assertThat(JdkMethodConfiguration.calls).hasValue(0);
            assertNoMethodTypeResolutionCallbacks(factory);
            assertThat(evidence.methodFamiliesKnown()).isFalse();
            assertThat(evidence.enabledMethodFamilies()).contains("pre-post");
            assertThat(evidence.usedMethodFamilies())
                    .as("Readable interface annotations remain facts even when target annotations are unknown")
                    .containsExactly("pre-post");
            assertHttpMethodQualification(report, "HIGH");
            assertThat(report.evidence().coverageComplete()).isFalse();
        }
    }

    @Test
    void uncreatedChildDefinitionCannotHideAnInheritedFactoryMethod() throws Exception {
        try (var application = new AnnotationConfigApplicationContext(MethodInventoryConfiguration.class)) {
            var factory = spy(application.getDefaultListableBeanFactory());
            AtomicInteger callbacks = new AtomicInteger();
            var parent = new RootBeanDefinition(UnannotatedMethodProduct.class);
            parent.setAbstract(true);
            parent.setFactoryBeanName("parentCreationFactory");
            parent.setFactoryMethodName("product");
            factory.registerSingleton("parentCreationFactory", new ParentCreationFactory(callbacks));
            factory.registerBeanDefinition("creationParent", parent);
            var child = new GenericBeanDefinition();
            child.setParentName("creationParent");
            child.setBeanClass(UnannotatedMethodProduct.class);
            child.setLazyInit(true);
            factory.registerBeanDefinition("lazyCreationChild", child);
            assertThat(child.getFactoryBeanName()).isNull();
            assertThat(child.getFactoryMethodName()).isNull();
            var scanner = scannerFor(methodInventoryChains(), factory);
            clearInvocations(factory);

            var beforeCreation = methodInventoryEvidence(factory);
            var report = scanner.scan();

            assertThat(callbacks).hasValue(0);
            assertNoMethodTypeResolutionCallbacks(factory);
            // Verify native inherited creation only after ending the collection window.
            assertThat(factory.getBean("lazyCreationChild")).isInstanceOf(UnannotatedMethodProduct.class);
            assertThat(callbacks).hasValue(1);
            clearInvocations(factory);
            var afterCreation = methodInventoryEvidence(factory);
            assertThat(afterCreation.methodFamiliesKnown()).isTrue();
            assertThat(callbacks).hasValue(1);
            assertNoMethodTypeResolutionCallbacks(factory);
            assertThat(beforeCreation.methodFamiliesKnown())
                    .as(
                            "Local factory metadata is null but Spring selected the inherited factory; prior report: %s",
                            report.results())
                    .isFalse();
            assertHttpMethodQualification(report, "HIGH");
        }
    }

    @Test
    void inheritedFactoryOverloadCannotBorrowAnUnselectedFinalReturnType() throws Exception {
        InheritedMethodFactory.parentCalls.set(0);
        ChildMethodFactory.childCalls.set(0);
        try (var application = new AnnotationConfigApplicationContext(MethodInventoryConfiguration.class)) {
            var factory = spy(application.getDefaultListableBeanFactory());
            factory.registerSingleton("inheritedMethodFactory", new ChildMethodFactory());
            var definition = new AnnotatedGenericBeanDefinition(
                    AnnotationMetadata.introspect(ChildMethodFactory.class),
                    new StandardMethodMetadata(ChildMethodFactory.class.getDeclaredMethod("product")));
            definition.setFactoryBeanName("inheritedMethodFactory");
            definition.setFactoryMethodName("product");
            definition.getConstructorArgumentValues().addIndexedArgumentValue(0, "native-selected");
            definition.setScope("prototype");
            factory.registerBeanDefinition("inheritedMethodProduct", definition);
            var scanner = scannerFor(methodInventoryChains(), factory);
            clearInvocations(factory);

            var evidence = methodInventoryEvidence(factory);
            var report = scanner.scan();

            assertThat(InheritedMethodFactory.parentCalls).hasValue(0);
            assertThat(ChildMethodFactory.childCalls).hasValue(0);
            assertNoMethodTypeResolutionCallbacks(factory);
            // An explicit argument makes the inherited overload the actual native creation path.
            assertThat(factory.getBean("inheritedMethodProduct")).isInstanceOf(UnannotatedMethodProduct.class);
            assertThat(InheritedMethodFactory.parentCalls).hasValue(1);
            assertThat(ChildMethodFactory.childCalls).hasValue(0);
            assertThat(evidence.methodFamiliesKnown())
                    .as("The metadata's final-return overload was not selected; prior report: %s", report.results())
                    .isFalse();
            assertHttpMethodQualification(report, "HIGH");
        }
    }

    @Test
    void methodTypeObservationNeverCreatesAnEarlySingletonReference() throws Exception {
        var factory = new EarlyMethodBeanFactory();
        factory.registerBeanDefinition("pendingMethodBean", new RootBeanDefinition(UnannotatedMethodProduct.class));
        factory.installEarlyReference("pendingMethodBean");
        assertThat(factory.containsSingleton("pendingMethodBean")).isFalse();
        try {
            var observer = SecurityScanner.class.getDeclaredMethod(
                    "methodBeanType",
                    org.springframework.beans.factory.config.ConfigurableListableBeanFactory.class,
                    String.class,
                    org.springframework.beans.factory.support.AbstractBeanDefinition.class);
            observer.setAccessible(true);

            observer.invoke(null, factory, "pendingMethodBean", factory.getBeanDefinition("pendingMethodBean"));

            assertThat(factory.callbacks)
                    .as("Passive type observation must not invoke a native early ObjectFactory")
                    .hasValue(0);
        } finally {
            factory.finishEarlyReference("pendingMethodBean");
        }
    }

    @Test
    void singletonInventoryNeverCreatesAnEarlySingletonReference() throws Exception {
        var factory = new EarlyMethodBeanFactory();
        factory.registerBeanDefinition("pendingInventoryBean", new RootBeanDefinition(UnannotatedMethodProduct.class));
        factory.installEarlyReference("pendingInventoryBean");
        assertThat(factory.containsSingleton("pendingInventoryBean")).isFalse();
        try {
            var observer = SecurityScanner.class.getDeclaredMethod(
                    "existingSingletons", ListableBeanFactory.class, Class.class);
            observer.setAccessible(true);

            observer.invoke(null, factory, Object.class);

            assertThat(factory.callbacks)
                    .as("The preexisting inventory helper must not materialize an early reference either")
                    .hasValue(0);
        } finally {
            factory.finishEarlyReference("pendingInventoryBean");
        }
    }

    @Test
    void applicationLoaderFailureKeepsIndependentNativeChainFindings() throws Exception {
        try (var application = new AnnotationConfigApplicationContext(MethodInventoryConfiguration.class)) {
            var factory = spy(application.getDefaultListableBeanFactory());
            factory.setBeanClassLoader(new ClassLoader(SecurityScanner.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.equals("isolated.application.RejectedType")) {
                        throw new IllegalStateException("private loader detail");
                    }
                    return super.loadClass(name, resolve);
                }
            });
            var definition = new RootBeanDefinition();
            definition.setBeanClassName("isolated.application.RejectedType");
            definition.setLazyInit(true);
            factory.registerBeanDefinition("rejectedApplicationType", definition);
            var scanner = scannerFor(methodInventoryChains(), factory);
            clearInvocations(factory);

            var report = scanner.scan();

            assertNoMethodTypeResolutionCallbacks(factory);
            assertThat(report.filterChainsAnalyzed())
                    .as("A single metadata loader failure must not discard both observed native chains")
                    .isEqualTo(2);
            assertHttpMethodQualification(report, "HIGH");
            assertThat(report.evidence().usable()).isTrue();
            assertThat(report.evidence().coverageComplete()).isFalse();
            assertThat(report.evidence().limitations())
                    .contains("SEC-METHOD-001: Method-security family metadata is incomplete.")
                    .noneMatch(value -> value.contains("private loader detail"));
            assertThat(report.analysisErrors()).isEmpty();
        }
    }

    @Test
    void uncreatedConstructorBasedFactoryBeanTypesAreNotTheirProducts() throws Exception {
        for (boolean framework : List.of(false, true)) {
            try (var application = new AnnotationConfigApplicationContext(MethodInventoryConfiguration.class)) {
                var factory = spy(application.getDefaultListableBeanFactory());
                AtomicInteger callbacks = new AtomicInteger();
                var definition = new RootBeanDefinition(
                        framework ? ObjectFactoryCreatingFactoryBean.class : FinalMethodProductFactory.class);
                definition.setLazyInit(true);
                if (!framework) definition.getConstructorArgumentValues().addIndexedArgumentValue(0, callbacks);
                factory.registerBeanDefinition("uncreatedConstructorFactory", definition);
                var scanner = scannerFor(methodInventoryChains(), factory);
                clearInvocations(factory);

                var evidence = methodInventoryEvidence(factory);
                var report = scanner.scan();

                assertThat(evidence.methodFamiliesKnown())
                        .as("framework=%s", framework)
                        .isFalse();
                assertHttpMethodQualification(report, "HIGH");
                assertThat(factory.containsSingleton("uncreatedConstructorFactory"))
                        .isFalse();
                assertThat(callbacks).hasValue(0);
                assertNoMethodTypeResolutionCallbacks(factory);
            }
        }
    }

    @Test
    void unsupportedDefinitionAccessorsAreNotInvokedAndKnownSingletonFactsRemain() throws Exception {
        try (var application = new AnnotationConfigApplicationContext(MethodInventoryConfiguration.class)) {
            var factory = spy(application.getDefaultListableBeanFactory());
            var definition = new GuardedMethodDefinition();
            factory.registerBeanDefinition("guardedDefinition", definition);
            var singleton = new SecuredMethodInventoryService();
            factory.registerSingleton("guardedDefinition", singleton);
            definition.armed = true;
            var scanner = scannerFor(methodInventoryChains(), factory);
            clearInvocations(factory);

            var evidence = methodInventoryEvidence(factory);
            var report = scanner.scan();

            assertThat(evidence.methodFamiliesKnown()).isFalse();
            assertThat(evidence.usedMethodFamilies()).containsExactlyInAnyOrder("pre-post", "secured");
            assertThat(definition.callbacks).hasValue(0);
            assertThat(singleton.calls).hasValue(0);
            assertHttpMethodQualification(report, "HIGH");
            assertNoMethodTypeResolutionCallbacks(factory);
            definition.armed = false;
        }
    }

    @Test
    void enhancedConfigurationOwnerDoesNotProveALazyFinalProduct() throws Exception {
        EnhancedMethodConfiguration.calls.set(0);
        try (var application = new AnnotationConfigApplicationContext(
                MethodInventoryConfiguration.class, EnhancedMethodConfiguration.class)) {
            assertThat(application
                            .getBean(EnhancedMethodConfiguration.class)
                            .getClass()
                            .getSuperclass())
                    .isEqualTo(EnhancedMethodConfiguration.class);
            var factory = spy(application.getDefaultListableBeanFactory());
            var scanner = scannerFor(methodInventoryChains(), factory);
            clearInvocations(factory);

            var evidence = methodInventoryEvidence(factory);
            var report = scanner.scan();

            assertThat(evidence.methodFamiliesKnown()).isFalse();
            assertThat(EnhancedMethodConfiguration.calls).hasValue(0);
            assertThat(factory.containsSingleton("enhancedMethodProduct")).isFalse();
            assertHttpMethodQualification(report, "HIGH");
            assertNoMethodTypeResolutionCallbacks(factory);
        }
    }

    private static AnnotationConfigApplicationContext methodProductApplication(String retainedProduct) {
        MethodProductConfiguration.calls.set(0);
        var application = new AnnotationConfigApplicationContext(
                MethodInventoryConfiguration.class, MethodProductConfiguration.class);
        for (String product : List.of(
                "finalProduct",
                "staticFinalProduct",
                "nonFinalProduct",
                "factoryProduct",
                "arrayProduct",
                "primitiveProduct",
                "interfaceProduct",
                "objectProduct")) {
            if (!product.equals(retainedProduct)) application.removeBeanDefinition(product);
        }
        return application;
    }

    private static SecurityContext.Evidence methodInventoryEvidence(DefaultListableBeanFactory factory)
            throws ReflectiveOperationException {
        var method = SecurityScanner.class.getDeclaredMethod(
                "discoverEvidence", ListableBeanFactory.class, List.class, Environment.class, List.class);
        method.setAccessible(true);
        return (SecurityContext.Evidence)
                method.invoke(null, factory, List.of(), new MockEnvironment(), new ArrayList<String>());
    }

    private static FilterChainProxy methodInventoryChains() {
        return new FilterChainProxy(List.of(
                new DefaultSecurityFilterChain(
                        PathPatternRequestMatcher.pathPattern("/missing/**"),
                        new UsernamePasswordAuthenticationFilter()),
                new DefaultSecurityFilterChain(
                        AnyRequestMatcher.INSTANCE,
                        new UsernamePasswordAuthenticationFilter(),
                        new AuthorizationFilter(SingleResultAuthorizationManager.permitAll()))));
    }

    private static void assertHttpMethodQualification(SecurityReport report, String severity) {
        assertThat(report.results())
                .filteredOn(result -> List.of("SEC-AUTHZ-001", "SEC-AUTHZ-002").contains(result.id()))
                .hasSize(2)
                .allSatisfy(result -> {
                    assertThat(result.status()).isEqualTo("VIOLATION");
                    assertThat(result.severity()).isEqualTo(severity);
                    assertThat(result.violationCount()).isOne();
                });
    }

    private static void assertNoMethodTypeResolutionCallbacks(DefaultListableBeanFactory factory) {
        verify(factory, never()).getBean(anyString());
        verify(factory, never()).getBean(anyString(), org.mockito.ArgumentMatchers.<Class<Object>>any());
        verify(factory, never()).getBean(anyString(), any(Object[].class));
        verify(factory, never()).getBean(org.mockito.ArgumentMatchers.<Class<Object>>any());
        verify(factory, never()).getBean(org.mockito.ArgumentMatchers.<Class<Object>>any(), any(Object[].class));
        verify(factory, never()).getType(anyString());
        verify(factory, never()).getType(anyString(), anyBoolean());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodInventoryConfiguration {
        @Bean
        MethodInventoryService methodInventoryService() {
            return new MethodInventoryService();
        }
    }

    static class MethodInventoryService {
        final AtomicInteger calls = new AtomicInteger();

        @PreAuthorize("hasRole('ADMIN')")
        public void guarded() {
            calls.incrementAndGet();
        }
    }

    static class SecuredMethodInventoryService {
        final AtomicInteger calls = new AtomicInteger();

        @Secured("ROLE_ADMIN")
        public void guarded() {
            calls.incrementAndGet();
        }
    }

    interface JdkMethodContract {
        @PreAuthorize("hasRole('ADMIN')")
        void guarded();

        void securedOnly();
    }

    static class JdkMethodService implements JdkMethodContract {
        @Override
        @PreAuthorize("hasRole('ADMIN')")
        public void guarded() {
            JdkMethodConfiguration.calls.incrementAndGet();
        }

        @Override
        @Secured("ROLE_ADMIN")
        public void securedOnly() {
            JdkMethodConfiguration.calls.incrementAndGet();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity(proxyTargetClass = false)
    static class JdkMethodConfiguration {
        static final AtomicInteger calls = new AtomicInteger();

        @Bean
        JdkMethodContract jdkMethodService() {
            return new JdkMethodService();
        }
    }

    static class UnannotatedMethodProduct {}

    static class ParentCreationFactory {
        private final AtomicInteger callbacks;

        ParentCreationFactory(AtomicInteger callbacks) {
            this.callbacks = callbacks;
        }

        UnannotatedMethodProduct product() {
            callbacks.incrementAndGet();
            return new UnannotatedMethodProduct();
        }
    }

    static class InheritedMethodFactory {
        static final AtomicInteger parentCalls = new AtomicInteger();

        UnannotatedMethodProduct product(String argument) {
            parentCalls.incrementAndGet();
            return new UnannotatedMethodProduct();
        }
    }

    static class ChildMethodFactory extends InheritedMethodFactory {
        static final AtomicInteger childCalls = new AtomicInteger();

        FinalMethodProduct product() {
            childCalls.incrementAndGet();
            return new FinalMethodProduct();
        }
    }

    static class EarlyMethodBeanFactory extends DefaultListableBeanFactory {
        final AtomicInteger callbacks = new AtomicInteger();

        void installEarlyReference(String name) {
            beforeSingletonCreation(name);
            addSingletonFactory(name, () -> {
                callbacks.incrementAndGet();
                return new UnannotatedMethodProduct();
            });
        }

        void finishEarlyReference(String name) {
            afterSingletonCreation(name);
            destroySingleton(name);
        }
    }

    static class GuardedMethodDefinition extends RootBeanDefinition {
        final AtomicInteger callbacks = new AtomicInteger();
        boolean armed;

        @Override
        public String getFactoryBeanName() {
            if (armed) {
                callbacks.incrementAndGet();
                throw new AssertionError("Must not invoke unsupported definition accessors");
            }
            return super.getFactoryBeanName();
        }

        @Override
        public String getFactoryMethodName() {
            if (armed) {
                callbacks.incrementAndGet();
                throw new AssertionError("Must not invoke unsupported definition accessors");
            }
            return super.getFactoryMethodName();
        }

        @Override
        public String getBeanClassName() {
            if (armed) {
                callbacks.incrementAndGet();
                throw new AssertionError("Must not invoke unsupported definition accessors");
            }
            return super.getBeanClassName();
        }
    }

    @Configuration
    static class EnhancedMethodConfiguration {
        static final AtomicInteger calls = new AtomicInteger();

        @Bean
        @Lazy
        @Scope("prototype")
        FinalMethodProduct enhancedMethodProduct() {
            calls.incrementAndGet();
            throw new AssertionError("Must not invoke an enhanced configuration owner");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MethodProductConfiguration {
        static final AtomicInteger calls = new AtomicInteger();

        @Bean
        @Lazy
        @Scope("prototype")
        FinalMethodProduct finalProduct() {
            calls.incrementAndGet();
            throw new AssertionError("Must not create a prototype final product");
        }

        @Bean
        @Lazy
        @Scope("prototype")
        static FinalMethodProduct staticFinalProduct() {
            calls.incrementAndGet();
            throw new AssertionError("Must not execute a static factory");
        }

        @Bean
        @Lazy
        @Scope("prototype")
        MethodInventoryService nonFinalProduct() {
            calls.incrementAndGet();
            throw new AssertionError("Nonfinal declared type is incomplete");
        }

        @Bean
        @Lazy
        @Scope("prototype")
        FinalMethodProductFactory factoryProduct() {
            calls.incrementAndGet();
            throw new AssertionError("Factory class is not its product");
        }

        @Bean
        @Lazy
        @Scope("prototype")
        String[] arrayProduct() {
            calls.incrementAndGet();
            throw new AssertionError("An array's final modifier proves no product inventory");
        }

        @Bean
        @Lazy
        @Scope("prototype")
        int primitiveProduct() {
            calls.incrementAndGet();
            throw new AssertionError("Primitive is not an ordinary bean type");
        }

        @Bean
        @Lazy
        @Scope("prototype")
        Runnable interfaceProduct() {
            calls.incrementAndGet();
            throw new AssertionError("Interface return does not prove an implementation");
        }

        @Bean
        @Lazy
        @Scope("prototype")
        Object objectProduct() {
            calls.incrementAndGet();
            throw new AssertionError("Object return does not prove a product type");
        }
    }

    static final class FinalMethodProduct {
        @Secured("ROLE_ADMIN")
        public void guarded() {
            throw new AssertionError("Declaration observation is not method invocation");
        }
    }

    static final class FinalMethodProductFactory implements FactoryBean<FinalMethodProduct> {
        private final AtomicInteger callbacks;

        FinalMethodProductFactory(AtomicInteger callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public FinalMethodProduct getObject() {
            callbacks.incrementAndGet();
            throw new AssertionError("Must not create a FactoryBean product");
        }

        @Override
        public Class<?> getObjectType() {
            callbacks.incrementAndGet();
            throw new AssertionError("Must not query FactoryBean product type");
        }
    }

    static class OverloadedMethodFactory {
        FinalMethodProduct product() {
            throw new AssertionError("Must not execute an overloaded factory");
        }

        FinalMethodProduct product(String ignored) {
            throw new AssertionError("Must not resolve overloaded arguments");
        }
    }

    @Test
    void scanReportsSecurityFindingsAcrossCategories() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of(
                        "SecurityContextHolderFilter",
                        "HeaderWriterFilter",
                        "BasicAuthenticationFilter",
                        "SessionManagementFilter",
                        "AuthorizationFilter"),
                Boolean.TRUE,
                Boolean.TRUE,
                List.of());
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "*")
                .withProperty("spring.security.user.name", "admin")
                .withProperty("spring.security.user.password", "admin");
        SecurityContext context = new SecurityContext(
                List.of(chain),
                List.of(),
                List.of(new CorsConfigModel("/**", List.of("*"), List.of(), List.of(), List.of(), Boolean.TRUE)),
                true,
                List.of(),
                false,
                false,
                false,
                false,
                List.of(),
                false,
                false,
                List.of(),
                false,
                true,
                environment);
        SecurityScanner scanner = new SecurityScanner(context, CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(scanner.applyDismissals(report, java.util.Set.of("SEC-AUTHZ-002"))
                        .evidence())
                .isEqualTo(report.evidence());
        assertThat(report.filterChainsAnalyzed()).isEqualTo(1);
        assertThat(report.rulesEvaluated()).isEqualTo(RULE_COUNT);
        assertThat(report.violationsFound()).isPositive();
        assertThat(report.violationsFound()).isEqualTo(report.results().size());
        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-AUTHZ-002", "SEC-CSRF-002", "SEC-SESSION-001", "SEC-CORS-002", "SEC-CONFIG-001");
        // Results are ordered by severity; the first finding must be HIGH (spring.security.user.password is a
        // literal, matching SEC-CONFIG-007 -- HardcodedSecretPropertyRule's severity, since NoOpPasswordEncoderRule
        // is the only remaining CRITICAL rule and no NoOp encoder is configured here).
        assertThat(report.results().get(0).severity()).isEqualTo("HIGH");
        // The severity histogram leads with CRITICAL so promoted rules sort and count correctly.
        assertThat(report.severityCounts())
                .extracting("severity")
                .containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
        assertThat(report.filterChains()).containsExactly("any request");
    }

    @Test
    void scanReportsNoViolationsForAHardenedChain() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of(
                        "SecurityContextHolderFilter",
                        "CsrfFilter",
                        "HeaderWriterFilter",
                        "BearerTokenAuthenticationFilter",
                        "AuthorizationFilter"),
                Boolean.FALSE,
                Boolean.FALSE,
                List.of(
                        "HstsHeaderWriter",
                        "XFrameOptionsHeaderWriter",
                        "XContentTypeOptionsHeaderWriter",
                        "ContentSecurityPolicyHeaderWriter",
                        "ReferrerPolicyHeaderWriter",
                        "PermissionsPolicyHeaderWriter",
                        "CrossOriginOpenerPolicyHeaderWriter",
                        "CrossOriginEmbedderPolicyHeaderWriter"));
        MockEnvironment environment = new MockEnvironment();
        SecurityContext context = new SecurityContext(
                List.of(chain),
                List.of(new PasswordEncoderModel(
                        "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder", null)),
                List.of(),
                false,
                List.of("com.example.AudienceValidatingJwtDecoder"),
                true,
                false,
                false,
                false,
                List.of(),
                false,
                false,
                List.of(),
                false,
                environment);
        SecurityScanner scanner = new SecurityScanner(context, CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.violationsFound()).isZero();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void scanDoesNotReportBootUiActuatorDefaultsAsHostApplicationFindings() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of(
                        "SecurityContextHolderFilter",
                        "CsrfFilter",
                        "HeaderWriterFilter",
                        "BearerTokenAuthenticationFilter",
                        "AuthorizationFilter"),
                Boolean.FALSE,
                Boolean.FALSE,
                List.of(
                        "HstsHeaderWriter",
                        "XFrameOptionsHeaderWriter",
                        "XContentTypeOptionsHeaderWriter",
                        "ContentSecurityPolicyHeaderWriter",
                        "ReferrerPolicyHeaderWriter",
                        "PermissionsPolicyHeaderWriter",
                        "CrossOriginOpenerPolicyHeaderWriter",
                        "CrossOriginEmbedderPolicyHeaderWriter"));
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addLast(bootUiActuatorDefaultsInDefaultPropertiesSource());
        ConfigurationPropertySources.attach(environment);
        SecurityContext context = new SecurityContext(
                List.of(chain),
                List.of(new PasswordEncoderModel(
                        "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder", null)),
                List.of(),
                false,
                List.of("com.example.AudienceValidatingJwtDecoder"),
                true,
                false,
                false,
                false,
                List.of(),
                false,
                false,
                List.of(),
                false,
                environment);
        SecurityScanner scanner = new SecurityScanner(context, CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.violationsFound()).isZero();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void scanDoesNotInventActualOperationsFromHostSelectionProperties() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of(
                        "SecurityContextHolderFilter",
                        "CsrfFilter",
                        "HeaderWriterFilter",
                        "BearerTokenAuthenticationFilter",
                        "AuthorizationFilter"),
                Boolean.FALSE,
                Boolean.FALSE,
                List.of(
                        "HstsHeaderWriter",
                        "XFrameOptionsHeaderWriter",
                        "XContentTypeOptionsHeaderWriter",
                        "ContentSecurityPolicyHeaderWriter",
                        "ReferrerPolicyHeaderWriter",
                        "PermissionsPolicyHeaderWriter",
                        "CrossOriginOpenerPolicyHeaderWriter",
                        "CrossOriginEmbedderPolicyHeaderWriter"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "env,beans")
                .withProperty("management.endpoint.health.show-details", "always");
        environment.getPropertySources().addLast(bootUiActuatorDefaultsInDefaultPropertiesSource());
        ConfigurationPropertySources.attach(environment);
        SecurityContext context = new SecurityContext(
                List.of(chain),
                List.of(new PasswordEncoderModel(
                        "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder", null)),
                List.of(),
                false,
                List.of("com.example.AudienceValidatingJwtDecoder"),
                true,
                false,
                false,
                false,
                List.of(),
                false,
                false,
                List.of(),
                false,
                environment);
        SecurityScanner scanner = new SecurityScanner(context, CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-ACT-002", "SEC-ACT-003", "SEC-ACT-006");
    }

    @Test
    void scanKeepsUnknownActualInventoryWithHostDefaultPropertiesExposure() {
        MockEnvironment environment = resourceServerEnvironment();
        environment
                .getPropertySources()
                .addLast(new MapPropertySource(
                        "defaultProperties",
                        new LinkedHashMap<>(Map.of("management.endpoints.web.exposure.include", "env,beans"))));
        ConfigurationPropertySources.attach(environment);
        SecurityReport report = new SecurityScanner(hardenedContext(List.of(), environment), CLOCK).scan();

        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-ACT-002", "SEC-ACT-003", "SEC-ACT-006");
    }

    @Test
    void scanReportsNoActuatorFindingsWhenExcludeFullyHardensAWildcardInclude() {
        // include=* alongside an exclude naming every sensitive endpoint is Spring Boot's own
        // documented hardening pattern (expose everything except an explicit deny-list); none of
        // SEC-ACT-001/002/003/006 should fire once the effective exposed set is empty.
        MockEnvironment environment = resourceServerEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "*")
                .withProperty(
                        "management.endpoints.web.exposure.exclude",
                        "env,beans,configprops,heapdump,threaddump,shutdown,loggers,mappings");
        SecurityReport report = new SecurityScanner(hardenedContext(List.of(), environment), CLOCK).scan();

        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-ACT-001", "SEC-ACT-002", "SEC-ACT-003", "SEC-ACT-006");
    }

    @Test
    void scanReturnsStableDisabledReportWhenNoFilterChainProxyIsAvailable() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        SecurityScanner scanner = new SecurityScanner(
                beanFactory.getBeanProvider(FilterChainProxy.class),
                beanFactory.getBeanProvider(ListableBeanFactory.class),
                new MockEnvironment(),
                CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("DISABLED");
        assertThat(report.results()).isEmpty();
        assertThat(report.filterChainsAnalyzed()).isZero();
    }

    @Test
    void blanketPermitAllRequiresEveryBoundedPathProbeToBeGranted() {
        AuthorizationManager<HttpServletRequest> publicResourcesOnly =
                RequestMatcherDelegatingAuthorizationManager.builder()
                        .requestMatchers(
                                PathPatternRequestMatcher.pathPattern("/"),
                                PathPatternRequestMatcher.pathPattern("/login"),
                                PathPatternRequestMatcher.pathPattern("/css/**"))
                        .permitAll()
                        .anyRequest()
                        .authenticated()
                        .build();

        SecurityReport report = scannerFor(filterChainProxy(publicResourcesOnly), new DefaultListableBeanFactory())
                .scan();

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-AUTHZ-002");
    }

    @Test
    void blanketPermitAllRequiresEveryBoundedHttpMethodProbeToBeGranted() {
        AuthorizationManager<HttpServletRequest> getOnly = RequestMatcherDelegatingAuthorizationManager.builder()
                .requestMatchers(PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/**"))
                .permitAll()
                .anyRequest()
                .authenticated()
                .build();

        SecurityReport report = scannerFor(filterChainProxy(getOnly), new DefaultListableBeanFactory())
                .scan();

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-AUTHZ-002");
    }

    @Test
    void blanketPermitAllFiresWhenEveryBoundedProbeIsGranted() {
        AuthorizationManager<HttpServletRequest> permitAll = RequestMatcherDelegatingAuthorizationManager.builder()
                .anyRequest()
                .permitAll()
                .build();

        SecurityReport report = scannerFor(filterChainProxy(permitAll), new DefaultListableBeanFactory())
                .scan();

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-AUTHZ-002");
    }

    @Test
    void observationAuthorizationManagerIsUnwrappedWithoutEmittingSyntheticObservations() {
        AtomicInteger observationsStarted = new AtomicInteger();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStart(Observation.Context context) {
                observationsStarted.incrementAndGet();
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        AuthorizationManager<RequestAuthorizationContext> permitAll = SingleResultAuthorizationManager.permitAll();
        RequestMatcherDelegatingAuthorizationManager delegate = RequestMatcherDelegatingAuthorizationManager.builder()
                .add(PathPatternRequestMatcher.pathPattern("/**"), permitAll)
                .add(PathPatternRequestMatcher.pathPattern("/admin/**"), permitAll)
                .build();
        AuthorizationManager<HttpServletRequest> observed =
                new ObservationAuthorizationManager<>(observationRegistry, delegate);

        SecurityReport report = scannerFor(filterChainProxy(observed), new DefaultListableBeanFactory())
                .scan();

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-AUTHZ-005");
        assertThat(observationsStarted).hasValue(0);
    }

    @Test
    void debugFilterKeepsTheAdvisorAvailableAndProducesTheDebugFinding() {
        AuthorizationManager<HttpServletRequest> denyAll =
                (authentication, request) -> new AuthorizationDecision(false);
        FilterChainProxy proxy = filterChainProxy(denyAll);
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("springSecurityFilterChain", new DebugFilter(proxy));

        SecurityReport report = scannerFor(null, beanFactory).scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.filterChainsAnalyzed()).isEqualTo(1);
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-CONFIG-001");
    }

    @Test
    void nativeDefaultFirewallIsNotWeakenedByAnUnusedFirewallBean() {
        var proxy = filterChainProxy(SingleResultAuthorizationManager.permitAll());
        var unused = new StrictHttpFirewall();
        unused.setAllowUrlEncodedSlash(true);
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("unusedFirewall", unused);

        SecurityReport report = scannerFor(proxy, factory).scan();

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-CONFIG-008");
        assertThat(report.evidence().limitations()).noneMatch(value -> value.startsWith("SEC-CONFIG-008:"));
    }

    @Test
    void nativeRelaxedActiveFirewallProducesAHighFinding() {
        var firewall = new StrictHttpFirewall();
        firewall.setAllowUrlEncodedSlash(true);
        var proxy = filterChainProxy(SingleResultAuthorizationManager.permitAll());
        proxy.setFirewall(firewall);

        SecurityReport report =
                scannerFor(proxy, new DefaultListableBeanFactory()).scan();

        assertThat(report.results())
                .filteredOn(result -> result.id().equals("SEC-CONFIG-008"))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
                    assertThat(result.severity()).isEqualTo("HIGH");
                    assertThat(result.violationCount()).isOne();
                });
        assertThat(report.evidence().limitations()).noneMatch(value -> value.startsWith("SEC-CONFIG-008:"));
    }

    @Test
    void customAndSubclassFirewallsStayUnknownWithoutCallbacks() {
        HttpFirewall custom = new HttpFirewall() {
            @Override
            public FirewalledRequest getFirewalledRequest(HttpServletRequest request) {
                throw new AssertionError("Must not invoke custom firewall requests");
            }

            @Override
            public HttpServletResponse getFirewalledResponse(HttpServletResponse response) {
                throw new AssertionError("Must not invoke custom firewall responses");
            }
        };
        var subclass = new StrictHttpFirewall() {
            @Override
            public Set<String> getEncodedUrlBlocklist() {
                throw new AssertionError("Must not invoke a custom firewall getter");
            }

            @Override
            public FirewalledRequest getFirewalledRequest(HttpServletRequest request) {
                throw new AssertionError("Must not invoke subclass firewall requests");
            }

            @Override
            public HttpServletResponse getFirewalledResponse(HttpServletResponse response) {
                throw new AssertionError("Must not invoke subclass firewall responses");
            }
        };
        for (HttpFirewall firewall : List.of(custom, subclass)) {
            assertUnknownFirewallPreservesIndependentFindings(firewall);
        }
    }

    @Test
    void unreadableNativeFirewallBlocklistStaysUnknown() {
        var firewall = new StrictHttpFirewall();
        ReflectionTestUtils.setField(firewall, "encodedUrlBlocklist", null);

        assertUnknownFirewallPreservesIndependentFindings(firewall);
    }

    @Test
    void customNativeFirewallBlocklistStaysUnknownWithoutCollectionCallbacks() {
        var firewall = new StrictHttpFirewall();
        ReflectionTestUtils.setField(firewall, "encodedUrlBlocklist", new HashSet<String>() {
            @Override
            public boolean contains(Object token) {
                throw new AssertionError("Must not invoke custom blocklist callbacks");
            }
        });

        assertUnknownFirewallPreservesIndependentFindings(firewall);
    }

    private static void assertUnknownFirewallPreservesIndependentFindings(HttpFirewall firewall) {
        var proxy = filterChainProxy(SingleResultAuthorizationManager.permitAll());
        SecurityScanner scanner = scannerFor(proxy, new DefaultListableBeanFactory());
        SecurityReport baseline = scanner.scan();
        proxy.setFirewall(firewall);

        SecurityReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.evidence().limitations())
                .anyMatch(value -> value.startsWith("SEC-CONFIG-008:") && value.contains("firewall"));
        assertThat(report.results()).isEqualTo(baseline.results());
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-AUTHZ-002");
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-CONFIG-008");
    }

    @Test
    @SuppressWarnings("removal")
    void mvcHandlerMappingIntrospectorIsNotTreatedAsApplicationCorsConfiguration() {
        AuthorizationManager<HttpServletRequest> denyAll =
                (authentication, request) -> new AuthorizationDecision(false);
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("mvcHandlerMappingIntrospector", new HandlerMappingIntrospector());

        SecurityReport report =
                scannerFor(filterChainProxy(denyAll), beanFactory).scan();

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-CORS-003");
    }

    /**
     * A {@code sessionCreationPolicy(STATELESS)} chain built by the real {@code HttpSecurity} DSL.
     * Spring Security still installs a {@code SessionManagementFilter} for it, so this pins that the
     * advisor reads statelessness from the chain's {@code SecurityContextRepository} instead
     * (issue #921). SEC-SESSION-005 stands in for the whole family of session-based rules: it fires
     * only when some chain is considered stateful, and CSRF stays enabled so the fixture does not
     * have to configure a knowingly insecure chain.
     */
    @Test
    void statelessSessionPolicyChainIsNotTreatedAsSessionBased() throws Exception {
        SecurityFilterChain chain = securityFilterChain(http ->
                http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)));

        SecurityReport report = scannerFor(new FilterChainProxy(chain), new DefaultListableBeanFactory())
                .scan();

        assertThat(chain.getFilters())
                .extracting(filter -> filter.getClass().getSimpleName())
                .as("the filter the previous heuristic keyed on is still installed")
                .contains("SessionManagementFilter");
        assertThat(report.filterChainsAnalyzed()).isEqualTo(1);
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-SESSION-005");
    }

    /** An explicitly selected session policy does not revive the retired timeout-default rule. */
    @Test
    void sessionBackedPolicyChainIsTreatedAsSessionBased() throws Exception {
        SecurityFilterChain chain = securityFilterChain(http ->
                http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)));

        SecurityReport report = scannerFor(new FilterChainProxy(chain), new DefaultListableBeanFactory())
                .scan();

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-SESSION-005");
    }

    /**
     * Builds only the requested DSL fragments, not Boot's normal defaults. Native default coverage
     * lives in SecurityAuditAccuracyTests.
     */
    private static SecurityFilterChain securityFilterChain(Customizer<HttpSecurity> customizer) throws Exception {
        GenericApplicationContext applicationContext = new GenericApplicationContext();
        applicationContext.refresh();
        ObjectPostProcessor<Object> objectPostProcessor = new ObjectPostProcessor<>() {
            @Override
            public <O> O postProcess(O object) {
                return object;
            }
        };
        HttpSecurity http = new HttpSecurity(
                objectPostProcessor,
                new AuthenticationManagerBuilder(objectPostProcessor),
                Map.of(ApplicationContext.class, applicationContext));
        customizer.customize(http);
        return http.httpBasic(Customizer.withDefaults())
                .authenticationManager(authentication -> authentication)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .build();
    }

    /**
     * Issue #922: the actuator is protected inside a single {@code anyRequest} chain, the shape
     * Spring Boot's reference documents. The chain matcher renders as "any request" and never
     * mentions {@code /actuator}, so SEC-ACT-003 must read the chain's authorization rules instead.
     */
    @Test
    void actuatorProtectedInsideTheAnyRequestChainIsNotReportedAsUnprotected() throws Exception {
        SecurityFilterChain chain = actuatorSecurityFilterChain(authorize -> authorize
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                .permitAll()
                .requestMatchers("/actuator/**")
                .hasRole("ADMIN")
                .anyRequest()
                .authenticated());

        SecurityReport report = scannerFor(
                        new FilterChainProxy(chain), new DefaultListableBeanFactory(), actuatorEnvironment())
                .scan();

        assertThat(report.filterChainsAnalyzed()).isEqualTo(1);
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-ACT-003");
        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .as("configuration selection without actual operation inventory remains unknown")
                .doesNotContain("SEC-ACT-006");
    }

    /** Endpoint selection is not actual operation metadata. */
    @Test
    void actuatorPermitAllWithoutActualOperationsRemainsUnknown() throws Exception {
        SecurityFilterChain chain =
                actuatorSecurityFilterChain(authorize -> authorize.anyRequest().permitAll());

        SecurityReport report = scannerFor(
                        new FilterChainProxy(chain), new DefaultListableBeanFactory(), actuatorEnvironment())
                .scan();

        assertThat(report.results())
                .filteredOn(result -> result.id().equals("SEC-ACT-003"))
                .isEmpty();
    }

    /** A dedicated actuator chain that requires a role is protection, and stays silent. */
    @Test
    void dedicatedActuatorChainRequiringARoleIsNotReported() throws Exception {
        SecurityFilterChain actuator = actuatorSecurityFilterChain(
                http -> http.securityMatcher(
                        PathPatternRequestMatcher.withDefaults().matcher("/actuator/**")),
                authorize -> authorize.anyRequest().hasRole("ADMIN"));
        SecurityFilterChain application =
                actuatorSecurityFilterChain(authorize -> authorize.anyRequest().permitAll());

        SecurityReport report = scannerFor(
                        new FilterChainProxy(List.of(actuator, application)),
                        new DefaultListableBeanFactory(),
                        actuatorEnvironment())
                .scan();

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-ACT-003");
    }

    private static MockEnvironment actuatorEnvironment() {
        return new MockEnvironment().withProperty("management.endpoints.web.exposure.include", "health,prometheus");
    }

    private static SecurityFilterChain actuatorSecurityFilterChain(
            Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry>
                    authorize)
            throws Exception {
        return actuatorSecurityFilterChain(http -> {}, authorize);
    }

    /**
     * Builds a real chain through the {@code HttpSecurity} DSL with the given
     * {@code authorizeHttpRequests} rules, so the actuator assertions run against the authorization
     * manager Spring Security actually assembles rather than a hand-written model.
     */
    private static SecurityFilterChain actuatorSecurityFilterChain(
            Customizer<HttpSecurity> customizer,
            Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry>
                    authorize)
            throws Exception {
        GenericApplicationContext applicationContext = new GenericApplicationContext();
        applicationContext.refresh();
        ObjectPostProcessor<Object> objectPostProcessor = new ObjectPostProcessor<>() {
            @Override
            public <O> O postProcess(O object) {
                return object;
            }
        };
        HttpSecurity http = new HttpSecurity(
                objectPostProcessor,
                new AuthenticationManagerBuilder(objectPostProcessor),
                Map.of(ApplicationContext.class, applicationContext));
        customizer.customize(http);
        return http.httpBasic(Customizer.withDefaults())
                .authenticationManager(authentication -> authentication)
                .authorizeHttpRequests(authorize)
                .build();
    }

    @Test
    void unusedApplicationCorsBeanDoesNotProveMissingIntegration() {
        AuthorizationManager<HttpServletRequest> denyAll =
                (authentication, request) -> new AuthorizationDecision(false);
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        CorsConfigurationSource applicationCorsSource = request -> null;
        beanFactory.registerSingleton("corsConfigurationSource", applicationCorsSource);

        SecurityReport report =
                scannerFor(filterChainProxy(denyAll), beanFactory).scan();

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-CORS-003");
    }

    @Test
    void initialReportDoesNotInspectConfigurationBeforeExplicitScan() {
        FilterChainModel chain =
                new FilterChainModel(0, "any request", List.of("AuthorizationFilter"), null, null, List.of());
        SecurityContext context = new SecurityContext(
                List.of(chain),
                List.of(),
                List.of(),
                false,
                List.of(),
                false,
                false,
                false,
                false,
                List.of(),
                false,
                false,
                List.of(),
                false,
                new MockEnvironment());
        SecurityScanner scanner = new SecurityScanner(context, CLOCK);

        SecurityReport report = scanner.initialReport();

        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void registryExposesTheFullActiveRuleset() {
        assertThat(SecurityRuleRegistry.activeRules()).hasSize(RULE_COUNT);
        assertThat(SecurityRuleRegistry.activeRules())
                .extracting(rule -> rule.definition().id())
                .doesNotHaveDuplicates();
    }

    @Test
    void flagsBcryptEncoderConfiguredBelowRecommendedStrength() {
        SecurityContext context =
                hardenedContext(List.of(new PasswordEncoderModel(BCRYPT, 4)), resourceServerEnvironment());

        SecurityReport report = new SecurityScanner(context, CLOCK).scan();

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-AUTH-006");
    }

    @Test
    void doesNotFlagBcryptEncoderUsingDefaultOrAdequateStrength() {
        SecurityContext context = hardenedContext(
                List.of(new PasswordEncoderModel(BCRYPT, -1), new PasswordEncoderModel(BCRYPT, 12)),
                resourceServerEnvironment());

        SecurityReport report = new SecurityScanner(context, CLOCK).scan();

        assertThat(report.violationsFound()).isZero();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void retiredServletRulesAreNotEvaluatedAndDismissalsStayCompatible() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of(
                        "HeaderWriterFilter",
                        "SecurityContextHolderFilter",
                        "UsernamePasswordAuthenticationFilter",
                        "SessionManagementFilter",
                        "AuthorizationFilter"),
                Boolean.FALSE,
                Boolean.FALSE,
                List.of("XContentTypeOptionsHeaderWriter"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoint.health.show-details", "always")
                .withProperty("management.endpoint.health.show-components", "always")
                .withProperty("spring.web.error.include-stacktrace", "always")
                .withProperty("spring.web.error.include-message", "always");

        SecurityScanner scanner = new SecurityScanner(contextWith(chain, environment), CLOCK);
        SecurityReport report = scanner.scan();

        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-SESSION-007", "SEC-HEAD-010", "SEC-ACT-004", "SEC-CONFIG-005")
                .doesNotContainAnyElementsOf(SecurityScanner.RETIRED_RULE_IDS);

        SecurityReport dismissed = scanner.applyDismissals(report, SecurityScanner.RETIRED_RULE_IDS);
        assertThat(dismissed.results()).isEqualTo(report.results());
        assertThat(dismissed.violationsFound()).isEqualTo(report.violationsFound());
        assertThat(dismissed.severityCounts()).isEqualTo(report.severityCounts());
        assertThat(dismissed.scan().status()).isEqualTo(report.scan().status());
    }

    @Test
    void flagsMissingHttpsEnforcementInProduction() {
        MockEnvironment environment = resourceServerEnvironment();
        environment.setActiveProfiles("prod");
        SecurityContext context = hardenedContext(List.of(), environment);

        SecurityReport report = new SecurityScanner(context, CLOCK).scan();

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-CONFIG-006");
    }

    @Test
    void forwardedHeadersDoNotProveHttpsEnforcement() {
        MockEnvironment environment = resourceServerEnvironment();
        environment.setActiveProfiles("prod");
        environment.withProperty("server.forward-headers-strategy", "framework");
        SecurityContext context = hardenedContext(List.of(), environment);

        SecurityReport report = new SecurityScanner(context, CLOCK).scan();

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-CONFIG-006");
    }

    private static final String BCRYPT = "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder";

    private static FilterChainModel hardenedChain() {
        return new FilterChainModel(
                0,
                "any request",
                List.of(
                        "SecurityContextHolderFilter",
                        "CsrfFilter",
                        "HeaderWriterFilter",
                        "BearerTokenAuthenticationFilter",
                        "AuthorizationFilter"),
                Boolean.FALSE,
                Boolean.FALSE,
                List.of(
                        "HstsHeaderWriter",
                        "XFrameOptionsHeaderWriter",
                        "XContentTypeOptionsHeaderWriter",
                        "ContentSecurityPolicyHeaderWriter",
                        "ReferrerPolicyHeaderWriter",
                        "PermissionsPolicyHeaderWriter",
                        "CrossOriginOpenerPolicyHeaderWriter",
                        "CrossOriginEmbedderPolicyHeaderWriter"));
    }

    private static MockEnvironment resourceServerEnvironment() {
        return new MockEnvironment();
    }

    private static FilterChainProxy filterChainProxy(AuthorizationManager<HttpServletRequest> authorizationManager) {
        return new FilterChainProxy(new DefaultSecurityFilterChain(
                AnyRequestMatcher.INSTANCE,
                new UsernamePasswordAuthenticationFilter(),
                new AuthorizationFilter(authorizationManager)));
    }

    @SuppressWarnings("unchecked")
    private static SecurityScanner scannerFor(FilterChainProxy proxy, ListableBeanFactory beanFactory) {
        return scannerFor(proxy, beanFactory, new MockEnvironment());
    }

    private static SecurityScanner scannerFor(
            FilterChainProxy proxy, ListableBeanFactory beanFactory, MockEnvironment environment) {
        return scannerFor(proxy, beanFactory, environment, null);
    }

    private static SecurityScanner scannerFor(
            FilterChainProxy proxy, ListableBeanFactory beanFactory, MockEnvironment environment, Path projectRoot) {
        if (beanFactory == null) beanFactory = new DefaultListableBeanFactory();
        if (proxy != null
                && beanFactory instanceof DefaultListableBeanFactory configurable
                && !configurable.containsSingleton("springSecurityFilterChain")) {
            configurable.registerSingleton("springSecurityFilterChain", proxy);
        }
        ObjectProvider<FilterChainProxy> filterChains = mock(ObjectProvider.class);
        ObjectProvider<ListableBeanFactory> beanFactories = mock(ObjectProvider.class);
        when(filterChains.getIfAvailable()).thenReturn(proxy);
        when(beanFactories.getIfAvailable()).thenReturn(beanFactory);
        return new SecurityScanner(filterChains, beanFactories, environment, CLOCK, () -> projectRoot);
    }

    private static SecurityContext hardenedContext(List<PasswordEncoderModel> encoders, MockEnvironment environment) {
        return new SecurityContext(
                List.of(hardenedChain()),
                encoders,
                List.of(),
                false,
                List.of("com.example.AudienceValidatingJwtDecoder"),
                true,
                false,
                false,
                false,
                List.of(),
                false,
                false,
                List.of(),
                false,
                environment);
    }

    private static SecurityContext contextWith(FilterChainModel chain, MockEnvironment environment) {
        return new SecurityContext(
                List.of(chain),
                List.of(),
                List.of(),
                false,
                List.of(),
                true,
                false,
                false,
                false,
                List.of(),
                false,
                false,
                List.of(),
                false,
                environment);
    }

    @Test
    void noApplicableTargetsAndUnavailableDiscoveryNeverCompleteChecks() {
        SecurityReport empty = new SecurityScanner(emptyContext(), CLOCK).scan();
        assertThat(empty.evidence().usable()).isFalse();
        SecurityReport missing = scannerFor(null, null).scan();
        assertThat(missing.scan().status()).isEqualTo("DISABLED");
        assertThat(missing.evidence().usable()).isFalse();
        assertThat(missing.evidence().coverageComplete()).isFalse();
    }

    @Test
    void missingRequiredOperationInventoryMakesScanStatusAndEvidencePartial() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("AuthorizationFilter"),
                false,
                false,
                List.of(),
                null,
                null,
                null,
                null,
                false,
                null,
                true,
                null,
                null);
        SecurityReport report = new SecurityScanner(contextWith(chain, new MockEnvironment()), CLOCK).scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results()).isEmpty();
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.evidence().limitations()).anyMatch(value -> value.contains("SEC-ACT-"));
    }

    @Test
    void ruleEvaluationWrapsRuntimeExceptionAsErrorResult() {
        SecurityContext context = emptyContext();
        SecurityRuleResultDto result = new ThrowingRule().evaluate(context);

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.ERROR);
        assertThat(context.evidence().evaluation().evidence(List.of()).usable()).isFalse();
        assertThat(context.evidence().evaluation().evidence(List.of()).coverageComplete())
                .isFalse();
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations()).hasSize(1);
        assertThat(result.sampleViolations().get(0))
                .contains("Rule could not be evaluated:")
                .doesNotContain("boom");
    }

    @Test
    void ruleEvaluationWrapsLinkageErrorAsErrorResult() {
        SecurityRuleResultDto result = new LinkageErrorRule().evaluate(emptyContext());

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.ERROR);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations().get(0)).doesNotContain("missing");
    }

    @Test
    void skippedRuleSurfacesSkippedStatusAndReason() {
        SecurityRuleResultDto result = new SkippingRule().evaluate(emptyContext());

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.SKIPPED);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations()).containsExactly("Not applicable in this context.");
    }

    @Test
    void everyActiveRuleRoutesThroughTheFailClosedBase() {
        assertThat(SecurityRuleRegistry.activeRules())
                .allSatisfy(rule -> assertThat(rule).isInstanceOf(AbstractSecurityRule.class));
    }

    private static SecurityContext emptyContext() {
        return new SecurityContext(
                List.of(),
                List.of(),
                List.of(),
                false,
                List.of(),
                false,
                false,
                false,
                false,
                List.of(),
                false,
                false,
                List.of(),
                false,
                new MockEnvironment());
    }

    private static SecurityRuleDefinition testRuleDefinition() {
        return new SecurityRuleDefinition(
                "SEC-TEST-001",
                "Deliberately failing test rule",
                SecurityCategory.CONFIGURATION,
                "LOW",
                "Test-only rule used to exercise the fail-closed base.",
                "No action required.",
                null);
    }

    private static final class ThrowingRule extends AbstractSecurityRule {

        ThrowingRule() {
            super(testRuleDefinition());
        }

        @Override
        SecurityRuleResultDto evaluateRule(SecurityContext context) {
            throw new IllegalStateException("boom");
        }
    }

    private static final class LinkageErrorRule extends AbstractSecurityRule {

        LinkageErrorRule() {
            super(testRuleDefinition());
        }

        @Override
        SecurityRuleResultDto evaluateRule(SecurityContext context) {
            throw new NoClassDefFoundError("missing");
        }
    }

    private static final class SkippingRule extends AbstractSecurityRule {

        SkippingRule() {
            super(testRuleDefinition());
        }

        @Override
        SecurityRuleResultDto evaluateRule(SecurityContext context) {
            return skipped("Not applicable in this context.");
        }
    }

    private record DescribedRequestMatcher(String description, boolean matches) implements RequestMatcher {

        @Override
        public boolean matches(HttpServletRequest request) {
            return matches;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    private static MapPropertySource bootUiActuatorDefaultsInDefaultPropertiesSource() {
        return new MapPropertySource(
                "defaultProperties",
                Map.of(
                        "management.endpoints.web.exposure.include",
                        "health,info,beans,conditions,configprops,env,loggers,mappings,metrics,startup,scheduledtasks",
                        "management.endpoint.health.show-details",
                        "always"));
    }

    @Test
    void analysisErrorsKeepsOnlyErrorResultsSortedById() {
        SecurityRuleResultDto pass = result("SEC-T-001", SecurityRuleSupport.PASS);
        SecurityRuleResultDto violation = result("SEC-T-002", SecurityRuleSupport.VIOLATION);
        SecurityRuleResultDto errorB = result("SEC-T-004", SecurityRuleSupport.ERROR);
        SecurityRuleResultDto errorA = result("SEC-T-003", SecurityRuleSupport.ERROR);
        SecurityRuleResultDto skipped = result("SEC-T-005", SecurityRuleSupport.SKIPPED);

        List<SecurityRuleResultDto> errors =
                SecurityScanner.analysisErrors(List.of(pass, violation, errorB, errorA, skipped));

        assertThat(errors).extracting(SecurityRuleResultDto::id).containsExactly("SEC-T-003", "SEC-T-004");
        assertThat(errors).extracting(SecurityRuleResultDto::status).containsOnly(SecurityRuleSupport.ERROR);
    }

    private static SecurityRuleResultDto result(String id, String status) {
        return new SecurityRuleResultDto(
                id,
                "name",
                "Category",
                "HIGH",
                "description",
                status,
                0,
                List.of("detail"),
                "recommendation",
                "https://example.com");
    }
}
