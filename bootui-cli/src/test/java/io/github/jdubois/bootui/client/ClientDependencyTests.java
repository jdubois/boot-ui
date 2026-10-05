package io.github.jdubois.bootui.client;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The client ships inside {@code bootui-cli} beside the picocli command line, but it is embedded in other
 * people's builds, which depend on {@code bootui-cli} for it and get picocli only as an optional dependency.
 * It must therefore reach nothing outside the JDK: no picocli, no {@code io.github.jdubois.bootui.cli}, no
 * {@code bootui-core} DTOs, no Jackson.
 */
class ClientDependencyTests {

    private static final JavaClasses CLIENT = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.jdubois.bootui.client");

    @Test
    void clientDependsOnTheJdkAndItselfOnly() {
        classes()
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "javax..", "io.github.jdubois.bootui.client")
                .because("tooling depends on bootui-cli for the client and must get no dependency with it")
                .check(CLIENT);
    }

    @Test
    void theImportSeesTheClient() {
        // Guards the rule above against passing vacuously on an empty import.
        assertThat(CLIENT.contain(BootUiClient.class)).isTrue();
        assertThat(CLIENT.contain(JsonReader.class)).isTrue();
    }
}
