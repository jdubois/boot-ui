package io.github.jdubois.bootui.spi;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * Pins the framework-neutrality of the BootUI SPI package {@code io.github.jdubois.bootui.spi} (which now
 * lives inside {@code bootui-engine} after the former {@code bootui-spi} module was merged in): its types
 * must never depend on a host-framework or transport API, so both the Spring Boot and Quarkus adapters can
 * implement them. The ban includes both JSON libraries — Spring Boot 4 ships Jackson 3
 * ({@code tools.jackson.*}) while Quarkus ships Jackson 2 ({@code com.fasterxml.jackson.*}), incompatible
 * artifact <em>and</em> package — so SPI signatures carry already-parsed neutral DTOs and each adapter owns
 * its own JSON binding. Neutral {@code jakarta.*} contracts ({@code jakarta.persistence}, {@code jakarta.sql})
 * and Micrometer stay allowed deliberately; only the framework/transport/JSON packages below are banned.
 */
@AnalyzeClasses(packages = "io.github.jdubois.bootui.spi", importOptions = DoNotIncludeTests.class)
class SpiBoundaryArchitectureTests {

    @ArchTest
    static final ArchRule spiStaysFreeOfHostFrameworkTypes = ArchRuleDefinition.noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.servlet..",
                    "jakarta.ws.rs..",
                    "io.quarkus..",
                    "io.vertx..",
                    "org.jboss..",
                    "liquibase..",
                    "tools.jackson..",
                    "com.fasterxml.jackson..")
            .because("bootui-spi must stay framework-neutral and JSON-free so every host-framework "
                    + "adapter can implement it and bind its own JSON library");
}
