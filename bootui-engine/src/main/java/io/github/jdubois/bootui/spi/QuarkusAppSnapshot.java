package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral snapshot of a Quarkus application's effective idioms, collected by the Quarkus adapter
 * from MicroProfile config plus build-time annotation/signature counts. Consumed by the engine
 * {@code QuarkusAppScanner} to evaluate the Quarkus-native advisor ruleset (see {@code docs/QUARKUS-ADVISOR-CHECKS.md}).
 *
 * <p>This carries only neutral values (booleans, strings, counts, and lists) so it never leaks an
 * {@code io.quarkus.*} type into the engine. All fields fail safe: an unknown value is rendered as absent
 * (zero / {@code false}) rather than throwing. It is the Quarkus counterpart to the runtime-context snapshot
 * the Spring advisor reads from its application context.</p>
 *
 * @param applicationScopedCount number of {@code @ApplicationScoped} app beans
 * @param singletonCount number of {@code @Singleton} app beans
 * @param requestScopedCount number of {@code @RequestScoped} app beans
 * @param dependentScopedCount number of explicit {@code @Dependent} app beans
 * @param mutableAppScopedFields {@code @ApplicationScoped} bean fields that are public or non-final (shared mutable state)
 * @param configPropertyCount number of {@code @ConfigProperty} injection sites
 * @param endpointCount discovered JAX-RS endpoint methods
 * @param defaultScopeResourceCount JAX-RS resources with no explicit CDI scope
 * @param reactiveEndpointCount endpoints returning {@code Uni}/{@code Multi}
 * @param blockingAnnotationCount {@code @Blocking} sites
 * @param scheduledCount {@code @Scheduled} methods
 * @param activeProfiles the SmallRye active profiles
 * @param prodProfileKeys the {@code %prod.*} config keys present
 * @param prodDevServicesEnabled whether a {@code %prod.*devservices.enabled=true} key is present
 * @param nativeBuild whether the snapshot was taken during a native build
 * @param configMappingCount number of {@code @ConfigMapping} interfaces (type-safe config groups)
 * @param jdbcDatasourcePresent whether a JDBC datasource (db-kind or jdbc url) is configured
 * @param prodSchemaGeneration effective {@code %prod} Hibernate schema strategy (normalised, may be empty)
 * @param prodDbKind the {@code %prod.quarkus.datasource.db-kind} value (may be empty)
 * @param prodJdbcUrlInMemory whether a {@code %prod} JDBC url points at an in-memory database
 * @param prodSqlLoggingEnabled whether {@code %prod.quarkus.hibernate-orm.log.sql=true}
 * @param clusteredScheduler whether a clustered (Quartz) scheduler is configured
 * @param publicResourceFields public, non-final instance fields on JAX-RS resource classes (shared mutable state)
 */
public record QuarkusAppSnapshot(
        int applicationScopedCount,
        int singletonCount,
        int requestScopedCount,
        int dependentScopedCount,
        List<String> mutableAppScopedFields,
        int configPropertyCount,
        int endpointCount,
        int defaultScopeResourceCount,
        int reactiveEndpointCount,
        int blockingAnnotationCount,
        int scheduledCount,
        List<String> activeProfiles,
        List<String> prodProfileKeys,
        boolean prodDevServicesEnabled,
        boolean nativeBuild,
        int configMappingCount,
        boolean jdbcDatasourcePresent,
        String prodSchemaGeneration,
        String prodDbKind,
        boolean prodJdbcUrlInMemory,
        boolean prodSqlLoggingEnabled,
        boolean clusteredScheduler,
        List<String> publicResourceFields) {

    public QuarkusAppSnapshot {
        mutableAppScopedFields = mutableAppScopedFields == null ? List.of() : List.copyOf(mutableAppScopedFields);
        activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
        prodProfileKeys = prodProfileKeys == null ? List.of() : List.copyOf(prodProfileKeys);
        prodSchemaGeneration = prodSchemaGeneration == null ? "" : prodSchemaGeneration;
        prodDbKind = prodDbKind == null ? "" : prodDbKind;
        publicResourceFields = publicResourceFields == null ? List.of() : List.copyOf(publicResourceFields);
    }

    public int beanCount() {
        return applicationScopedCount + singletonCount + requestScopedCount + dependentScopedCount;
    }
}
