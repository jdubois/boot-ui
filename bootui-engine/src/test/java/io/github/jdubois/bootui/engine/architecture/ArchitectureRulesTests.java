package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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
    void noStandardStreamsFlagsNoArgPrintStackTraceExclusively() {
        ArchitectureRuleResultDto standardStreamsResult =
                evaluate(new NoStandardStreamsRule(), NoArgPrintStackTraceCaller.class);
        ArchitectureRuleResultDto printStackTraceResult =
                evaluate(new NoPrintStackTraceRule(), NoArgPrintStackTraceCaller.class);

        assertThat(standardStreamsResult.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(standardStreamsResult.id()).isEqualTo("ARCH-CODE-001");
        assertThat(printStackTraceResult.status())
                .as("the no-arg printStackTrace() overload is exclusively covered by ARCH-CODE-001, so"
                        + " ARCH-CODE-005 must not also fire on the same call site")
                .isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void noPrintStackTraceFlagsArgTakingOverloadExclusively() {
        ArchitectureRuleResultDto printStackTraceResult =
                evaluate(new NoPrintStackTraceRule(), WriterArgPrintStackTraceCaller.class);
        ArchitectureRuleResultDto standardStreamsResult =
                evaluate(new NoStandardStreamsRule(), WriterArgPrintStackTraceCaller.class);

        assertThat(printStackTraceResult.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(printStackTraceResult.id()).isEqualTo("ARCH-CODE-005");
        assertThat(standardStreamsResult.status())
                .as("the printStackTrace(PrintWriter) overload is exclusively covered by ARCH-CODE-005, so"
                        + " ARCH-CODE-001 must not also fire on the same call site")
                .isEqualTo(ArchitectureRuleSupport.PASS);
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
        ArchitectureRuleResultDto result = evaluate(new NoTestFrameworkDependenciesRule(), TestFrameworkUser.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-013");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("org.junit"));
    }

    @Test
    void noTestFrameworkDependenciesFlagsMainCodeUsingQuarkusTestApi() {
        ArchitectureRuleResultDto result =
                evaluate(new NoTestFrameworkDependenciesRule(), QuarkusTestFrameworkUser.class);

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
    void proxiedMethodsShouldBePublicAndNonStaticFlagsProtectedMethods() {
        ArchitectureRuleResultDto result =
                evaluate(new ProxiedMethodsShouldNotBePrivateOrStaticRule(), ProtectedProxyAnnotationComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-010");
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("protected"));
    }

    @Test
    void noSelfInvocationFlagsClassLevelAsyncSelfCall() {
        ArchitectureRuleResultDto result =
                evaluate(new NoSelfInvocationOfProxiedMethodsRule(), ClassLevelAsyncBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-004");
    }

    @Test
    void noSelfInvocationDoesNotFlagClassLevelTransactionalSelfCall() {
        ArchitectureRuleResultDto result =
                evaluate(new NoSelfInvocationOfProxiedMethodsRule(), ClassLevelTransactionalBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
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
                        importedClasses, List.of("io.github.jdubois.bootui.engine.architecture.modulefixtures")));

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
    void assertionsShouldHaveDetailMessageFlagsMessagelessAssertion() {
        ArchitectureRuleResultDto result =
                evaluate(new AssertionsShouldHaveDetailMessageRule(), MessagelessAssertionUser.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-018");
        assertThat(result.severity()).isEqualTo("INFO");
    }

    @Test
    void assertionsShouldHaveDetailMessagePassesForMessageBearingAssertion() {
        ArchitectureRuleResultDto result =
                evaluate(new AssertionsShouldHaveDetailMessageRule(), MessageBearingAssertionUser.class);

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

    private static class MessagelessAssertionUser {

        void check(int x) {
            assert x > 0;
        }
    }

    private static class MessageBearingAssertionUser {

        void check(int x) {
            assert x > 0 : "x must be positive";
        }
    }
}
