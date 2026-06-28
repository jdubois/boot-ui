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
 * allowed deliberately; only the framework/transport packages below are banned. JSON libraries are banned
 * too: Spring Boot 4 ships Jackson 3 ({@code tools.jackson.*}) while Quarkus ships Jackson 2
 * ({@code com.fasterxml.jackson.*}) — incompatible artifact <em>and</em> package — so any JSON
 * parsing/serialization belongs in the adapter, which feeds the engine already-parsed neutral records.
 * This is the build-time tripwire for the optional-dependency classloading trap: engine classes must
 * inject already-resolved handles rather than statically importing Spring/CDI/Quarkus/Jackson types.
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
                    "org.jboss..",
                    "liquibase..",
                    "tools.jackson..",
                    "com.fasterxml.jackson..")
            .because("bootui-engine must stay framework-neutral; adapters wire it via @Bean / @Produces and "
                    + "feed it already-parsed neutral records (JSON libraries differ across Spring Boot/Quarkus)");

    @ArchTest
    static final ArchRule onlyTheMetamodelReaderTouchesJpa = ArchRuleDefinition.noClasses()
            .that()
            .resideInAPackage("io.github.jdubois.bootui.engine..")
            .and()
            .haveSimpleNameNotEndingWith("JpaMetamodelReader")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("jakarta.persistence..")
            .because("jakarta.persistence access is concentrated in JpaMetamodelReader (R2 optional-dependency port)");

    @ArchTest
    static final ArchRule onlyTheSpanExporterTouchesOpenTelemetry = ArchRuleDefinition.noClasses()
            .that()
            .resideInAPackage("io.github.jdubois.bootui.engine..")
            // Exclude BootUiSpanExporter and the synthetic switch-map class (BootUiSpanExporter$1) that
            // javac emits for its switch over io.opentelemetry.api.common.AttributeType.
            .and()
            .haveNameNotMatching(".*BootUiSpanExporter(\\$.*)?")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.opentelemetry.sdk..", "io.opentelemetry.api..")
            .because("the OpenTelemetry SDK is an optional dependency concentrated in BootUiSpanExporter "
                    + "(R2 optional-dependency port); the rest of the telemetry engine works over neutral "
                    + "NormalizedSpan records so the OTLP-decoding adapter never forces OTel on a consumer");
}
