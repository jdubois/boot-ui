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
}
