package io.github.jdubois.bootui.engine;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * Pins the framework-neutrality of {@code bootui-engine}: its services must never depend on a
 * host-framework or transport API, so both the Spring Boot and Quarkus adapters can wire them. Neutral
 * {@code jakarta.*} contracts ({@code jakarta.persistence}, {@code jakarta.sql}) and Micrometer stay
 * allowed deliberately; only the framework/transport packages below are banned. This is the build-time
 * tripwire for the optional-dependency classloading trap: engine classes must inject already-resolved
 * handles rather than statically importing Spring/CDI/Quarkus types.
 */
@AnalyzeClasses(packages = "io.github.jdubois.bootui.engine", importOptions = DoNotIncludeTests.class)
class EngineBoundaryArchitectureTests {

    @ArchTest
    static final ArchRule engineStaysFreeOfHostFrameworkTypes = ArchRuleDefinition.noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.servlet..",
                    "jakarta.ws.rs..",
                    "io.quarkus..",
                    "io.vertx..",
                    "org.jboss..")
            .because("bootui-engine must stay framework-neutral; adapters wire it via @Bean / @Produces");
}
