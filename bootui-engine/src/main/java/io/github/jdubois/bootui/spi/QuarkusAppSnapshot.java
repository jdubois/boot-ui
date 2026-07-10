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
 * @param mutableAppScopedFields mutable {@code @ApplicationScoped} bean fields, excluding known immutable final
 *     value types (shared mutable state)
 * @param configPropertyCount number of {@code @ConfigProperty} injection sites
 * @param endpointCount discovered JAX-RS endpoint methods
 * @param defaultScopeResourceCount JAX-RS resources with no explicit CDI scope
 * @param reactiveEndpointCount endpoints returning {@code Uni}/{@code Multi}
 * @param reactiveEndpointsWithoutBlockingCount reactive ({@code Uni}/{@code Multi}) endpoints whose method and
 *     declaring class both lack {@code @Blocking} — the exact endpoints QA-RX-001 should flag, correlated
 *     per-endpoint rather than against the app's total {@code @Blocking} count
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
 * @param prodLogLevelVerbose whether {@code %prod.quarkus.log.level} resolves to DEBUG/TRACE/ALL
 * @param compressionEnabled whether {@code quarkus.http.enable-compression} is on
 * @param shutdownTimeoutZeroed whether the graceful shutdown grace period was explicitly zeroed
 * @param restClientsRegistered whether any {@code @RegisterRestClient} interface is declared
 * @param restClientTimeoutZeroOrExcessive whether a global or per-client REST client connect/read timeout is
 *     explicitly set to {@code 0} (disabled) or to an excessively high value — Quarkus REST clients already
 *     default to a 15s connect-timeout/30s read-timeout, so merely leaving the timeout unset is not flagged
 * @param virtualThreadEndpointCount {@code @RunOnVirtualThread} sites (methods or classes)
 * @param virtualThreadSynchronizedCount {@code @RunOnVirtualThread} sites that are also {@code synchronized}
 *     (a class-level {@code @RunOnVirtualThread} counts every {@code synchronized} method in that class)
 * @param jdkMajorVersion the build JDK's major version (0 if undetermined)
 * @param mutableSingletonFields mutable {@code @Singleton} bean fields, excluding known immutable final value
 *     types (the same shared-state risk as {@link #mutableAppScopedFields()})
 * @param legacySchemaGenerationPropertyUsed whether the deprecated {@code quarkus.hibernate-orm.database.generation}
 *     property (or a profile/named-persistence-unit variant) is used instead of the current
 *     {@code quarkus.hibernate-orm.schema-management.strategy}
 * @param shutdownTimeoutConfigured whether {@code quarkus.shutdown.timeout} or
 *     {@code quarkus.http.shutdown.timeout} is set at all (to any value, including {@code 0})
 * @param datasourceMaxSizeConfigured whether {@code quarkus.datasource.jdbc.max-size} is explicitly set
 *     (globally or via a {@code %prod} override)
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
        int reactiveEndpointsWithoutBlockingCount,
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
        List<String> publicResourceFields,
        boolean prodLogLevelVerbose,
        boolean compressionEnabled,
        boolean shutdownTimeoutZeroed,
        boolean restClientsRegistered,
        boolean restClientTimeoutZeroOrExcessive,
        int virtualThreadEndpointCount,
        int virtualThreadSynchronizedCount,
        int jdkMajorVersion,
        List<String> mutableSingletonFields,
        boolean legacySchemaGenerationPropertyUsed,
        boolean shutdownTimeoutConfigured,
        boolean datasourceMaxSizeConfigured) {

    public QuarkusAppSnapshot {
        mutableAppScopedFields = mutableAppScopedFields == null ? List.of() : List.copyOf(mutableAppScopedFields);
        activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
        prodProfileKeys = prodProfileKeys == null ? List.of() : List.copyOf(prodProfileKeys);
        prodSchemaGeneration = prodSchemaGeneration == null ? "" : prodSchemaGeneration;
        prodDbKind = prodDbKind == null ? "" : prodDbKind;
        publicResourceFields = publicResourceFields == null ? List.of() : List.copyOf(publicResourceFields);
        mutableSingletonFields = mutableSingletonFields == null ? List.of() : List.copyOf(mutableSingletonFields);
    }

    public int beanCount() {
        return applicationScopedCount + singletonCount + requestScopedCount + dependentScopedCount;
    }
}
