package io.github.jdubois.bootui.engine.quarkusapp;

import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixed Quarkus-native application advisor ruleset (see {@code docs/QUARKUS-ADVISOR-CHECKS.md}). Each rule
 * inspects the neutral {@link QuarkusAppSnapshot} and, when triggered, emits one {@link SpringRuleResultDto}
 * with status {@code VIOLATION}. The full set evaluated is {@link #ruleCount()}; only violations are returned.
 * All signals are build-time computable (CDI scopes, {@code @ConfigProperty}, JAX-RS signatures, profiles) —
 * none require live runtime state or the network.
 */
final class QuarkusAppChecks {

    private static final String VIOLATION = "VIOLATION";
    private static final int RULE_COUNT = 20;
    private static final String GUIDE = "https://quarkus.io/guides/cdi-reference";
    private static final String CONFIG_GUIDE = "https://quarkus.io/guides/config-reference";
    private static final String REACTIVE_GUIDE = "https://quarkus.io/guides/getting-started-reactive";
    private static final String PROFILE_GUIDE = "https://quarkus.io/guides/config-reference#profiles";
    private static final String HIBERNATE_GUIDE = "https://quarkus.io/guides/hibernate-orm";
    private static final String DATASOURCE_GUIDE = "https://quarkus.io/guides/datasource";
    private static final String SCHEDULER_GUIDE = "https://quarkus.io/guides/scheduler-reference";
    private static final String LOGGING_GUIDE = "https://quarkus.io/guides/logging";
    private static final String HTTP_GUIDE = "https://quarkus.io/guides/http-reference";
    private static final String REST_CLIENT_GUIDE = "https://quarkus.io/guides/rest-client";
    private static final String VIRTUAL_THREADS_GUIDE = "https://quarkus.io/guides/virtual-threads";

    private QuarkusAppChecks() {}

    static int ruleCount() {
        return RULE_COUNT;
    }

    static List<SpringRuleResultDto> evaluate(QuarkusAppSnapshot s) {
        List<SpringRuleResultDto> v = new ArrayList<>();

        if (!s.mutableAppScopedFields().isEmpty()) {
            v.add(rule(
                    "QA-CDI-001",
                    "Shared mutable state on @ApplicationScoped bean",
                    "CDI",
                    "MEDIUM",
                    "@ApplicationScoped beans are single instances shared across threads; public or non-final"
                            + " fields (other than injected dependencies) hold unsynchronised shared state.",
                    s.mutableAppScopedFields().size(),
                    s.mutableAppScopedFields(),
                    "Make fields private final, or move per-request state to a @RequestScoped bean.",
                    GUIDE));
        }
        if (!s.publicResourceFields().isEmpty()) {
            v.add(rule(
                    "QA-CDI-002",
                    "Public mutable field on a JAX-RS resource",
                    "CDI",
                    "MEDIUM",
                    "JAX-RS resources default to @Singleton, so a public non-final field is process-wide shared"
                            + " mutable state accessed concurrently across requests. (A resource explicitly"
                            + " annotated @RequestScoped gets a fresh instance per request and is excluded.)",
                    s.publicResourceFields().size(),
                    s.publicResourceFields(),
                    "Make the field private final, inject it, or move per-request state to a @RequestScoped bean.",
                    GUIDE));
        }
        if (!s.mutableSingletonFields().isEmpty()) {
            v.add(rule(
                    "QA-CDI-003",
                    "Shared mutable state on a @Singleton bean",
                    "CDI",
                    "MEDIUM",
                    "@Singleton beans are a single instance shared across threads, exactly like"
                            + " @ApplicationScoped; public or non-final fields (other than injected dependencies)"
                            + " hold unsynchronised shared state.",
                    s.mutableSingletonFields().size(),
                    s.mutableSingletonFields(),
                    "Make fields private final, or move per-request state to a @RequestScoped bean.",
                    GUIDE));
        }
        if (s.beanCount() > 0 && s.configPropertyCount() == 0 && s.configMappingCount() == 0) {
            v.add(rule(
                    "QA-CFG-001",
                    "No type-safe configuration",
                    "Config",
                    "LOW",
                    "The app declares no @ConfigProperty injection sites and no @ConfigMapping interfaces,"
                            + " suggesting configuration is read ad hoc rather than through type-safe MicroProfile Config.",
                    1,
                    List.of("0 @ConfigProperty sites, 0 @ConfigMapping interfaces"),
                    "Inject configuration with @ConfigProperty or a @ConfigMapping interface.",
                    CONFIG_GUIDE));
        }
        if (s.reactiveEndpointsWithoutBlockingCount() > 0 && s.jdbcDatasourcePresent()) {
            v.add(rule(
                    "QA-RX-001",
                    "Reactive endpoints with a blocking JDBC datasource",
                    "Reactive",
                    "HIGH",
                    "Endpoint(s) return Uni/Multi/CompletionStage/CompletableFuture/Publisher (run on the I/O"
                            + " event loop), lack a @Blocking or @Transactional guard on the method or resource"
                            + " class, and a blocking JDBC datasource is configured; a JDBC call on the event loop"
                            + " stalls it and can throw BlockingOperationNotAllowedException at runtime.",
                    s.reactiveEndpointsWithoutBlockingCount(),
                    List.of(s.reactiveEndpointsWithoutBlockingCount()
                            + " reactive endpoint(s) without @Blocking/@Transactional, JDBC datasource present"),
                    "Annotate blocking work with @Blocking (Quarkus also treats @Transactional as blocking), or"
                            + " use a reactive datasource client.",
                    REACTIVE_GUIDE));
        }
        if (s.prodDevServicesEnabled()) {
            v.add(rule(
                    "QA-PROD-001",
                    "Dev Services override present in the prod profile",
                    "Profiles",
                    "LOW",
                    "A %prod.*devservices.enabled=true key is set. This has no effect in a packaged production"
                            + " build — Dev Services only runs during augmentation/dev/test, never in a"
                            + " LaunchMode.NORMAL packaged JAR or native executable — but its presence usually"
                            + " means leftover or copy-pasted config that should be cleaned up.",
                    s.prodProfileKeys().size(),
                    List.of("%prod devservices.enabled=true"),
                    "Remove the unused %prod devservices override; it does not start containers in production"
                            + " but can confuse readers of the config.",
                    PROFILE_GUIDE));
        }
        String prodSchemaSeverity = destructiveSchemaSeverity(s.prodSchemaGeneration());
        if (prodSchemaSeverity != null) {
            v.add(rule(
                    "QA-PROD-002",
                    "Destructive Hibernate schema strategy in the prod profile",
                    "Profiles",
                    prodSchemaSeverity,
                    prodSchemaSeverity.equals("CRITICAL")
                            ? "A %prod Hibernate schema strategy of drop-and-create/create/drop rebuilds or"
                                    + " drops the production schema on every boot, destroying data."
                            : "A %prod Hibernate schema strategy of update lets Hibernate silently alter the"
                                    + " production schema on every boot (adding/changing columns or tables to"
                                    + " match the entity model), which can lock tables or apply an unreviewed"
                                    + " structural change directly to production.",
                    1,
                    List.of("%prod quarkus.hibernate-orm schema strategy=" + s.prodSchemaGeneration()),
                    "Use 'none' (or 'validate') in %prod and manage the schema with Flyway/Liquibase.",
                    HIBERNATE_GUIDE));
        }
        if (isInMemoryProdDatasource(s)) {
            v.add(rule(
                    "QA-PROD-003",
                    "In-memory/dev datasource in the prod profile",
                    "Profiles",
                    "MEDIUM",
                    "The %prod datasource targets an in-memory/embedded database (H2/HSQLDB/Derby), so production"
                            + " data is lost on restart and never shared across instances.",
                    1,
                    List.of("%prod datasource db-kind="
                            + (s.prodDbKind().isBlank() ? "(in-memory jdbc url)" : s.prodDbKind())),
                    "Point %prod at a real managed database (PostgreSQL, MySQL, …).",
                    DATASOURCE_GUIDE));
        }
        if (s.jdbcDatasourcePresent() && !s.datasourceMaxSizeConfigured()) {
            v.add(rule(
                    "QA-DB-001",
                    "JDBC datasource without an explicit pool size",
                    "Database",
                    "LOW",
                    "A JDBC datasource is configured, but quarkus.datasource.jdbc.max-size is never set (Agroal"
                            + " defaults to a max pool size of 50). Under high concurrency — especially with"
                            + " virtual threads increasing request parallelism — the default pool can become a"
                            + " bottleneck or exhaust the database's own connection limit.",
                    1,
                    List.of("quarkus.datasource.jdbc.max-size not set (Agroal default: 50)"),
                    "Set quarkus.datasource.jdbc.max-size (with a %prod override if it should differ from dev)"
                            + " to a value sized for the target database and expected concurrency.",
                    DATASOURCE_GUIDE));
        }
        if (s.prodSqlLoggingEnabled()) {
            v.add(rule(
                    "QA-CFG-002",
                    "Hibernate SQL logging enabled in the prod profile",
                    "Config",
                    "MEDIUM",
                    "%prod.quarkus.hibernate-orm.log.sql=true logs every statement in production, hurting"
                            + " performance and risking sensitive data in logs.",
                    1,
                    List.of("%prod quarkus.hibernate-orm.log.sql=true"),
                    "Disable SQL logging in %prod; enable it only in %dev when debugging.",
                    HIBERNATE_GUIDE));
        }
        if (s.prodLogLevelVerbose()) {
            v.add(rule(
                    "QA-CFG-003",
                    "Verbose log level in the prod profile",
                    "Config",
                    "MEDIUM",
                    "%prod.quarkus.log.level resolves to DEBUG/TRACE/ALL, far more verbose than production"
                            + " needs; it hurts performance and risks leaking sensitive data into logs.",
                    1,
                    List.of("%prod quarkus.log.level=DEBUG/TRACE/ALL"),
                    "Set %prod.quarkus.log.level to INFO or WARN; use DEBUG/TRACE only in %dev.",
                    LOGGING_GUIDE));
        }
        if (s.legacySchemaGenerationPropertyUsed()) {
            v.add(rule(
                    "QA-CFG-004",
                    "Legacy Hibernate schema-generation property in use",
                    "Config",
                    "LOW",
                    "quarkus.hibernate-orm.database.generation (or a %profile/named-persistence-unit variant)"
                            + " is deprecated for removal in favour of"
                            + " quarkus.hibernate-orm.schema-management.strategy; it still works today but may"
                            + " be removed in a future Quarkus release.",
                    1,
                    List.of("quarkus.hibernate-orm.database.generation present"),
                    "Migrate to quarkus.hibernate-orm.schema-management.strategy (it accepts the same values:"
                            + " none/create/drop-and-create/drop/update/validate).",
                    HIBERNATE_GUIDE));
        }
        if (s.scheduledCount() > 0 && !s.clusteredScheduler()) {
            v.add(rule(
                    "QA-SCH-001",
                    "Scheduled tasks without a clustered scheduler",
                    "Scheduling",
                    "LOW",
                    "@Scheduled methods run on every instance; without a clustered scheduler each replica fires"
                            + " the job, causing duplicate work in a scaled-out deployment.",
                    s.scheduledCount(),
                    List.of(s.scheduledCount() + " @Scheduled method(s), no clustered scheduler"),
                    "Use the Quartz extension with quarkus.quartz.clustered=true, or confirm single-instance"
                            + " deployment.",
                    SCHEDULER_GUIDE));
        }
        if (s.prodProfileKeys().isEmpty()) {
            v.add(rule(
                    "QA-PROF-001",
                    "No prod-specific configuration overrides",
                    "Profiles",
                    "INFO",
                    "No %prod. overrides were found. This is fine when production config is externalised (env"
                            + " vars, Secrets/ConfigMaps); otherwise prod shares dev defaults for every setting.",
                    1,
                    List.of("no %prod. keys found"),
                    "Add %prod. overrides (or externalise config) so production differs from dev defaults.",
                    PROFILE_GUIDE));
        }
        if (!s.compressionEnabled()) {
            v.add(rule(
                    "QA-WEB-001",
                    "HTTP response compression disabled",
                    "Web",
                    "INFO",
                    "quarkus.http.enable-compression is not set (Quarkus's own default), so responses are not"
                            + " gzip/deflate-compressed, increasing bandwidth and latency for text-heavy payloads.",
                    1,
                    List.of("quarkus.http.enable-compression not set"),
                    "Set quarkus.http.enable-compression=true (tune quarkus.http.compress-media-types if needed).",
                    HTTP_GUIDE));
        }
        if (s.shutdownTimeoutZeroed()) {
            v.add(rule(
                    "QA-WEB-002",
                    "Graceful shutdown grace period zeroed",
                    "Web",
                    "MEDIUM",
                    "quarkus.shutdown.timeout or quarkus.http.shutdown.timeout is explicitly set to 0, disabling"
                            + " the graceful-shutdown grace period; in-flight requests are dropped instead of"
                            + " being allowed to complete on SIGTERM.",
                    1,
                    List.of("shutdown timeout=0"),
                    "Remove the override (or set a positive duration) so in-flight requests can drain before"
                            + " shutdown.",
                    HTTP_GUIDE));
        }
        if (!s.shutdownTimeoutConfigured()) {
            v.add(rule(
                    "QA-WEB-004",
                    "Graceful shutdown timeout never configured",
                    "Web",
                    "LOW",
                    "Neither quarkus.shutdown.timeout nor quarkus.http.shutdown.timeout is set. Quarkus's"
                            + " graceful shutdown is opt-in: with no timeout configured, the application exits"
                            + " immediately on SIGTERM instead of draining in-flight requests.",
                    1,
                    List.of("no shutdown timeout configured"),
                    "Set quarkus.shutdown.timeout to a positive duration (e.g. 10s) so in-flight requests can"
                            + " drain before shutdown.",
                    HTTP_GUIDE));
        }
        if (s.restClientsRegistered() && s.restClientTimeoutZeroOrExcessive()) {
            v.add(rule(
                    "QA-WEB-003",
                    "REST client connect/read timeout disabled or excessive",
                    "Web",
                    "MEDIUM",
                    "A connect-timeout or read-timeout for a @RegisterRestClient interface is explicitly set to"
                            + " 0 (no timeout) or to an excessively high value (over 5 minutes). Quarkus REST"
                            + " clients already default to a 15s connect-timeout and 30s read-timeout"
                            + " (quarkus.rest-client.connect-timeout / read-timeout), so a slow/hanging remote"
                            + " service is normally bounded; this override removes that safety net.",
                    1,
                    List.of("REST client connect-timeout/read-timeout explicitly 0 or > 5m"),
                    "Remove the override to keep Quarkus's 15s/30s defaults, or set a specific, bounded timeout"
                            + " appropriate for the remote service.",
                    REST_CLIENT_GUIDE));
        }
        if (s.endpointCount() > 0 && s.virtualThreadEndpointCount() == 0 && s.jdkMajorVersion() >= 21) {
            v.add(rule(
                    "QA-PERF-001",
                    "No virtual-thread adoption",
                    "Performance",
                    "INFO",
                    "The app declares " + s.endpointCount() + " JAX-RS endpoint(s) but none use"
                            + " @RunOnVirtualThread. If any perform blocking I/O (JDBC, file access, blocking"
                            + " REST calls), running them on virtual threads can improve throughput without"
                            + " sizing a worker thread pool.",
                    s.endpointCount(),
                    List.of(s.endpointCount() + " JAX-RS endpoint(s), 0 @RunOnVirtualThread"),
                    "Annotate blocking I/O-bound endpoint methods (or the resource class) with @RunOnVirtualThread.",
                    VIRTUAL_THREADS_GUIDE));
        }
        if (s.virtualThreadSynchronizedCount() > 0 && s.jdkMajorVersion() >= 21 && s.jdkMajorVersion() < 24) {
            v.add(rule(
                    "QA-PERF-002",
                    "Virtual-thread pinning via synchronized (JEP 491)",
                    "Performance",
                    "HIGH",
                    s.virtualThreadSynchronizedCount() + " @RunOnVirtualThread method(s) are also declared"
                            + " synchronized. On JDK " + s.jdkMajorVersion() + " (21-23), entering a synchronized"
                            + " method pins the carrier thread instead of yielding it, defeating the scalability"
                            + " benefit of virtual threads; JEP 491 removes this pinning starting in JDK 24.",
                    s.virtualThreadSynchronizedCount(),
                    List.of(s.virtualThreadSynchronizedCount() + " @RunOnVirtualThread synchronized method(s), JDK "
                            + s.jdkMajorVersion()),
                    "Replace synchronized with a java.util.concurrent.locks.ReentrantLock, or upgrade to JDK 24+.",
                    VIRTUAL_THREADS_GUIDE));
        }
        return v;
    }

    /**
     * Severity of a {@code %prod} Hibernate schema strategy, mirroring the sibling Hibernate advisor's
     * {@code HIB-CONFIG-002} split: {@code drop-and-create}/{@code create}/{@code drop} rebuild or drop the
     * schema outright (CRITICAL), while {@code update} silently alters it in place (HIGH). Returns {@code
     * null} when the strategy is not a destructive one (e.g. {@code none}/{@code validate}/unset).
     */
    private static String destructiveSchemaSeverity(String strategy) {
        if (strategy == null) {
            return null;
        }
        String s = strategy.trim().toLowerCase();
        if (s.equals("drop-and-create") || s.equals("create") || s.equals("drop")) {
            return "CRITICAL";
        }
        if (s.equals("update")) {
            return "HIGH";
        }
        return null;
    }

    private static boolean isInMemoryProdDatasource(QuarkusAppSnapshot s) {
        String kind = s.prodDbKind() == null ? "" : s.prodDbKind().trim().toLowerCase();
        return s.prodJdbcUrlInMemory() || kind.equals("h2") || kind.equals("hsqldb") || kind.equals("derby");
    }

    private static SpringRuleResultDto rule(
            String id,
            String name,
            String category,
            String severity,
            String description,
            int count,
            List<String> samples,
            String recommendation,
            String learnMore) {
        return new SpringRuleResultDto(
                id, name, category, severity, description, VIOLATION, count, samples, recommendation, learnMore);
    }
}
