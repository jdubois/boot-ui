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
 * allowed deliberately; the framework/transport packages, the optional-library packages
 * (Flyway/Liquibase/HikariCP/Agroal/Hibernate ORM — which must stay behind their provider SPIs), and both
 * JSON libraries below are banned. JSON is banned because Spring Boot 4 ships Jackson 3
 * ({@code tools.jackson.*}) while Quarkus ships Jackson 2 ({@code com.fasterxml.jackson.*}) — incompatible
 * artifact <em>and</em> package — so any JSON parsing/serialization belongs in the adapter, which feeds the
 * engine already-parsed neutral records. This is the build-time tripwire for the optional-dependency
 * classloading trap: engine classes must inject already-resolved handles rather than statically importing
 * Spring/CDI/Quarkus/Jackson/optional-library types. (The Hibernate advisor matches Hibernate annotations by
 * <em>string</em> name through Jandex, so it carries no {@code org.hibernate} bytecode dependency.)
 */
@AnalyzeClasses(packages = "io.github.jdubois.bootui.engine", importOptions = DoNotIncludeTests.class)
class EngineBoundaryArchitectureTests {

    @ArchTest
    static final ArchRule engineStaysFreeOfHostFrameworkTypes = ArchRuleDefinition.noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    // host frameworks + transport
                    "org.springframework..",
                    "jakarta.servlet..",
                    "jakarta.ws.rs..",
                    "io.quarkus..",
                    "io.vertx..",
                    "org.jboss..",
                    // optional libraries that must stay adapter-side (R2): the engine works over neutral SPI
                    // carriers (FlywayProvider, LiquibaseProvider, ConnectionPoolProvider, …) and never the
                    // library types themselves
                    "org.flywaydb..",
                    "liquibase..",
                    "com.zaxxer.hikari..",
                    "io.agroal..",
                    "org.hibernate..",
                    // JSON libraries (incompatible across Spring Boot 4 / Quarkus)
                    "tools.jackson..",
                    "com.fasterxml.jackson..")
            .because("bootui-engine must stay framework-neutral; adapters wire it via @Bean / @Produces and "
                    + "feed it already-parsed neutral records (JSON libraries differ across Spring Boot/Quarkus). "
                    + "Optional libraries (Flyway/Liquibase/HikariCP/Agroal/Hibernate ORM) stay behind their "
                    + "provider SPIs so the engine never statically imports a type that may be absent at runtime");

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
            // javac emits for its switch over io.opentelemetry.api.common.AttributeType, plus the two other
            // concentrated OTel-touching enrichment types (the identity SpanProcessor stamped on span start
            // and the capture-time span enricher).
            .and()
            .haveNameNotMatching(".*BootUiSpanExporter(\\$.*)?")
            .and()
            .haveNameNotMatching(".*BootUiIdentitySpanProcessor")
            .and()
            .haveNameNotMatching(".*OtelSpanEnricher")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.opentelemetry.sdk..", "io.opentelemetry.api..", "io.opentelemetry.context..")
            .because("the OpenTelemetry SDK is an optional dependency concentrated in BootUiSpanExporter, "
                    + "BootUiIdentitySpanProcessor and OtelSpanEnricher (R2 optional-dependency port); the rest "
                    + "of the telemetry engine works over neutral NormalizedSpan records so the OTLP-decoding "
                    + "adapter never forces OTel on a consumer");
}
