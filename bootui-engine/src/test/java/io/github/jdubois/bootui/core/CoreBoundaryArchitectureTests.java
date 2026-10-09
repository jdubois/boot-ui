package io.github.jdubois.bootui.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMembers;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.jdubois.bootui.core.dto.OverviewDto;
import org.junit.jupiter.api.Test;

/**
 * Pins the package boundary that replaced the former {@code bootui-core} module: {@code io.github.jdubois.bootui.core}
 * lives inside {@code bootui-engine}, but the engine, the SPI, and the adapters still depend on it, never the other
 * way round. Its classes may reach only the JDK and each other, so the DTO/wire contract stays free of the engine,
 * the SPI, any host framework, and either Jackson generation, and serializes identically on Spring Boot's Jackson 3
 * and Quarkus' Jackson 2.
 */
class CoreBoundaryArchitectureTests {

    private static final String CORE = "io.github.jdubois.bootui.core..";

    private static final DescribedPredicate<JavaAnnotation<?>> ANY_ANNOTATION =
            DescribedPredicate.describe("any annotation", annotation -> true);

    private static final JavaClasses CORE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.jdubois.bootui.core");

    @Test
    void coreDependsOnTheJdkAndItselfOnly() {
        classes()
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage("java..", CORE)
                .because("the core package is the framework-neutral DTO/wire contract at the bottom of"
                        + " core <- engine and SPI <- adapters; it must not reach the engine, the SPI, an adapter,"
                        + " or any external library")
                .check(CORE_CLASSES);
    }

    @Test
    void dtosCarryNoAnnotations() {
        noClasses()
                .that()
                .resideInAPackage("io.github.jdubois.bootui.core.dto..")
                .should()
                .beAnnotatedWith(ANY_ANNOTATION)
                .because("DTO records are annotation-free so Jackson 3 and Jackson 2 serialize them identically")
                .check(CORE_CLASSES);
        noMembers()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage("io.github.jdubois.bootui.core.dto..")
                .should()
                .beAnnotatedWith(ANY_ANNOTATION)
                .because("DTO records are annotation-free so Jackson 3 and Jackson 2 serialize them identically")
                .check(CORE_CLASSES);
    }

    @Test
    void theImportSeesCoreAndOnlyCore() {
        // Guards the rules above against passing vacuously on an empty or test-polluted import.
        assertThat(CORE_CLASSES.contain(SecretMasker.class)).isTrue();
        assertThat(CORE_CLASSES.contain(BootUiInfo.class)).isTrue();
        assertThat(CORE_CLASSES.contain(OverviewDto.class)).isTrue();
        assertThat(CORE_CLASSES.contain(CoreBoundaryArchitectureTests.class)).isFalse();
        assertThat(CORE_CLASSES.stream().map(javaClass -> javaClass.getPackageName()))
                .allMatch(packageName -> packageName.startsWith("io.github.jdubois.bootui.core"));
    }
}
