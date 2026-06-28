package io.github.jdubois.bootui.spi;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * Pins the framework-neutrality of {@code bootui-spi}: its types must never depend on a host-framework
 * or transport API, so both the Spring Boot and Quarkus adapters can implement them. Neutral
 * {@code jakarta.*} contracts ({@code jakarta.persistence}, {@code jakarta.sql}) and Micrometer stay
 * allowed deliberately; only the framework/transport packages below are banned.
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
                    "liquibase..")
            .because("bootui-spi must stay framework-neutral so every host-framework adapter can implement it");
}
