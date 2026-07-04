package io.github.jdubois.bootui.quarkus.quarkusapp;

import io.github.jdubois.bootui.spi.QuarkusAppSnapshot;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshotProvider;
import io.smallrye.config.SmallRyeConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.Config;

/**
 * Quarkus adapter that reads live CDI/JAX-RS/config idiom counts — captured at build time by the deployment
 * processor and surfaced as {@code bootui.internal.app.*} runtime defaults — plus active/{@code %prod.}
 * profile keys from MicroProfile {@link Config}, into a neutral {@link QuarkusAppSnapshot} for the engine
 * {@code QuarkusAppScanner}. Fails safe: any unreadable value is treated as absent. Never exposes config
 * values — only profile key names.
 */
public class QuarkusAppSnapshotProviderImpl implements QuarkusAppSnapshotProvider {

    static final String APP_SCOPED_KEY = "bootui.internal.app.application-scoped";
    static final String SINGLETON_KEY = "bootui.internal.app.singleton";
    static final String REQUEST_SCOPED_KEY = "bootui.internal.app.request-scoped";
    static final String DEPENDENT_KEY = "bootui.internal.app.dependent";
    static final String MUTABLE_FIELDS_KEY = "bootui.internal.app.mutable-fields";
    static final String MUTABLE_SINGLETON_FIELDS_KEY = "bootui.internal.app.mutable-singleton-fields";
    static final String CONFIG_PROPERTY_KEY = "bootui.internal.app.config-property";
    static final String ENDPOINTS_KEY = "bootui.internal.app.endpoints";
    static final String DEFAULT_SCOPE_RESOURCES_KEY = "bootui.internal.app.default-scope-resources";
    static final String REACTIVE_ENDPOINTS_KEY = "bootui.internal.app.reactive-endpoints";
    static final String REACTIVE_ENDPOINTS_WITHOUT_BLOCKING_KEY =
            "bootui.internal.app.reactive-endpoints-without-blocking";
    static final String BLOCKING_KEY = "bootui.internal.app.blocking";
    static final String SCHEDULED_KEY = "bootui.internal.app.scheduled";
    static final String CONFIG_MAPPING_KEY = "bootui.internal.app.config-mapping";
    static final String PUBLIC_RESOURCE_FIELDS_KEY = "bootui.internal.app.public-resource-fields";
    static final String REST_CLIENTS_KEY = "bootui.internal.app.rest-clients";
    static final String VIRTUAL_THREAD_ENDPOINTS_KEY = "bootui.internal.app.virtual-thread-endpoints";
    static final String VIRTUAL_THREAD_SYNCHRONIZED_KEY = "bootui.internal.app.virtual-thread-synchronized";
    static final String JDK_MAJOR_VERSION_KEY = "bootui.internal.app.jdk-major-version";

    /**
     * A REST client connect/read timeout at or above this bound (5 minutes) is treated as "excessive" for
     * QA-WEB-003 — long enough that it provides essentially no protection against a hanging remote call,
     * even though it is technically a finite value rather than the more obviously dangerous {@code 0}.
     */
    private static final long EXCESSIVE_REST_CLIENT_TIMEOUT_MS = 300_000L;

    private final Config config;

    public QuarkusAppSnapshotProviderImpl(Config config) {
        this.config = config;
    }

    @Override
    public QuarkusAppSnapshot snapshot() {
        List<String> prodKeys = new ArrayList<>();
        boolean prodDevServices = false;
        for (String name : config.getPropertyNames()) {
            if (name.startsWith("%prod.")) {
                prodKeys.add(name);
                if (name.contains("devservices.enabled") && bool(name)) {
                    prodDevServices = true;
                }
            }
        }
        return new QuarkusAppSnapshot(
                count(APP_SCOPED_KEY),
                count(SINGLETON_KEY),
                count(REQUEST_SCOPED_KEY),
                count(DEPENDENT_KEY),
                strList(MUTABLE_FIELDS_KEY),
                count(CONFIG_PROPERTY_KEY),
                count(ENDPOINTS_KEY),
                count(DEFAULT_SCOPE_RESOURCES_KEY),
                count(REACTIVE_ENDPOINTS_KEY),
                count(REACTIVE_ENDPOINTS_WITHOUT_BLOCKING_KEY),
                count(BLOCKING_KEY),
                count(SCHEDULED_KEY),
                activeProfiles(),
                prodKeys,
                prodDevServices,
                false,
                count(CONFIG_MAPPING_KEY),
                jdbcDatasourcePresent(),
                prodSchemaGeneration(),
                str("%prod.quarkus.datasource.db-kind", "").toLowerCase(),
                prodJdbcUrlInMemory(),
                bool("%prod.quarkus.hibernate-orm.log.sql"),
                bool("quarkus.quartz.clustered"),
                strList(PUBLIC_RESOURCE_FIELDS_KEY),
                prodLogLevelVerbose(),
                bool("quarkus.http.enable-compression"),
                shutdownTimeoutZeroed(),
                count(REST_CLIENTS_KEY) > 0,
                restClientTimeoutZeroOrExcessive(),
                count(VIRTUAL_THREAD_ENDPOINTS_KEY),
                count(VIRTUAL_THREAD_SYNCHRONIZED_KEY),
                count(JDK_MAJOR_VERSION_KEY),
                strList(MUTABLE_SINGLETON_FIELDS_KEY),
                legacySchemaGenerationPropertyUsed(),
                shutdownTimeoutConfigured(),
                datasourceMaxSizeConfigured());
    }

