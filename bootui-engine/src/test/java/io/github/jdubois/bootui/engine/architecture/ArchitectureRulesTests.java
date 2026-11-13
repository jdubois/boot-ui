package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import io.github.jdubois.bootui.engine.architecture.cyclefixtures.alpha.AlphaComponent;
import io.github.jdubois.bootui.engine.architecture.cyclefixtures.beta.BetaComponent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import java.io.PrintWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.RepositoryDefinition;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;

class ArchitectureRulesTests {

    @TempDir
    Path tempDir;

    @Test
    void packageCyclesCountEachDetectedCycleOnceRatherThanEachDependencyEdge() {
        JavaClasses importedClasses = new ClassFileImporter().importClasses(AlphaComponent.class, BetaComponent.class);
        ArchitectureRuleResultDto result = new FreeOfPackageCyclesRule()
                .evaluate(new ArchitectureContext(
                        importedClasses,
                        List.of("io.github.jdubois.bootui.engine.architecture.cyclefixtures"),
                        ArchitecturePlatform.SPRING));

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-PKG-001");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).singleElement().asString().contains("Cycle detected");
    }

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
    void noStandardStreamsFlagsNoArgPrintStackTrace() {
        ArchitectureRuleResultDto result = evaluate(new NoStandardStreamsRule(), NoArgPrintStackTraceCaller.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-001");
    }

    @Test
    void capturingAStackTraceThroughAnExplicitWriterIsNotReported() {
        // ARCH-CODE-005 used to report printStackTrace(PrintWriter); it was retired because the remaining matches
        // were mostly the legitimate StringWriter capture idiom, and printStackTrace(System.err) already reads the
        // System.err field, which ARCH-CODE-001 reports.
        assertThat(evaluate(new NoStandardStreamsRule(), WriterArgPrintStackTraceCaller.class)
                        .status())
                .isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(evaluate(new NoStandardStreamsRule(), SystemErrPrintStackTraceCaller.class)
                        .status())
                .isEqualTo(ArchitectureRuleSupport.VIOLATION);
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
    void loggersShouldBePrivateStaticFinalExemptsContainerManagedInjectionPoints() {
        ArchitectureRuleResultDto result =
                evaluate(new LoggersShouldBePrivateStaticFinalRule(), ContainerManagedLoggerComponent.class);

        assertThat(result.status())
                .as("@Inject/@Autowired/@Resource logger fields are wired by the container, e.g. Quarkus's"
                        + " idiomatic `@Inject Logger log;`, so they are exempt from the private/static/final"
                        + " requirement")
                .isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void loggersShouldBePrivateStaticFinalAllowsProtectedInstanceLoggerInAbstractBaseClass() {
        ArchitectureRuleResultDto result =
                evaluate(new LoggersShouldBePrivateStaticFinalRule(), AbstractLoggingBaseComponent.class);

        assertThat(result.status())
                .as("a protected, final, non-static logger initialized via LoggerFactory.getLogger(getClass())"
                        + " in an abstract base class is a well-known SLF4J idiom for subclass-shared loggers")
                .isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void loggersShouldBePrivateStaticFinalStillFlagsPlainPublicInstanceLogger() {
        ArchitectureRuleResultDto result =
                evaluate(new LoggersShouldBePrivateStaticFinalRule(), PublicInstanceLoggerComponent.class);

        assertThat(result.status())
                .as("a non-final, non-static, non-injected, non-abstract-base-class public logger field is"
                        + " neither recognized alternate pattern, so it must still be flagged")
                .isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-012");
    }

    @Test
    void noTestFrameworkDependenciesFlagsMainCodeUsingTestApis() {
        ArchitectureRuleResultDto result =
                evaluateMainOutput(new NoTestFrameworkDependenciesRule(), TestFrameworkUser.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-013");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("org.junit"));
    }

    @Test
    void noTestFrameworkDependenciesFlagsMainCodeUsingQuarkusTestApi() {
        ArchitectureRuleResultDto result =
                evaluateMainOutput(new NoTestFrameworkDependenciesRule(), QuarkusTestFrameworkUser.class);

        assertThat(result.status())
                .as("io.quarkus.test.. must be flagged the same way org.springframework.boot.test.. already is,"
                        + " so a Quarkus app leaking @QuarkusTest into main sources gets the same warning a Spring"
                        + " app leaking @SpringBootTest already does")
                .isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-013");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("io.quarkus.test.junit.QuarkusTest"));
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
    void servicesAndRepositoriesShouldNotDependOnWebTypesFlagsReactiveRequestDependencies() {
        ArchitectureRuleResultDto result = evaluate(
                new ServicesAndRepositoriesShouldNotDependOnServletTypesRule(), ServiceUsingReactiveWebTypes.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-008");
        assertThat(result.violationCount()).isGreaterThanOrEqualTo(3);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("ServerRequest"))
                .anySatisfy(sample -> assertThat(sample).contains("ServerWebExchange"))
                .anySatisfy(sample -> assertThat(sample).contains("WebSession"));
    }

    @Test
    void transactionalAnnotationsShouldNotBeDeclaredOnInterfacesFlagsInterfaceAnnotations() {
        ArchitectureRuleResultDto result = evaluate(
                new TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule(),
                TransactionalInterface.class,
                TransactionalMethodInterface.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-009");
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("TransactionalInterface"));
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("TransactionalMethodInterface"));
    }

    @ParameterizedTest
    @ValueSource(
            classes = {
                TransactionalSpringDataRepository.class,
                JakartaTransactionalSpringDataRepository.class,
                TransactionalSpringDataCrudRepository.class,
                SpringDataBaseRepository.class,
                IndirectTransactionalSpringDataRepository.class,
                DefinedTransactionalSpringDataRepository.class,
                JakartaDefinedTransactionalSpringDataRepository.class,
                InheritedDefinedTransactionalSpringDataRepository.class,
                ComposedDefinedTransactionalSpringDataRepository.class,
                InheritedComposedDefinedTransactionalSpringDataRepository.class
            })
    void transactionalAnnotationsOnSpringDataRepositoryInterfacesAreSupported(Class<?> repositoryType) {
        ArchitectureRuleResultDto result =
                evaluate(new TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule(), repositoryType);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            classes = {
                FragmentSpringDataRepository.class,
                DefinedFragmentSpringDataRepository.class,
                ComposedDefinedFragmentSpringDataRepository.class
            })
    void transactionalFragmentsOfObservedSpringDataRepositoriesAreSupported(Class<?> repositoryType) {
        JavaClasses classes = new ClassFileImporter()
                .importClasses(repositoryType, TransactionalFragment.class, JakartaTransactionalFragment.class);
        ArchitectureContext context =
                new ArchitectureContext(classes, List.of(getClass().getPackageName()), ArchitecturePlatform.SPRING);
        ArchitectureRuleResultDto result =
                new TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule().evaluate(context);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations()).isEmpty();
        assertThat(context.evidence().evaluated()).isTrue();
        assertThat(context.evidence().usable()).isFalse();
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void repositoryFragmentsDoNotHideUnrelatedTransactionalInterfaces() {
        ArchitectureRuleResultDto result = evaluate(
                new TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule(),
                FragmentSpringDataRepository.class,
                TransactionalFragment.class,
                JakartaTransactionalFragment.class,
                TransactionalMethodInterface.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations())
                .containsExactly("Interface method " + TransactionalMethodInterface.class.getName()
                        + ".save() is annotated with @Transactional");
    }

    @Test
    void transactionalFragmentsWithoutAnObservedRepositoryRemainReported() {
        ArchitectureRuleResultDto result = evaluate(
                new TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule(),
                OrdinaryFragmentConsumer.class,
                TransactionalFragment.class,
                JakartaTransactionalFragment.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(4);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("TransactionalFragment"))
                .anySatisfy(sample -> assertThat(sample).contains("JakartaTransactionalFragment"));
    }

    @Test
    void repositoryFragmentExemptionDoesNotChangeQuarkusInterfaceChecks() {
        JavaClasses classes = new ClassFileImporter()
                .importClasses(
                        FragmentSpringDataRepository.class,
                        TransactionalFragment.class,
                        JakartaTransactionalFragment.class);
        ArchitectureRuleResultDto result = new TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule()
                .evaluate(new ArchitectureContext(
                        classes, List.of(getClass().getPackageName()), ArchitecturePlatform.QUARKUS));

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(4);
    }

    @Test
    void transactionalInterfaceGuidanceIncludesBothOrdinaryAndSpringDataReferences() {
        ArchitectureRuleResultDto result = evaluate(
                new TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule(), TransactionalMethodInterface.class);

        assertThat(result.recommendation())
                .contains(
                        "https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html#transactional-query-methods");
        assertThat(result.learnMoreUrl())
                .isEqualTo(
                        "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html");
    }

    @Test
    void springDataRepositoriesDoNotHideOrdinaryTransactionalInterfaceFindings() {
        ArchitectureRuleResultDto result = evaluate(
                new TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule(),
                TransactionalSpringDataRepository.class,
                DefinedTransactionalSpringDataRepository.class,
                TransactionalInterface.class,
                TransactionalMethodInterface.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .containsExactlyInAnyOrder(
                        "Interface " + TransactionalInterface.class.getName() + " is annotated with @Transactional",
                        "Interface method " + TransactionalMethodInterface.class.getName()
                                + ".save() is annotated with @Transactional");
    }

    @Test
    void repositoryNamesAndStereotypesDoNotExemptOrdinaryTransactionalInterfaces() {
        ArchitectureRuleResultDto result = evaluate(
                new TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule(),
                TransactionalLookalikeRepository.class,
                TransactionalStereotypeRepository.class,
                JakartaTransactionalInterface.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(4);
        assertThat(result.sampleViolations())
                .containsExactlyInAnyOrder(
                        "Interface " + TransactionalLookalikeRepository.class.getName()
                                + " is annotated with @Transactional",
                        "Interface method " + TransactionalStereotypeRepository.class.getName()
                                + ".save() is annotated with @Transactional",
                        "Interface " + JakartaTransactionalInterface.class.getName()
                                + " is annotated with @Transactional",
                        "Interface method " + JakartaTransactionalInterface.class.getName()
                                + ".save() is annotated with @Transactional");
    }

    @Test
    void springDataRepositoryExemptionDoesNotChangeQuarkusInterfaceChecks() {
        JavaClasses classes = new ClassFileImporter().importClasses(JakartaTransactionalSpringDataRepository.class);
        ArchitectureRuleResultDto result = new TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule()
                .evaluate(new ArchitectureContext(
                        classes, List.of(getClass().getPackageName()), ArchitecturePlatform.QUARKUS));

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
    }

    @Test
    void proxiedMethodsShouldNotBePrivateOrStaticFlagsUnproxyableMethods() {
        ArchitectureRuleResultDto result =
                evaluate(new ProxiedMethodsShouldNotBePrivateOrStaticRule(), BadProxyAnnotationComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-010");
        assertThat(result.violationCount()).isEqualTo(4);
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
    void noSystemExitFlagsRuntimeExitAndHalt() {
        ArchitectureRuleResultDto result = evaluate(new NoSystemExitRule(), JvmTerminator.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-006");
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("exit"))
                .anySatisfy(sample -> assertThat(sample).contains("halt"));
    }

    @Test
    void noSystemExitExemptsSystemExitCalledDirectlyFromStaticMain() {
        ArchitectureRuleResultDto result = evaluate(new NoSystemExitRule(), SpringExitLauncher.class);

        assertThat(result.status())
                .as("System.exit(SpringApplication.exit(context, ...)) from a static main method is Spring"
                        + " Boot's own documented CLI/batch exit-code idiom, so it must not be flagged")
                .isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void noSystemExitStillFlagsSystemExitFromNonMainMethod() {
        ArchitectureRuleResultDto result = evaluate(new NoSystemExitRule(), NonMainSystemExitCaller.class);

        assertThat(result.status())
                .as("a System.exit call from a service/controller/business-logic method (not the static main"
                        + " entry point) must still be flagged")
                .isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-006");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("exit"));
    }

    @Test
    void noSystemExitStillFlagsSystemExitFromStaticMainOverload() {
        ArchitectureRuleResultDto result = evaluate(new NoSystemExitRule(), MainOverloadSystemExitCaller.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-006");
        assertThat(result.violationCount()).isEqualTo(1);
    }

    @Test
    void noJdkInternalApiDoesNotFlagExportedComSunApi() {
        ArchitectureRuleResultDto result = evaluate(new NoJdkInternalApiRule(), ExportedComSunUser.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void loggersShouldBePrivateStaticFinalFlagsCommonsLoggingLoggers() {
        ArchitectureRuleResultDto result =
                evaluate(new LoggersShouldBePrivateStaticFinalRule(), VisibleCommonsLoggerComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-012");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("LOG"));
    }

    @Test
    void proxiedMethodsAllowProtectedMethodsOnSpringClassBasedProxies() {
        ArchitectureRuleResultDto result =
                evaluate(new ProxiedMethodsShouldNotBePrivateOrStaticRule(), ProtectedProxyAnnotationComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-010");
    }

    @Test
    void noSelfInvocationFlagsClassLevelAsyncSelfCall() {
        ArchitectureRuleResultDto result =
                evaluate(new NoSelfInvocationOfProxiedMethodsRule(), ClassLevelAsyncBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-004");
    }

    @Test
    void noSelfInvocationFlagsAllSpringCacheOperationAnnotations() {
        ArchitectureRuleResultDto result =
                evaluate(new NoSelfInvocationOfProxiedMethodsRule(), SelfInvokingCacheOperationsBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-004");
        assertThat(result.violationCount()).isEqualTo(3);
    }

    @Test
    void noSelfInvocationDoesNotFlagClassLevelTransactionalSelfCall() {
        ArchitectureRuleResultDto result =
                evaluate(new NoSelfInvocationOfProxiedMethodsRule(), ClassLevelTransactionalBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void noSelfInvocationFlagsSelfCallMadeFromInsideALambda() {
        ArchitectureRuleResultDto result =
                evaluate(new NoSelfInvocationOfProxiedMethodsRule(), LambdaSelfInvokingBean.class);

        // javac compiles a lambda body into a private synthetic method of the same class. Skipping every
        // synthetic origin would silence this, and the transaction really is lost when the task runs.
        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("persist"));
    }

    @Test
    void exceptionsShouldBeNamedExceptionAllowsNestedVariantsOfAnExceptionHierarchy() {
        ArchitectureRuleResultDto result = evaluate(
                new ExceptionsShouldBeNamedExceptionRule(), ClaimException.class, ClaimException.AlreadyAssigned.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void exceptionsShouldBeNamedExceptionStillFlagsNestedTypesOutsideAnExceptionHierarchy() {
        ArchitectureRuleResultDto result =
                evaluate(new ExceptionsShouldBeNamedExceptionRule(), ClaimOutcome.Rejected.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("Rejected"));
    }

    @Test
    void liteModeBeanMethodsFlagsSiblingBeanCallInLiteClass() {
        ArchitectureRuleResultDto result =
                evaluate(new LiteModeBeanMethodsShouldNotCallSiblingBeanMethodsRule(), LiteBeanComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-017");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("first").contains("directly calls sibling"));
    }

    @Test
    void liteModeBeanMethodsFlagsProxyBeanMethodsFalseConfiguration() {
        ArchitectureRuleResultDto result =
                evaluate(new LiteModeBeanMethodsShouldNotCallSiblingBeanMethodsRule(), LiteConfiguration.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-017");
    }

    @Test
    void liteModeBeanMethodsPassesForFullConfiguration() {
        ArchitectureRuleResultDto result =
                evaluate(new LiteModeBeanMethodsShouldNotCallSiblingBeanMethodsRule(), FullConfiguration.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void lifecycleCallbacksShouldNotBeProxyDrivenFlagsAnnotatedLifecycleMethods() {
        ArchitectureRuleResultDto result =
                evaluate(new LifecycleCallbacksShouldNotBeProxyDrivenRule(), TransactionalLifecycleBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-018");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("init"));
    }

    @Test
    void lifecycleCallbacksShouldNotBeProxyDrivenFlagsAllSpringCacheOperations() {
        ArchitectureRuleResultDto result =
                evaluate(new LifecycleCallbacksShouldNotBeProxyDrivenRule(), CachedLifecycleBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-018");
        assertThat(result.violationCount()).isEqualTo(3);
    }

    @Test
    void lifecycleCallbacksShouldNotBeProxyDrivenPassesForPlainLifecycleMethods() {
        ArchitectureRuleResultDto result =
                evaluate(new LifecycleCallbacksShouldNotBeProxyDrivenRule(), CleanLifecycleBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void asyncAndTransactionalShouldNotBeCombinedFlagsMethodsWithBothAnnotations() {
        ArchitectureRuleResultDto result =
                evaluate(new AsyncAndTransactionalShouldNotBeCombinedRule(), AsyncTransactionalBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-019");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("doWork"));
    }

    @Test
    void asyncAndTransactionalShouldNotBeCombinedPassesForAsyncOnlyMethods() {
        ArchitectureRuleResultDto result =
                evaluate(new AsyncAndTransactionalShouldNotBeCombinedRule(), AsyncOnlyBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void asyncAndTransactionalShouldNotBeCombinedPassesForPostCommitTransactionalEventListeners() {
        // The @Async + @Transactional(REQUIRES_NEW) + @TransactionalEventListener triple, the real
        // @ApplicationModuleListener it composes, and a hand-composed annotation meta-annotated with
        // @TransactionalEventListener: in all three the publishing transaction has already committed, so a
        // separate transaction on the async thread is the intended shape rather than a lost context.
        ArchitectureRuleResultDto result = evaluate(
                new AsyncAndTransactionalShouldNotBeCombinedRule(),
                AsyncTransactionalEventListenerBean.class,
                ApplicationModuleListenerBean.class,
                ComposedTransactionalEventListenerBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void asyncAndTransactionalShouldNotBeCombinedFlagsBeforeCommitTransactionalEventListeners() {
        ArchitectureRuleResultDto result =
                evaluate(new AsyncAndTransactionalShouldNotBeCombinedRule(), BeforeCommitAsyncEventListenerBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-019");
        assertThat(result.sampleViolations()).singleElement().asString().contains("BEFORE_COMMIT");
    }

    @Test
    void asyncEventListenersShouldReturnVoidFlagsIgnoredReturnValues() {
        ArchitectureRuleResultDto result = evaluate(
                new AsyncEventListenersShouldReturnVoidRule(),
                AsyncEventListenerBean.class,
                ClassLevelAsyncEventListenerBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-020");
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("methodLevel"))
                .anySatisfy(sample -> assertThat(sample).contains("classLevel"));
    }

    @Test
    void asyncEventListenersShouldReturnVoidAllowsSynchronousReturnValuesAndAsyncVoidListeners() {
        ArchitectureRuleResultDto result =
                evaluate(new AsyncEventListenersShouldReturnVoidRule(), ValidEventListenerBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void legacyJavaxTransactionalShouldBeMigratedFlagsClassAndMethodUsage() {
        ArchitectureRuleResultDto result = evaluate(
                new LegacyJavaxTransactionalShouldBeMigratedRule(),
                LegacyTransactionalBean.class,
                LegacyTransactionalMethodBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-022");
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("LegacyTransactionalBean"))
                .anySatisfy(sample -> assertThat(sample).contains("legacyTransaction"));
    }

    @Test
    void loggersShouldNotTreatLegacyJavaxResourceAsContainerManagedOnSpringSeven() {
        ArchitectureRuleResultDto result =
                evaluate(new LoggersShouldBePrivateStaticFinalRule(), LegacyResourceLoggerComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-012");
    }

    @Test
    void beanPostProcessorFactoryMethodsShouldBeStaticFlagsNonStaticFactoryMethods() {
        ArchitectureRuleResultDto result = evaluate(
                new BeanPostProcessorFactoryMethodsShouldBeStaticRule(),
                PostProcessorConfiguration.class,
                SampleBeanPostProcessor.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-021");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("nonStaticPostProcessor"));
    }

    @Test
    void internalPackagesShouldNotBeAccessedExternallyFlagsCrossModuleInternalAccess() {
        JavaClasses importedClasses =
                new ClassFileImporter().importPackages("io.github.jdubois.bootui.engine.architecture.modulefixtures");
        ArchitectureRuleResultDto result = new InternalPackagesShouldNotBeAccessedExternallyRule()
                .evaluate(new ArchitectureContext(
                        importedClasses,
                        List.of("io.github.jdubois.bootui.engine.architecture.modulefixtures"),
                        ArchitecturePlatform.SPRING));

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-MOD-001");
        assertThat(result.sampleViolations())
                .anySatisfy(sample ->
                        assertThat(sample).contains("ModuleTwoConsumer").contains("internal"));
        assertThat(result.sampleViolations())
                .noneSatisfy(sample -> assertThat(sample).contains("ModuleOnePublic"));
    }

    @Test
    void noFieldInjectionRuleFlagsAutowiredAndValueFields() {
        ArchitectureRuleResultDto result = evaluate(new NoFieldInjectionRule(), AutowiredFieldBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-001");
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("service"));
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("name"));
    }

    @Test
    void noFieldInjectionRulePassesForConstructorInjection() {
        ArchitectureRuleResultDto result = evaluate(new NoFieldInjectionRule(), ConstructorInjectedBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void noFieldInjectionRuleKeepsReportingAnnotatedJavaFieldsEvenWithAMatchingConstructorParameter() {
        // The constructor-property exemption is Kotlin-only (#1175): javac never copies a parameter annotation onto
        // a field, so a Java field carrying @Value was written there by the developer.
        ArchitectureRuleResultDto result = evaluate(new NoFieldInjectionRule(), JavaFieldAndConstructorValueBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).singleElement().asString().contains("name");
    }

    @Test
    void noFieldInjectionRuleDoesNotFlagStandardJakartaInjectionAnnotations() {
        // Regression guard for the ARCH-SPRING-001 narrowing: plain jakarta.inject.Inject / @Resource
        // field injection (the idiomatic CDI/Quarkus style) must never trip Spring's own field-injection
        // rule. See FieldsShouldNotUseStandardInjectionAnnotationsRule (ARCH-CODE-016) for the
        // framework-neutral counterpart, and ArchitectureCdiNeutralityTests for the exhaustive
        // cross-rule check against a full pure-CDI fixture set.
        ArchitectureRuleResultDto result = evaluate(new NoFieldInjectionRule(), JakartaInjectFieldBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void fieldsShouldNotUseStandardInjectionAnnotationsFlagsJakartaInjectAndResourceFields() {
        ArchitectureRuleResultDto result =
                evaluate(new FieldsShouldNotUseStandardInjectionAnnotationsRule(), JakartaInjectFieldBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-016");
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("service"));
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("name"));
    }

    @Test
    void fieldsShouldNotUseStandardInjectionAnnotationsPassesForConstructorInjection() {
        ArchitectureRuleResultDto result =
                evaluate(new FieldsShouldNotUseStandardInjectionAnnotationsRule(), ConstructorInjectedBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void fieldsShouldNotUseStandardInjectionAnnotationsDoesNotFlagSpringFieldInjection() {
        // The two field-injection rules are deliberately disjoint: Spring's own @Autowired/@Value must
        // never also trip the standard-annotation rule.
        ArchitectureRuleResultDto result =
                evaluate(new FieldsShouldNotUseStandardInjectionAnnotationsRule(), AutowiredFieldBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void proxiedMethodsShouldNotBePrivateOrStaticFlagsFinalSpringTransactionalMethod() {
        ArchitectureRuleResultDto result =
                evaluate(new ProxiedMethodsShouldNotBePrivateOrStaticRule(), FinalProxyAnnotationComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-010");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("final"));
    }

    @Test
    void proxiedMethodsShouldNotBePrivateOrStaticPassesForProtectedOrPackagePrivateJakartaTransactional() {
        // The key CDI-neutrality finding: a CDI client proxy can intercept public, protected, AND
        // package-private methods alike (Jakarta CDI spec, "Unproxyable bean types"); only private,
        // static, or final methods are excluded. Spring's stricter "public only" bar must not apply to
        // the portable jakarta.transaction.Transactional annotation, or this rule would false-positive
        // on a perfectly valid Quarkus/CDI transactional service method.
        ArchitectureRuleResultDto result = evaluate(
                new ProxiedMethodsShouldNotBePrivateOrStaticRule(), GoodJakartaTransactionalVisibilityComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void proxiedMethodsShouldNotBePrivateOrStaticFlagsPrivateOrFinalJakartaTransactional() {
        // Even under the CDI-permissive bar, private and final methods are still unproxyable (Jakarta
        // CDI's "Unproxyable bean types" excludes both), so these remain genuine violations.
        ArchitectureRuleResultDto result = evaluate(
                new ProxiedMethodsShouldNotBePrivateOrStaticRule(), BadJakartaTransactionalVisibilityComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-010");
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("private"));
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("final"));
        assertThat(result.sampleViolations())
                .noneSatisfy(sample -> assertThat(sample).contains("protected"));
        assertThat(result.sampleViolations())
                .noneSatisfy(sample -> assertThat(sample).contains("package-private"));
    }

    @Test
    void scheduledMethodsShouldHaveSupportedSignaturesFlagsRxJava2ReturnType() {
        // The false-negative fix: Spring's ScheduledAnnotationReactiveSupport only recognizes RxJava
        // 3's io.reactivex.rxjava3.* namespace, never the older io.reactivex.* (RxJava 2) one, so a
        // scheduled method returning a bare RxJava 2 type has its return value silently discarded.
        ArchitectureRuleResultDto result =
                evaluate(new ScheduledMethodsShouldHaveSupportedSignaturesRule(), RxJava2ScheduledComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-012");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("io.reactivex.Flowable"));
    }

    @Test
    void scheduledMethodsShouldHaveSupportedSignaturesPassesForRxJava3ReturnType() {
        ArchitectureRuleResultDto result =
                evaluate(new ScheduledMethodsShouldHaveSupportedSignaturesRule(), RxJava3ScheduledComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void scheduledMethodsShouldHaveSupportedSignaturesPassesForJdkFlowPublisherReturnType() {
        ArchitectureRuleResultDto result =
                evaluate(new ScheduledMethodsShouldHaveSupportedSignaturesRule(), JdkFlowScheduledComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void scheduledMethodsShouldHaveSupportedSignaturesPassesForMutinyUniAndMultiReturnTypes() {
        ArchitectureRuleResultDto result =
                evaluate(new ScheduledMethodsShouldHaveSupportedSignaturesRule(), MutinyScheduledComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void noDirectThreadInstantiationFlagsNewThread() {
        ArchitectureRuleResultDto result = evaluate(new NoDirectThreadInstantiationRule(), DirectThreadCreator.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-017");
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("Thread"));
    }

    @Test
    void noDirectThreadInstantiationFlagsThreadSubclassInstantiation() {
        ArchitectureRuleResultDto result = evaluate(
                new NoDirectThreadInstantiationRule(), CustomThreadInstantiator.class, CustomThreadSubclass.class);

        assertThat(result.status())
                .as("isAssignableTo(Thread.class) must also catch instantiating a class that extends Thread,"
                        + " not just the Thread constructor itself")
                .isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-017");
    }

    @Test
    void noDirectThreadInstantiationPassesForManagedExecutorUsage() {
        ArchitectureRuleResultDto result = evaluate(new NoDirectThreadInstantiationRule(), ManagedExecutorUser.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void asyncEventListenersShouldReturnVoidSuppressesWhenArchSpring011AlreadyFlagsMethod() {
        ArchitectureRuleResultDto asyncResult =
                evaluate(new AsyncMethodsShouldHaveSupportedSignaturesRule(), NonFutureAsyncEventListenerBean.class);
        ArchitectureRuleResultDto listenerResult =
                evaluate(new AsyncEventListenersShouldReturnVoidRule(), NonFutureAsyncEventListenerBean.class);

        assertThat(asyncResult.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(asyncResult.id()).isEqualTo("ARCH-SPRING-011");
        assertThat(asyncResult.violationCount()).isEqualTo(1);
        assertThat(listenerResult.status()).isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(listenerResult.violationCount()).isZero();
    }

    @Test
    void packageCyclesDetectCrossRootCyclesAcrossSharedMultiSegmentPrefixAndNeverCollapseSingleSegmentPrefix() {
        JavaClasses importedClasses = new ClassFileImporter().importClasses(AlphaComponent.class, BetaComponent.class);
        ArchitectureRuleResultDto result = new FreeOfPackageCyclesRule()
                .evaluate(new ArchitectureContext(
                        importedClasses,
                        List.of(
                                "io.github.jdubois.bootui.engine.architecture.cyclefixtures.alpha",
                                "io.github.jdubois.bootui.engine.architecture.cyclefixtures.beta"),
                        ArchitecturePlatform.QUARKUS));

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-PKG-001");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(FreeOfPackageCyclesRule.sliceRoots(List.of("com.acme.order", "com.other.billing")))
                .containsExactly("com.acme.order", "com.other.billing")
                .doesNotContain("com");
    }

    @Test
    void internalPackagesMatchesInternalStrictlyBelowBasePackageEvenWhenBasePackageContainsInternal() {
        JavaClasses importedClasses =
                new ClassFileImporter().importPackages("io.github.jdubois.bootui.engine.architecture.modulefixtures");
        // When the base package itself ends at "...moduleone.internal", classes directly in that base package
        // have no "<module>.internal" segment below the base package, so internal access inside it is not
        // misattributed.
        ArchitectureRuleResultDto result = new InternalPackagesShouldNotBeAccessedExternallyRule()
                .evaluate(new ArchitectureContext(
                        importedClasses,
                        List.of("io.github.jdubois.bootui.engine.architecture.modulefixtures.moduleone.internal"),
                        ArchitecturePlatform.SPRING));

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);

        // On Quarkus multi-root base packages where each module is its own base package, cross-module access
        // into "<moduleBase>.internal" is still detected.
        ArchitectureRuleResultDto quarkusMultiRoot = new InternalPackagesShouldNotBeAccessedExternallyRule()
                .evaluate(new ArchitectureContext(
                        importedClasses,
                        List.of(
                                "io.github.jdubois.bootui.engine.architecture.modulefixtures.moduleone",
                                "io.github.jdubois.bootui.engine.architecture.modulefixtures.moduletwo"),
                        ArchitecturePlatform.QUARKUS));
        assertThat(quarkusMultiRoot.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(quarkusMultiRoot.violationCount()).isEqualTo(3);
    }

    @Test
    void noSelfInvocationIgnoresPrivateStaticAndFinalHelpersInClassLevelAsyncAndFlagsMethodReferences() {
        assertThat(evaluate(new NoSelfInvocationOfProxiedMethodsRule(), ClassLevelAsyncWithUnannotatedHelpers.class)
                        .status())
                .isEqualTo(ArchitectureRuleSupport.PASS);

        ArchitectureRuleResultDto methodRefResult =
                evaluate(new NoSelfInvocationOfProxiedMethodsRule(), MethodRefBean.class);
        assertThat(methodRefResult.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(methodRefResult.violationCount()).isEqualTo(1);
        assertThat(methodRefResult.sampleViolations())
                .singleElement()
                .asString()
                .contains("MethodRefBean.save()");
    }

    @Test
    void liteModeBeanMethodsDetectsAutoConfigurationAndStaticSiblingCallsAndUsesCallLocation() {
        JavaClasses classes = new ClassFileImporter()
                .importClasses(AutoConfigBean.class, StaticSiblingConfig.class, LiteBeanCallingExternalMethod.class);
        ArchitectureContext externalCallContext = new ArchitectureContext(
                new ClassFileImporter().importClasses(LiteBeanCallingExternalMethod.class),
                List.of(getClass().getPackageName()),
                ArchitecturePlatform.SPRING);
        ArchitectureRuleResultDto externalResult =
                new LiteModeBeanMethodsShouldNotCallSiblingBeanMethodsRule().evaluate(externalCallContext);
        assertThat(externalResult.status()).isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(externalCallContext.evidence().requiredUnknown()).isFalse();

        ArchitectureContext context =
                new ArchitectureContext(classes, List.of(getClass().getPackageName()), ArchitecturePlatform.SPRING);
        ArchitectureRuleResultDto result =
                new LiteModeBeanMethodsShouldNotCallSiblingBeanMethodsRule().evaluate(context);
        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anySatisfy(sample ->
                        assertThat(sample).contains("AutoConfigBean.first()").contains("AutoConfigBean.second()"))
                .anySatisfy(sample -> assertThat(sample)
                        .contains("StaticSiblingConfig.first()")
                        .contains("StaticSiblingConfig.seco"));
        assertThat(result.sampleLocations()).allSatisfy(location -> {
            assertThat(location.memberName()).isEqualTo("first");
            assertThat(location.line()).isPositive();
        });
    }

    @Test
    void utilityClassesIgnoresSubclassesInterfaceImplementationsAndSuperclasses() {
        ArchitectureRuleResultDto result = evaluate(
                new UtilityClassesShouldBeFinalWithPrivateConstructorRule(),
                ExceptionWithStaticFactory.class,
                InterfaceImplementingStaticHolder.class,
                BaseClassWithStaticHelper.class,
                SubclassOfBaseWithStaticHelper.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void noDeprecatedApiRuleExemptsCallsInsideDeprecatedClassOrMethod() {
        assertThat(evaluate(new NoDeprecatedApiRule(), DeprecatedApiTarget.class, DeprecatedCallerScope.class)
                        .status())
                .isEqualTo(ArchitectureRuleSupport.PASS);

        ArchitectureRuleResultDto forRemovalResult = evaluate(
                new NoDeprecatedApiRule(), DeprecatedApiTarget.class, DeprecatedCallerUsingForRemovalTarget.class);
        assertThat(forRemovalResult.status())
                .as("@Deprecated(forRemoval = true) targets are still flagged inside @Deprecated callers")
                .isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(forRemovalResult.violationCount()).isEqualTo(1);

        ArchitectureRuleResultDto activeResult =
                evaluate(new NoDeprecatedApiRule(), DeprecatedApiTarget.class, ActiveDeprecatedCaller.class);
        assertThat(activeResult.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(activeResult.violationCount()).isEqualTo(1);
    }

    @Test
    void noSystemExitAllowsJep512PackagePrivateAndInstanceMainEntryPoints() {
        ArchitectureRuleResultDto result = evaluate(
                new NoSystemExitRule(),
                PackagePrivateMainLauncher.class,
                InstanceMainLauncher.class,
                ZeroArgStaticMainLauncher.class,
                ZeroArgInstanceMainLauncher.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void cacheableAndCachePutRuleFlagsVoidAndBoxedVoidAndExemptsEvictAndValuedMethods() {
        ArchitectureRuleResultDto violation = evaluate(
                new CacheableAndCachePutMethodsShouldNotReturnVoidRule(),
                VoidCacheableComponent.class,
                ClassLevelVoidCacheableComponent.class);

        assertThat(violation.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(violation.id()).isEqualTo("ARCH-SPRING-027");
        assertThat(violation.severity()).isEqualTo("HIGH");
        assertThat(violation.violationCount()).isEqualTo(5);
        assertThat(violation.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("voidCacheable"))
                .anySatisfy(sample -> assertThat(sample).contains("boxedVoidCachePut"))
                .anySatisfy(sample -> assertThat(sample).contains("cachingWithPut"))
                .anySatisfy(sample -> assertThat(sample).contains("metaVoidCacheable"))
                .anySatisfy(sample -> assertThat(sample).contains("inheritedClassLevelVoid"))
                .noneSatisfy(sample -> assertThat(sample).contains("evictOnly"))
                .noneSatisfy(sample -> assertThat(sample).contains("cachingEvictOnly"))
                .noneSatisfy(sample -> assertThat(sample).contains("valuedCacheable"))
                .noneSatisfy(sample -> assertThat(sample).contains("privateMethodCacheable"))
                .noneSatisfy(sample -> assertThat(sample).contains("javaContinuationValued"))
                .noneSatisfy(sample -> assertThat(sample).contains("classLevelEvictOverride"))
                .noneSatisfy(sample -> assertThat(sample).contains("classLevelPostConstruct"))
                .noneSatisfy(sample -> assertThat(sample).contains("privateHelper"));

        assertThat(evaluate(new CacheableAndCachePutMethodsShouldNotReturnVoidRule(), ValidCacheableComponent.class)
                        .status())
                .isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void scheduledAndEventListenerMethodsRuleFlagsUnmanagedTopLevelClassesAndAllowsManagedBeans() {
        ArchitectureRuleResultDto violation = evaluate(
                new ScheduledAndEventListenerMethodsShouldBeOnManagedBeansRule(),
                TopLevelUnmanagedScheduledTask.class,
                TopLevelUnmanagedEventListener.class,
                TopLevelUnmanagedRunnableScheduledTask.class,
                TopLevelSchedulerConfiguration.class);

        assertThat(violation.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(violation.id()).isEqualTo("ARCH-SPRING-031");
        assertThat(violation.severity()).isEqualTo("MEDIUM");
        assertThat(violation.violationCount()).isEqualTo(3);
        assertThat(violation.sampleViolations())
                .anySatisfy(sample -> assertThat(sample)
                        .contains("TopLevelUnmanagedScheduledTask")
                        .contains("runTask"))
                .anySatisfy(sample -> assertThat(sample)
                        .contains("TopLevelUnmanagedEventListener")
                        .contains("onEvent"))
                .anySatisfy(sample -> assertThat(sample)
                        .contains("TopLevelUnmanagedRunnableScheduledTask")
                        .contains("runTask"));

        ArchitectureRuleResultDto pass = evaluate(
                new ScheduledAndEventListenerMethodsShouldBeOnManagedBeansRule(),
                TopLevelComponentScheduledTask.class,
                TopLevelAbstractScheduledBase.class,
                TopLevelScheduledBaseWithSubclass.class,
                TopLevelScheduledSubclassBean.class,
                TopLevelBeanFactoryScheduledTask.class,
                TopLevelInterfaceScheduledTaskImpl.class,
                TopLevelImportedEventListener.class,
                TopLevelSchedulerConfiguration.class);
        assertThat(pass.status()).isEqualTo(ArchitectureRuleSupport.PASS);

        JavaClasses quarkusClasses = new ClassFileImporter().importClasses(TopLevelUnmanagedScheduledTask.class);
        ArchitectureRuleResultDto quarkusResult = new ScheduledAndEventListenerMethodsShouldBeOnManagedBeansRule()
                .evaluate(new ArchitectureContext(
                        quarkusClasses,
                        List.of(ArchitectureRulesTests.class.getPackageName()),
                        ArchitecturePlatform.QUARKUS));
        assertThat(quarkusResult.status()).isEqualTo(ArchitectureRuleSupport.SKIPPED);
    }

    @Test
    void noSharedStatefulJdkFormattersAndParsersRuleFlagsStaticAndSharedBeanFieldsOnly() {
        ArchitectureRuleResultDto violation = evaluate(
                new NoSharedStatefulJdkFormattersAndParsersRule(),
                StaticFormatterHolder.class,
                SharedBeanWithStatefulFormatters.class);

        assertThat(violation.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(violation.id()).isEqualTo("ARCH-CODE-019");
        assertThat(violation.severity()).isEqualTo("MEDIUM");
        assertThat(violation.violationCount()).isEqualTo(9);
        assertThat(violation.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("STATIC_DATE_FORMAT"))
                .anySatisfy(sample -> assertThat(sample).contains("dateFormat"))
                .anySatisfy(sample -> assertThat(sample).contains("decimalFormat"))
                .anySatisfy(sample -> assertThat(sample).contains("numberFormat"))
                .anySatisfy(sample -> assertThat(sample).contains("messageFormat"))
                .anySatisfy(sample -> assertThat(sample).contains("documentBuilder"))
                .anySatisfy(sample -> assertThat(sample).contains("saxParser"))
                .anySatisfy(sample -> assertThat(sample).contains("transformer"))
                .anySatisfy(sample -> assertThat(sample).contains("xmlValidator"));

        ArchitectureRuleResultDto inheritedAndRefreshViolation = evaluate(
                new NoSharedStatefulJdkFormattersAndParsersRule(),
                RefreshScopedFormatterBean.class,
                BaseClassWithFormatterField.class,
                SharedSubclassInheritingFormatterField.class);
        assertThat(inheritedAndRefreshViolation.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(inheritedAndRefreshViolation.violationCount()).isEqualTo(2);
        assertThat(inheritedAndRefreshViolation.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("refreshDateFormat"))
                .anySatisfy(sample -> assertThat(sample).contains("inheritedDateFormat"));

        ArchitectureRuleResultDto pass = evaluate(
                new NoSharedStatefulJdkFormattersAndParsersRule(),
                SafeFormatterAndValidatorBean.class,
                PrototypeFormatterBean.class,
                RequestScopedFormatterBean.class,
                UnscopedCdiProducerHolder.class,
                UnscopedProducedFormatterHolder.class,
                PlainUnmanagedFormatterDto.class);
        assertThat(pass.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    // Fixtures compile into target/test-classes, which ARCH-CODE-013 deliberately ignores; copy them to main output.
    private ArchitectureRuleResultDto evaluateMainOutput(ArchitectureRule rule, Class<?>... classes) {
        JavaClasses importedClasses = CompiledOutputFixtures.importFrom(tempDir, "target/classes", classes);
        return rule.evaluate(new ArchitectureContext(
                importedClasses, List.of(ArchitectureRulesTests.class.getPackageName()), ArchitecturePlatform.SPRING));
    }

    private static ArchitectureRuleResultDto evaluate(ArchitectureRule rule, Class<?>... classes) {
        JavaClasses importedClasses = new ClassFileImporter().importClasses(classes);
        return rule.evaluate(new ArchitectureContext(
                importedClasses, List.of(ArchitectureRulesTests.class.getPackageName()), ArchitecturePlatform.SPRING));
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

    private static class NoArgPrintStackTraceCaller {

        void log(Exception e) {
            e.printStackTrace();
        }
    }

    private static class WriterArgPrintStackTraceCaller {

        void log(Exception e, PrintWriter writer) {
            e.printStackTrace(writer);
        }
    }

    private static class SystemErrPrintStackTraceCaller {

        void log(Exception e) {
            e.printStackTrace(System.err);
        }
    }

    private static class VisibleLoggerComponent {

        static final Logger LOGGER = LoggerFactory.getLogger(VisibleLoggerComponent.class);
    }

    private static class WellFormedLoggerComponent {

        private static final Logger LOGGER = LoggerFactory.getLogger(WellFormedLoggerComponent.class);
    }

    private static class ContainerManagedLoggerComponent {

        @Inject
        Logger injectedLogger;

        @Autowired
        Logger autowiredLogger;

        @Resource
        Logger resourceLogger;
    }

    private abstract static class AbstractLoggingBaseComponent {

        protected final Logger logger = LoggerFactory.getLogger(getClass());
    }

    private static class PublicInstanceLoggerComponent {

        public Logger logger = LoggerFactory.getLogger(getClass());
    }

    private static class TestFrameworkUser {

        private final org.junit.jupiter.api.TestInfo testInfo = null;
    }

    @io.quarkus.test.junit.QuarkusTest
    private static class QuarkusTestFrameworkUser {}

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

    @Service
    private static class ServiceUsingReactiveWebTypes {

        String requestPath(ServerRequest request) {
            return request.path();
        }

        String exchangePath(ServerWebExchange exchange) {
            return exchange.getRequest().getPath().value();
        }

        String sessionId(WebSession session) {
            return session.getId();
        }
    }

    @Transactional
    private interface TransactionalInterface {}

    private interface TransactionalMethodInterface {

        @Transactional
        void save();
    }

    @Transactional(readOnly = true)
    private interface TransactionalSpringDataRepository
            extends org.springframework.data.repository.Repository<Object, Long> {

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        int expireIfDueInOwnTransaction(Long id);
    }

    @jakarta.transaction.Transactional
    private interface JakartaTransactionalSpringDataRepository
            extends org.springframework.data.repository.Repository<Object, Long> {

        @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.REQUIRES_NEW)
        int expireIfDueInOwnTransaction(Long id);
    }

    private interface TransactionalSpringDataCrudRepository extends CrudRepository<Object, Long> {

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        int expireIfDueInOwnTransaction(Long id);
    }

    @NoRepositoryBean
    @Transactional
    private interface SpringDataBaseRepository<T, ID> extends CrudRepository<T, ID> {}

    private interface IndirectTransactionalSpringDataRepository extends SpringDataBaseRepository<Object, Long> {

        @Transactional
        void saveInTransaction();
    }

    @RepositoryDefinition(domainClass = Object.class, idClass = Long.class)
    @Transactional(readOnly = true)
    private interface DefinedTransactionalSpringDataRepository {

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        int expireIfDueInOwnTransaction(Long id);
    }

    @RepositoryDefinition(domainClass = Object.class, idClass = Long.class)
    @jakarta.transaction.Transactional
    private interface JakartaDefinedTransactionalSpringDataRepository {

        @jakarta.transaction.Transactional
        void save();
    }

    @Transactional
    private interface InheritedDefinedTransactionalSpringDataRepository
            extends DefinedTransactionalSpringDataRepository {

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        void saveInNewTransaction();
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @RepositoryDefinition(domainClass = Object.class, idClass = Long.class)
    private @interface ComposedRepositoryDefinition {}

    @ComposedRepositoryDefinition
    @Transactional
    private interface ComposedDefinedTransactionalSpringDataRepository {

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        void saveInNewTransaction();
    }

    @jakarta.transaction.Transactional
    private interface InheritedComposedDefinedTransactionalSpringDataRepository
            extends ComposedDefinedTransactionalSpringDataRepository {

        @jakarta.transaction.Transactional
        void save();
    }

    @jakarta.transaction.Transactional
    private interface JakartaTransactionalFragment {

        @jakarta.transaction.Transactional
        void saveFragment();
    }

    @Transactional(readOnly = true)
    private interface TransactionalFragment extends JakartaTransactionalFragment {

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        int expireInOwnTransaction(Long id);
    }

    private interface FragmentSpringDataRepository extends CrudRepository<Object, Long>, TransactionalFragment {}

    @RepositoryDefinition(domainClass = Object.class, idClass = Long.class)
    private interface DefinedFragmentSpringDataRepository extends TransactionalFragment {}

    @ComposedRepositoryDefinition
    private interface ComposedDefinedFragmentSpringDataRepository extends TransactionalFragment {}

    private interface OrdinaryFragmentConsumer extends TransactionalFragment {}

    @Transactional
    private interface TransactionalLookalikeRepository {}

    @Repository
    private interface TransactionalStereotypeRepository {

        @Transactional
        void save();
    }

    @jakarta.transaction.Transactional
    private interface JakartaTransactionalInterface {

        @jakarta.transaction.Transactional
        void save();
    }

    private static class BadProxyAnnotationComponent {

        @Transactional
        private void privateTransactional() {}

        @Async
        static void staticAsync() {}

        @CachePut("items")
        private String privateCachePut() {
            return "item";
        }

        @CacheEvict("items")
        protected void protectedCacheEvict() {}

        @Caching(evict = @CacheEvict("items"))
        static void staticCaching() {}
    }

    private static class SelfInvokingCacheOperationsBean {

        void refreshAll() {
            put();
            evict();
            combined();
        }

        @CachePut("items")
        public String put() {
            return "item";
        }

        @CacheEvict("items")
        public void evict() {}

        @Caching(put = @CachePut("items"), evict = @CacheEvict("other"))
        public String combined() {
            return "item";
        }
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

    private static class JvmTerminator {

        void terminate() {
            System.exit(0);
            Runtime.getRuntime().exit(1);
            Runtime.getRuntime().halt(2);
        }
    }

    private static class SpringExitLauncher {

        public static void main(String[] args) {
            ConfigurableApplicationContext context = SpringApplication.run(SpringExitLauncher.class, args);
            System.exit(SpringApplication.exit(context));
        }
    }

    private static class NonMainSystemExitCaller {

        void shutdown() {
            System.exit(1);
        }
    }

    private static class MainOverloadSystemExitCaller {

        public static void main(int exitCode) {
            System.exit(exitCode);
        }
    }

    private static class ExportedComSunUser {

        String osName(com.sun.management.OperatingSystemMXBean osBean) {
            return osBean.getName();
        }
    }

    private static class VisibleCommonsLoggerComponent {

        static final org.apache.commons.logging.Log LOG =
                org.apache.commons.logging.LogFactory.getLog(VisibleCommonsLoggerComponent.class);
    }

    private static class ProtectedProxyAnnotationComponent {

        @Transactional
        protected void protectedTransactional() {}
    }

    private static class FinalProxyAnnotationComponent {

        @Transactional
        final void finalTransactional() {}
    }

    @Async
    private static class ClassLevelAsyncBean {

        void outer() {
            inner();
        }

        void inner() {}
    }

    @Transactional
    private static class ClassLevelTransactionalBean {

        void outer() {
            inner();
        }

        void inner() {}
    }

    /** A self-invocation the developer wrote inside a lambda, which javac hides in a synthetic method. */
    @Service
    private static class LambdaSelfInvokingBean {

        Runnable persistLater(String value) {
            return () -> persist(value);
        }

        @Transactional
        public void persist(String value) {}
    }

    /** An exception hierarchy whose variants are nested inside the type they specialise. */
    private static class ClaimException extends RuntimeException {

        static final class AlreadyAssigned extends ClaimException {}
    }

    /** A nested exception whose enclosing class says nothing about it: still a naming finding. */
    private static class ClaimOutcome {

        static final class Rejected extends RuntimeException {}
    }

    @Component
    private static class LiteBeanComponent {

        @Bean
        String first() {
            return second() + "x";
        }

        @Bean
        String second() {
            return "s";
        }
    }

    @Configuration(proxyBeanMethods = false)
    private static class LiteConfiguration {

        @Bean
        String one() {
            return two() + "x";
        }

        @Bean
        String two() {
            return "t";
        }
    }

    @Configuration
    private static class FullConfiguration {

        @Bean
        String alpha() {
            return beta() + "x";
        }

        @Bean
        String beta() {
            return "b";
        }
    }

    private static class TransactionalLifecycleBean {

        @PostConstruct
        @Transactional
        void init() {}
    }

    private static class CachedLifecycleBean {

        @PostConstruct
        @CachePut("items")
        void put() {}

        @PostConstruct
        @CacheEvict("items")
        void evict() {}

        @PostConstruct
        @Caching(evict = @CacheEvict("items"))
        void combined() {}
    }

    private static class CleanLifecycleBean {

        @PostConstruct
        void init() {}
    }

    private static class AsyncTransactionalBean {

        @Async
        @Transactional
        void doWork() {}
    }

    private static class AsyncOnlyBean {

        @Async
        void doWork() {}
    }

    // The shape Spring Modulith's @ApplicationModuleListener composes, written out by hand.
    private static class AsyncTransactionalEventListenerBean {

        @Async
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        @TransactionalEventListener
        void onSomethingHappened(String event) {}
    }

    private static class ApplicationModuleListenerBean {

        // The composed annotation already implies both, but a redundant pair is written out here so the
        // rule's direct-annotation detection fires and the exemption is what keeps the method silent.
        @Async
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        @ApplicationModuleListener
        void onSomethingHappened(String event) {}
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @TransactionalEventListener
    private @interface AfterCommitListener {}

    private static class ComposedTransactionalEventListenerBean {

        @Async
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        @AfterCommitListener
        void onSomethingHappened(String event) {}
    }

    private static class BeforeCommitAsyncEventListenerBean {

        @Async
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
        void onSomethingHappened(String event) {}
    }

    private static class AsyncEventListenerBean {

        @Async
        @EventListener
        Future<String> methodLevel(String event) {
            return CompletableFuture.completedFuture(event);
        }

        @EventListener
        String synchronous(String event) {
            return event;
        }
    }

    @Async
    private static class ClassLevelAsyncEventListenerBean {

        @EventListener
        CompletableFuture<String> classLevel(String event) {
            return CompletableFuture.completedFuture(event);
        }
    }

    private static class ValidEventListenerBean {

        @Async
        @EventListener
        void asynchronous(String event) {}

        @EventListener
        String synchronous(String event) {
            return event;
        }
    }

    @javax.transaction.Transactional
    private static class LegacyTransactionalBean {}

    private static class LegacyTransactionalMethodBean {

        @javax.transaction.Transactional
        void legacyTransaction() {}
    }

    private static class LegacyResourceLoggerComponent {

        @javax.annotation.Resource
        Logger logger;
    }

    @Configuration
    private static class PostProcessorConfiguration {

        @Bean
        SampleBeanPostProcessor nonStaticPostProcessor() {
            return new SampleBeanPostProcessor();
        }

        @Bean
        static SampleBeanPostProcessor staticPostProcessor() {
            return new SampleBeanPostProcessor();
        }
    }

    private static class SampleBeanPostProcessor implements BeanPostProcessor {}

    private static class AutowiredFieldBean {

        @Autowired
        private ExampleService service;

        @Value("${some.name}")
        private String name;
    }

    private static class JakartaInjectFieldBean {

        @Inject
        private ExampleService service;

        @Resource
        private String name;
    }

    private static class ConstructorInjectedBean {

        private final ExampleService service;

        ConstructorInjectedBean(ExampleService service) {
            this.service = service;
        }
    }

    private static class JavaFieldAndConstructorValueBean {

        @Value("${some.name}")
        private final String name;

        JavaFieldAndConstructorValueBean(@Value("${some.name}") String name) {
            this.name = name;
        }
    }

    private static class GoodJakartaTransactionalVisibilityComponent {

        @jakarta.transaction.Transactional
        protected void protectedTransactional() {}

        @jakarta.transaction.Transactional
        void packagePrivateTransactional() {}
    }

    private static class BadJakartaTransactionalVisibilityComponent {

        @jakarta.transaction.Transactional
        private void privateTransactional() {}

        @jakarta.transaction.Transactional
        final void finalTransactional() {}
    }

    private static class RxJava2ScheduledComponent {

        @Scheduled(fixedDelay = 1000)
        io.reactivex.Flowable<String> run() {
            return null;
        }
    }

    private static class RxJava3ScheduledComponent {

        @Scheduled(fixedDelay = 1000)
        io.reactivex.rxjava3.core.Single<String> run() {
            return null;
        }
    }

    private static class JdkFlowScheduledComponent {

        @Scheduled(fixedDelay = 1000)
        java.util.concurrent.Flow.Publisher<String> run() {
            return null;
        }
    }

    private static class MutinyScheduledComponent {

        @Scheduled(fixedDelay = 1000)
        io.smallrye.mutiny.Uni<String> uni() {
            return null;
        }

        @Scheduled(fixedDelay = 1000)
        io.smallrye.mutiny.Multi<String> multi() {
            return null;
        }
    }

    private static class DirectThreadCreator {

        void start() {
            new Thread(() -> {}).start();
        }
    }

    private static class CustomThreadSubclass extends Thread {}

    private static class CustomThreadInstantiator {

        void start() {
            new CustomThreadSubclass().start();
        }
    }

    private static class ManagedExecutorUser {

        void submit() {
            java.util.concurrent.Executors.newFixedThreadPool(2).submit(() -> {});
        }
    }

    private static class NonFutureAsyncEventListenerBean {

        @Async
        @EventListener
        String handledByArchSpring011(String event) {
            return event;
        }
    }

    @Async
    private static class ClassLevelAsyncWithUnannotatedHelpers {

        public void execute() {
            privateHelper();
            staticHelper();
            finalHelper();
        }

        private void privateHelper() {}

        static void staticHelper() {}

        public final void finalHelper() {}
    }

    @Service
    private static class MethodRefBean {

        Runnable run() {
            return this::save;
        }

        @Transactional
        public void save() {}
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Configuration(proxyBeanMethods = false)
    private @interface CustomAutoConfiguration {}

    @CustomAutoConfiguration
    private static class AutoConfigBean {

        @Bean
        String first() {
            return second();
        }

        @Bean
        String second() {
            return "two";
        }
    }

    @Configuration
    private static class StaticSiblingConfig {

        @Bean
        String first() {
            return second();
        }

        @Bean
        static String second() {
            return "two";
        }
    }

    @Component
    private static class LiteBeanCallingExternalMethod {

        @Bean
        String value() {
            return String.valueOf(42);
        }
    }

    private static class ExceptionWithStaticFactory extends RuntimeException {

        static ExceptionWithStaticFactory of(String message) {
            return new ExceptionWithStaticFactory();
        }
    }

    private static class InterfaceImplementingStaticHolder implements java.io.Serializable {

        static String helper() {
            return "ok";
        }
    }

    private static class BaseClassWithStaticHelper {

        static String helper() {
            return "ok";
        }
    }

    private static final class SubclassOfBaseWithStaticHelper extends BaseClassWithStaticHelper {

        private final int id = 1;
    }

    private static class DeprecatedApiTarget {

        @Deprecated
        static String legacyOperation() {
            return "legacy";
        }

        @Deprecated(forRemoval = true)
        static String scheduledForRemovalOperation() {
            return "removal";
        }
    }

    @Deprecated
    private static class DeprecatedCallerScope {

        String callFromDeprecatedClass() {
            return DeprecatedApiTarget.legacyOperation();
        }
    }

    @Deprecated
    private static class DeprecatedCallerUsingForRemovalTarget {

        String callRemovalTargetFromDeprecatedClass() {
            return DeprecatedApiTarget.scheduledForRemovalOperation();
        }
    }

    private static class ActiveDeprecatedCaller {

        @Deprecated
        String allowedInDeprecatedMethod() {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    DeprecatedApiTarget.legacyOperation();
                }
            };
            r.run();
            return DeprecatedApiTarget.legacyOperation();
        }

        String flaggedInActiveMethod() {
            return DeprecatedApiTarget.legacyOperation();
        }
    }

    private static class PackagePrivateMainLauncher {

        static void main(String[] args) {
            System.exit(0);
        }
    }

    private static class InstanceMainLauncher {

        void main(String[] args) {
            System.exit(0);
        }
    }

    private static class ZeroArgStaticMainLauncher {

        static void main() {
            System.exit(0);
        }
    }

    private static class ZeroArgInstanceMainLauncher {

        void main() {
            System.exit(0);
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @org.springframework.cache.annotation.Cacheable("composed")
    private @interface ComposedCacheable {}

    @Service
    private static class VoidCacheableComponent {

        @org.springframework.cache.annotation.Cacheable("items")
        public void voidCacheable(String id) {}

        @CachePut("items")
        public Void boxedVoidCachePut(String id) {
            return null;
        }

        @Caching(put = @CachePut("items"), evict = @CacheEvict("other"))
        public void cachingWithPut(String id) {}

        @ComposedCacheable
        public void metaVoidCacheable(String id) {}

        @CacheEvict("items")
        public void evictOnly(String id) {}

        @Caching(evict = @CacheEvict("items"))
        public void cachingEvictOnly(String id) {}

        @org.springframework.cache.annotation.Cacheable("items")
        public String valuedCacheable(String id) {
            return id;
        }

        @org.springframework.cache.annotation.Cacheable("items")
        private void privateMethodCacheable(String id) {}

        @org.springframework.cache.annotation.Cacheable("items")
        public Object javaContinuationValued(kotlin.coroutines.Continuation<? super kotlin.Unit> continuation) {
            return "not-a-kotlin-class";
        }
    }

    @Service
    @org.springframework.cache.annotation.Cacheable("class-items")
    private static class ClassLevelVoidCacheableComponent {

        public void inheritedClassLevelVoid(String id) {}

        @CacheEvict("class-items")
        public void classLevelEvictOverride(String id) {}

        @PostConstruct
        public void classLevelPostConstruct() {}

        private void privateHelper(String id) {}
    }

    @Service
    private static class ValidCacheableComponent {

        @org.springframework.cache.annotation.Cacheable("items")
        public String findItem(String id) {
            return id;
        }

        @CacheEvict("items")
        public void clearItem(String id) {}
    }

    private static class StaticFormatterHolder {

        private static final java.text.SimpleDateFormat STATIC_DATE_FORMAT =
                new java.text.SimpleDateFormat("yyyy-MM-dd");
    }

    @Service
    private static class SharedBeanWithStatefulFormatters {

        private java.text.DateFormat dateFormat;
        private java.text.DecimalFormat decimalFormat;
        private java.text.NumberFormat numberFormat;
        private java.text.MessageFormat messageFormat;
        private javax.xml.parsers.DocumentBuilder documentBuilder;
        private javax.xml.parsers.SAXParser saxParser;
        private javax.xml.transform.Transformer transformer;
        private javax.xml.validation.Validator xmlValidator;
    }

    @Service
    private static class SafeFormatterAndValidatorBean {

        private static final java.time.format.DateTimeFormatter ISO_FORMATTER =
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
        private static final ThreadLocal<java.text.SimpleDateFormat> THREAD_LOCAL_FORMAT =
                ThreadLocal.withInitial(() -> new java.text.SimpleDateFormat("yyyy-MM-dd"));
        private jakarta.validation.Validator beanValidator;
        private javax.xml.parsers.DocumentBuilderFactory documentBuilderFactory;
    }

    @Component
    @org.springframework.context.annotation.Scope("prototype")
    private static class PrototypeFormatterBean {

        private final java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd");
    }

    @Component
    @org.springframework.web.context.annotation.RequestScope
    private static class RequestScopedFormatterBean {

        private final java.text.DecimalFormat decimalFormat = new java.text.DecimalFormat("#0.00");
    }

    @Component
    @org.springframework.context.annotation.Scope("refresh")
    private static class RefreshScopedFormatterBean {

        private java.text.SimpleDateFormat refreshDateFormat;
    }

    private static class BaseClassWithFormatterField {

        protected java.text.SimpleDateFormat inheritedDateFormat;
    }

    @Service
    private static class SharedSubclassInheritingFormatterField extends BaseClassWithFormatterField {}

    private static class UnscopedProducedFormatterHolder {

        private java.text.SimpleDateFormat dateFormat;
    }

    private static class UnscopedCdiProducerHolder {

        @jakarta.enterprise.inject.Produces
        UnscopedProducedFormatterHolder produceDependent() {
            return new UnscopedProducedFormatterHolder();
        }
    }

    private static class PlainUnmanagedFormatterDto {

        private java.text.SimpleDateFormat dateFormat;
    }
}

class TopLevelUnmanagedScheduledTask {

    @Scheduled(fixedRate = 5000)
    void runTask() {}
}

class TopLevelUnmanagedEventListener {

    @EventListener
    void onEvent(String event) {}
}

class TopLevelUnmanagedRunnableScheduledTask implements Runnable {

    @Override
    public void run() {}

    @Scheduled(fixedRate = 5000)
    void runTask() {}
}

@Component
class TopLevelComponentScheduledTask {

    @Scheduled(fixedRate = 5000)
    void runTask() {}
}

abstract class TopLevelAbstractScheduledBase {

    @Scheduled(fixedRate = 5000)
    void runTask() {}
}

class TopLevelScheduledBaseWithSubclass {

    @Scheduled(fixedRate = 5000)
    void runTask() {}
}

@Component
class TopLevelScheduledSubclassBean extends TopLevelScheduledBaseWithSubclass {}

class TopLevelBeanFactoryScheduledTask {

    @Scheduled(fixedRate = 5000)
    void runTask() {}
}

interface TopLevelScheduledTaskContract {}

class TopLevelInterfaceScheduledTaskImpl implements TopLevelScheduledTaskContract {

    @Scheduled(fixedRate = 5000)
    void runTask() {}
}

class TopLevelImportedEventListener {

    @TransactionalEventListener
    void onCommitted(String event) {}
}

@Configuration
@org.springframework.context.annotation.Import(TopLevelImportedEventListener.class)
class TopLevelSchedulerConfiguration {

    @Bean
    TopLevelBeanFactoryScheduledTask scheduledTask() {
        return new TopLevelBeanFactoryScheduledTask();
    }

    @Bean
    TopLevelScheduledTaskContract scheduledContractTask() {
        return new TopLevelInterfaceScheduledTaskImpl();
    }

    @Bean
    Runnable unrelatedRunnableBean() {
        return () -> {};
    }
}
