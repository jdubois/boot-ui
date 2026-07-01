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
    private static final int RULE_COUNT = 10;
    private static final String GUIDE = "https://quarkus.io/guides/cdi-reference";
    private static final String CONFIG_GUIDE = "https://quarkus.io/guides/config-reference";
    private static final String REACTIVE_GUIDE = "https://quarkus.io/guides/getting-started-reactive";
    private static final String PROFILE_GUIDE = "https://quarkus.io/guides/config-reference#profiles";
    private static final String HIBERNATE_GUIDE = "https://quarkus.io/guides/hibernate-orm";
    private static final String DATASOURCE_GUIDE = "https://quarkus.io/guides/datasource";
    private static final String SCHEDULER_GUIDE = "https://quarkus.io/guides/scheduler-reference";

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
                            + " mutable state accessed concurrently across requests.",
                    s.publicResourceFields().size(),
                    s.publicResourceFields(),
                    "Make the field private final, inject it, or move per-request state to a @RequestScoped bean.",
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
        if (s.reactiveEndpointCount() > 0 && s.blockingAnnotationCount() == 0 && s.jdbcDatasourcePresent()) {
            v.add(rule(
                    "QA-RX-001",
                    "Reactive endpoints with a blocking JDBC datasource",
                    "Reactive",
                    "INFO",
                    "Endpoints return Uni/Multi (run on the I/O event loop) and a blocking JDBC datasource is"
                            + " configured, yet no @Blocking guard was found; a JDBC call on the event loop stalls it.",
                    s.reactiveEndpointCount(),
                    List.of(s.reactiveEndpointCount() + " reactive endpoint(s), 0 @Blocking, JDBC datasource present"),
                    "Annotate blocking work with @Blocking, or use a reactive datasource client.",
                    REACTIVE_GUIDE));
        }
        if (s.prodDevServicesEnabled()) {
            v.add(rule(
                    "QA-PROD-001",
                    "Dev Services enabled in the prod profile",
                    "Profiles",
                    "HIGH",
                    "A %prod.*devservices.enabled=true key would start throwaway containers in production.",
                    s.prodProfileKeys().size(),
                    List.of("%prod devservices.enabled=true"),
                    "Remove the %prod devservices override; configure a real datasource/broker for prod.",
                    PROFILE_GUIDE));
        }
        if (isDestructiveSchema(s.prodSchemaGeneration())) {
            v.add(rule(
                    "QA-PROD-002",
                    "Destructive Hibernate schema strategy in the prod profile",
                    "Profiles",
                    "HIGH",
                    "A %prod Hibernate schema strategy of drop-and-create/create/drop rebuilds or drops the"
                            + " production schema on every boot, destroying data.",
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
        if (s.activeProfiles().isEmpty() && s.prodProfileKeys().isEmpty()) {
            v.add(rule(
                    "QA-PROF-001",
                    "No profile configuration",
                    "Profiles",
                    "INFO",
                    "No active profile and no %prod. overrides were found. This is fine when production config"
                            + " is externalised (env vars, Secrets/ConfigMaps); otherwise prod shares dev defaults.",
                    1,
                    List.of("no %prod./%dev. keys, no active profile"),
                    "Add %prod. overrides (or externalise config) so production differs from dev defaults.",
                    PROFILE_GUIDE));
        }
        return v;
    }

    private static boolean isDestructiveSchema(String strategy) {
        if (strategy == null) {
            return false;
        }
        String s = strategy.trim().toLowerCase();
        return s.equals("drop-and-create") || s.equals("create") || s.equals("drop");
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