    private boolean prodLogLevelVerbose() {
        String level = str("%prod.quarkus.log.level", "").trim().toUpperCase();
        return level.equals("DEBUG") || level.equals("TRACE") || level.equals("ALL");
    }

    private boolean shutdownTimeoutZeroed() {
        return isZero("quarkus.shutdown.timeout") || isZero("quarkus.http.shutdown.timeout");
    }

    private boolean shutdownTimeoutConfigured() {
        return has("quarkus.shutdown.timeout") || has("quarkus.http.shutdown.timeout");
    }

    private boolean isZero(String key) {
        return config.getOptionalValue(key, Duration.class).map(d -> d.isZero()).orElse(false);
    }

    /**
     * QA-WEB-003: unlike the presence-only check this replaces, this only fires on an <em>explicit</em>
     * footgun — a connect/read timeout set to {@code 0} (disabled) or an excessively high value. Quarkus REST
     * clients already default to a 15s connect-timeout / 30s read-timeout ({@code RestClientsConfig}), so
     * merely leaving the property unset is safe and intentionally not flagged.
     */
    private boolean restClientTimeoutZeroOrExcessive() {
        for (String name : config.getPropertyNames()) {
            if (name.startsWith("quarkus.rest-client")
                    && (name.endsWith("connect-timeout") || name.endsWith("read-timeout"))) {
                Long ms = config.getOptionalValue(name, Long.class).orElse(null);
                if (ms != null && (ms == 0L || ms > EXCESSIVE_REST_CLIENT_TIMEOUT_MS)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean jdbcDatasourcePresent() {
        return has("quarkus.datasource.db-kind")
                || has("quarkus.datasource.jdbc.url")
                || has("%prod.quarkus.datasource.db-kind")
                || has("%prod.quarkus.datasource.jdbc.url");
    }

    private String prodSchemaGeneration() {
        String v = config.getOptionalValue("%prod.quarkus.hibernate-orm.schema-management.strategy", String.class)
                .orElseGet(
                        () -> config.getOptionalValue("%prod.quarkus.hibernate-orm.database.generation", String.class)
                                .orElse(""));
        v = v.trim().toLowerCase();
        return v.equals("create-drop") ? "drop-and-create" : v;
    }

    private boolean prodJdbcUrlInMemory() {
        String url = str("%prod.quarkus.datasource.jdbc.url", "").toLowerCase();
        return url.contains(":mem:")
                || url.contains("h2:mem")
                || url.contains("hsqldb:mem")
                || url.contains("derby:memory");
    }

    /**
     * QA-CFG-004: {@code quarkus.hibernate-orm.database.generation} is {@code @Deprecated(forRemoval = true)}
     * as of Quarkus 3.22, in favour of {@code quarkus.hibernate-orm.schema-management.strategy}. Matches any
     * profile (bare, {@code %prod.}/{@code %dev.}/…) or named-persistence-unit variant, since the property
     * name — not just its {@code %prod} use — is what is being flagged as legacy.
     */
    private boolean legacySchemaGenerationPropertyUsed() {
        for (String name : config.getPropertyNames()) {
            if (name.endsWith("database.generation")) {
                return true;
            }
        }
        return false;
    }

    /** QA-DB-001: the default (unnamed) datasource's own max pool size, checked bare or under {@code %prod}. */
    private boolean datasourceMaxSizeConfigured() {
        return has("quarkus.datasource.jdbc.max-size") || has("%prod.quarkus.datasource.jdbc.max-size");
    }

    private List<String> activeProfiles() {
        try {
            return List.copyOf(config.unwrap(SmallRyeConfig.class).getProfiles());
        } catch (RuntimeException notSmallRye) {
            return List.of();
        }
    }

    private List<String> strList(String key) {
        String v = config.getOptionalValue(key, String.class).orElse("");
        if (v.isBlank()) {
            return List.of();
        }
        return List.of(v.split(","));
    }

    private boolean has(String key) {
        return config.getOptionalValue(key, String.class)
                .filter(v -> !v.isBlank())
                .isPresent();
    }

    private boolean bool(String key) {
        return config.getOptionalValue(key, Boolean.class).orElse(false);
    }

    private String str(String key, String def) {
        return config.getOptionalValue(key, String.class).orElse(def);
    }

    private int count(String key) {
        return config.getOptionalValue(key, Integer.class).orElse(0);
    }
}
