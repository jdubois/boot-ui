package io.github.jdubois.bootui.autoconfigure.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.BootUiDtos.ArchitectureRuleResultDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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

    private static ArchitectureRuleResultDto evaluate(ArchitectureRule rule, Class<?>... classes) {
        JavaClasses importedClasses = new ClassFileImporter().importClasses(classes);
        return rule.evaluate(
                new ArchitectureContext(importedClasses, List.of(ArchitectureRulesTests.class.getPackageName())));
    }

    @RestController
    private static class ExampleController {}

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
}
