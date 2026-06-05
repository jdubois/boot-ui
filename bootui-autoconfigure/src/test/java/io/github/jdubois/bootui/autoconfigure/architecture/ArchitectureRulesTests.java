package io.github.jdubois.bootui.autoconfigure.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopContext;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

class ArchitectureRulesTests {

    @Test
    void servicesShouldNotDependOnControllersFlagsWebLayerDependencies() {
        ArchitectureRuleResultDto result = evaluate(
                new ServicesShouldNotDependOnControllersRule(),
                ServiceDependingOnController.class,
                ExampleController.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-006");
        assertThat(result.violationCount()).isPositive();
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample)
                        .contains("ServiceDependingOnController")
                        .contains("ExampleController"));
    }

    @Test
    void servicesShouldNotDependOnControllersPassesWhenServiceHasNoControllerDependency() {
        ArchitectureRuleResultDto result = evaluate(
                new ServicesShouldNotDependOnControllersRule(),
                ServiceWithoutControllerDependency.class,
                ExampleController.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void exceptionsShouldBeNamedExceptionFlagsExceptionTypesWithoutSuffix() {
        ArchitectureRuleResultDto result = evaluate(new ExceptionsShouldBeNamedExceptionRule(), BadFailure.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-010");
        assertThat(result.violationCount()).isPositive();
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("BadFailure"));
    }

    @Test
    void exceptionsShouldBeNamedExceptionPassesWhenSuffixIsPresent() {
        ArchitectureRuleResultDto result =
                evaluate(new ExceptionsShouldBeNamedExceptionRule(), GoodFailureException.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void interfacesShouldNotHaveInterfaceSuffixFlagsInterfaceSuffix() {
        ArchitectureRuleResultDto result =
                evaluate(new InterfacesShouldNotHaveInterfaceSuffixRule(), PaymentInterface.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-011");
        assertThat(result.violationCount()).isPositive();
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("PaymentInterface"));
    }

    @Test
    void interfacesShouldNotHaveInterfaceSuffixPassesWhenInterfaceHasRoleName() {
        ArchitectureRuleResultDto result =
                evaluate(new InterfacesShouldNotHaveInterfaceSuffixRule(), PaymentPort.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void loggersShouldBePrivateStaticFinalFlagsMutableOrVisibleLoggers() {
        ArchitectureRuleResultDto result =
                evaluate(new LoggersShouldBePrivateStaticFinalRule(), VisibleLoggerComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-012");
        assertThat(result.violationCount()).isPositive();
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("LOGGER"));
    }

    @Test
    void loggersShouldBePrivateStaticFinalPassesForPrivateStaticFinalLoggers() {
        ArchitectureRuleResultDto result =
                evaluate(new LoggersShouldBePrivateStaticFinalRule(), WellFormedLoggerComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void noTestFrameworkDependenciesFlagsMainCodeUsingTestApis() {
        ArchitectureRuleResultDto result = evaluate(new NoTestFrameworkDependenciesRule(), TestFrameworkUser.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-013");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("org.junit"));
    }

    @Test
    void repositoriesShouldNotDependOnServicesFlagsPersistenceDependingOnBusinessLayer() {
        ArchitectureRuleResultDto result = evaluate(
                new RepositoriesShouldNotDependOnServicesRule(),
                RepositoryDependingOnService.class,
                ExampleService.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-007");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample)
                        .contains("RepositoryDependingOnService")
                        .contains("ExampleService"));
    }

    @Test
    void servicesAndRepositoriesShouldNotDependOnServletTypesFlagsHttpRequestDependencies() {
        ArchitectureRuleResultDto result = evaluate(
                new ServicesAndRepositoriesShouldNotDependOnServletTypesRule(), ServiceUsingServletRequest.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-008");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("HttpServletRequest"));
    }

    @Test
    void transactionalAnnotationsShouldNotBeDeclaredOnInterfacesFlagsInterfaceAnnotations() {
        ArchitectureRuleResultDto result = evaluate(
                new TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule(),
                TransactionalInterface.class,
                TransactionalMethodInterface.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-009");
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("TransactionalInterface"));
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("TransactionalMethodInterface"));
    }

    @Test
    void proxiedMethodsShouldNotBePrivateOrStaticFlagsUnproxyableMethods() {
        ArchitectureRuleResultDto result =
                evaluate(new ProxiedMethodsShouldNotBePrivateOrStaticRule(), BadProxyAnnotationComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-010");
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("private"));
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("static"));
    }

    @Test
    void asyncMethodsShouldHaveSupportedSignaturesFlagsUnsupportedReturnTypes() {
        ArchitectureRuleResultDto result =
                evaluate(new AsyncMethodsShouldHaveSupportedSignaturesRule(), BadAsyncComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-011");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("java.lang.String"));
    }

    @Test
    void asyncMethodsShouldHaveSupportedSignaturesPassesForFutureReturnTypes() {
        ArchitectureRuleResultDto result =
                evaluate(new AsyncMethodsShouldHaveSupportedSignaturesRule(), GoodAsyncComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void scheduledMethodsShouldHaveSupportedSignaturesFlagsArgumentsAndIgnoredReturnValues() {
        ArchitectureRuleResultDto result =
                evaluate(new ScheduledMethodsShouldHaveSupportedSignaturesRule(), BadScheduledComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-012");
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("declares parameters"));
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("returns java.lang.String"));
    }

    @Test
    void scheduledMethodsShouldHaveSupportedSignaturesPassesForVoidMethodsWithoutArguments() {
        ArchitectureRuleResultDto result =
                evaluate(new ScheduledMethodsShouldHaveSupportedSignaturesRule(), GoodScheduledComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void asyncShouldNotBeUsedInConfigurationClassesFlagsConfigurationUsage() {
        ArchitectureRuleResultDto result =
                evaluate(new AsyncShouldNotBeUsedInConfigurationClassesRule(), AsyncConfiguration.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-013");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("AsyncConfiguration"));
    }

    @Test
    void noAopContextCurrentProxyFlagsDirectProxyLookup() {
        ArchitectureRuleResultDto result =
                evaluate(new NoAopContextCurrentProxyRule(), AopContextCurrentProxyUser.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-014");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("currentProxy"));
    }

    @Test
    void noPublicMutableStaticFieldsFlagsPublicNonFinalStaticFields() {
        ArchitectureRuleResultDto result =
                evaluate(new NoPublicMutableStaticFieldsRule(), PublicMutableStaticFieldHolder.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-014");
        assertThat(result.violationCount()).isPositive();
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("counter"));
    }

    @Test
    void noPublicMutableStaticFieldsPassesForFinalOrNonPublicStaticFields() {
        ArchitectureRuleResultDto result = evaluate(new NoPublicMutableStaticFieldsRule(), SafeStaticFieldHolder.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void utilityClassesShouldBeFinalWithPrivateConstructorFlagsInstantiableOrSubclassableUtilities() {
        ArchitectureRuleResultDto result = evaluate(
                new UtilityClassesShouldBeFinalWithPrivateConstructorRule(),
                NonFinalUtility.class,
                UtilityWithPublicConstructor.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-015");
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anySatisfy(
                        sample -> assertThat(sample).contains("NonFinalUtility").contains("not final"));
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample)
                        .contains("UtilityWithPublicConstructor")
                        .contains("non-private constructor"));
    }

    @Test
    void utilityClassesShouldBeFinalWithPrivateConstructorPassesForWellFormedUtilities() {
        ArchitectureRuleResultDto result =
                evaluate(new UtilityClassesShouldBeFinalWithPrivateConstructorRule(), WellFormedUtility.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void utilityClassesShouldBeFinalWithPrivateConstructorIgnoresClassesWithInstanceMembers() {
        ArchitectureRuleResultDto result =
                evaluate(new UtilityClassesShouldBeFinalWithPrivateConstructorRule(), NotAUtilityClass.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void utilityClassesShouldBeFinalWithPrivateConstructorIgnoresSyntheticStaticMembers() {
        ArchitectureRuleResultDto result =
                evaluate(new UtilityClassesShouldBeFinalWithPrivateConstructorRule(), ConstantsHolderWithLambda.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void configurationPropertiesShouldBeImmutableFlagsMutableFields() {
        ArchitectureRuleResultDto result =
                evaluate(new ConfigurationPropertiesShouldBeImmutableRule(), MutableConfigurationProperties.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-015");
        assertThat(result.violationCount()).isPositive();
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("name"));
    }

    @Test
    void configurationPropertiesShouldBeImmutablePassesForImmutableRecord() {
        ArchitectureRuleResultDto result =
                evaluate(new ConfigurationPropertiesShouldBeImmutableRule(), ImmutableConfigurationProperties.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void layeredArchitectureDirectionFlagsWebDependingDirectlyOnPersistence() {
        ArchitectureRuleResultDto result =
                evaluate(new LayeredArchitectureDirectionRule(), LayeredController.class, LayeredRepository.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-016");
        assertThat(result.violationCount()).isPositive();
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("LayeredRepository"));
    }

    @Test
    void layeredArchitectureDirectionPassesForCanonicalWebToServiceToRepositoryChain() {
        ArchitectureRuleResultDto result = evaluate(
                new LayeredArchitectureDirectionRule(),
                CompliantController.class,
                CompliantService.class,
                CompliantRepository.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    private static ArchitectureRuleResultDto evaluate(ArchitectureRule rule, Class<?>... classes) {
        JavaClasses importedClasses = new ClassFileImporter().importClasses(classes);
        return rule.evaluate(
                new ArchitectureContext(importedClasses, List.of(ArchitectureRulesTests.class.getPackageName())));
    }

    @RestController
    private static class ExampleController {}

    @Service
    private static class ExampleService {}

    @Service
    private static class ServiceDependingOnController {

        private final ExampleController controller;

        ServiceDependingOnController(ExampleController controller) {
            this.controller = controller;
        }
    }

    @Service
    private static class ServiceWithoutControllerDependency {

        private final String dependency = "safe";
    }

    private static class BadFailure extends RuntimeException {}

    private static class GoodFailureException extends RuntimeException {}

    private interface PaymentInterface {}

    private interface PaymentPort {}

    private static class VisibleLoggerComponent {

        static final Logger LOGGER = LoggerFactory.getLogger(VisibleLoggerComponent.class);
    }

    private static class WellFormedLoggerComponent {

        private static final Logger LOGGER = LoggerFactory.getLogger(WellFormedLoggerComponent.class);
    }

    private static class TestFrameworkUser {

        private final org.junit.jupiter.api.TestInfo testInfo = null;
    }

    @Repository
    private static class RepositoryDependingOnService {

        private final ExampleService service;

        RepositoryDependingOnService(ExampleService service) {
            this.service = service;
        }
    }

    @Service
    private static class ServiceUsingServletRequest {

        String userAgent(HttpServletRequest request) {
            return request.getHeader("User-Agent");
        }
    }

    @Transactional
    private interface TransactionalInterface {}

    private interface TransactionalMethodInterface {

        @Transactional
        void save();
    }

    private static class BadProxyAnnotationComponent {

        @Transactional
        private void privateTransactional() {}

        @Async
        static void staticAsync() {}
    }

    private static class BadAsyncComponent {

        @Async
        String unsupportedReturnType() {
            return "bad";
        }
    }

    private static class GoodAsyncComponent {

        @Async
        Future<String> supportedReturnType() {
            return CompletableFuture.completedFuture("ok");
        }
    }

    private static class BadScheduledComponent {

        @Scheduled(fixedDelay = 1000)
        void withArgument(String ignored) {}

        @Scheduled(fixedDelay = 1000)
        String ignoredReturnValue() {
            return "ignored";
        }
    }

    private static class GoodScheduledComponent {

        @Scheduled(fixedDelay = 1000)
        void run() {}
    }

    @Configuration
    private static class AsyncConfiguration {

        @Async
        void configureAsync() {}
    }

    private static class AopContextCurrentProxyUser {

        Object currentProxy() {
            return AopContext.currentProxy();
        }
    }

    private static class PublicMutableStaticFieldHolder {

        public static int counter = 0;
    }

    private static class SafeStaticFieldHolder {

        public static final int MAX = 10;

        private static int internal = 0;

        int read() {
            return internal;
        }
    }

    private static class NonFinalUtility {

        private NonFinalUtility() {}

        static int tripled(int value) {
            return value * 3;
        }
    }

    private static final class UtilityWithPublicConstructor {

        public UtilityWithPublicConstructor() {}

        static int doubled(int value) {
            return value * 2;
        }
    }

    private static final class WellFormedUtility {

        private WellFormedUtility() {}

        static int squared(int value) {
            return value * value;
        }
    }

    private static final class NotAUtilityClass {

        static int helper(int value) {
            return value;
        }

        int instanceMethod() {
            return 0;
        }
    }

    // Not final and has only a static constant, but a non-capturing lambda makes javac emit a synthetic static
    // method. The rule must ignore that synthetic method so this constants holder is not treated as a utility class.
    private static class ConstantsHolderWithLambda {

        static final Runnable ACTION = () -> {};
    }

    @ConfigurationProperties(prefix = "demo.mutable")
    private static class MutableConfigurationProperties {

        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @ConfigurationProperties(prefix = "demo.immutable")
    private record ImmutableConfigurationProperties(String name, int size) {}

    @RestController
    private static class LayeredController {

        LayeredController(LayeredRepository repository) {
            this.repository = repository;
        }

        private final LayeredRepository repository;
    }

    @Repository
    private static class LayeredRepository {}

    @RestController
    private static class CompliantController {

        CompliantController(CompliantService service) {
            this.service = service;
        }

        private final CompliantService service;
    }

    @Service
    private static class CompliantService {

        CompliantService(CompliantRepository repository) {
            this.repository = repository;
        }

        private final CompliantRepository repository;
    }

    @Repository
    private static class CompliantRepository {}
}
