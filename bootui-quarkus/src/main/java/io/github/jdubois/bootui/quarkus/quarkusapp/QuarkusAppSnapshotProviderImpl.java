package io.github.jdubois.bootui.quarkus.quarkusapp;

import io.github.jdubois.bootui.spi.QuarkusAppSnapshot;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshotProvider;
import io.smallrye.config.SmallRyeConfig;
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
    static final String CONFIG_PROPERTY_KEY = "bootui.internal.app.config-property";
    static final String ENDPOINTS_KEY = "bootui.internal.app.endpoints";
    static final String DEFAULT_SCOPE_RESOURCES_KEY = "bootui.internal.app.default-scope-resources";
    static final String REACTIVE_ENDPOINTS_KEY = "bootui.internal.app.reactive-endpoints";
    static final String BLOCKING_KEY = "bootui.internal.app.blocking";
    static final String SCHEDULED_KEY = "bootui.internal.app.scheduled";
    static final String CONFIG_MAPPING_KEY = "bootui.internal.app.config-mapping";
    static final String PUBLIC_RESOURCE_FIELDS_KEY = "bootui.internal.app.public-resource-fields";

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
                strList(PUBLIC_RESOURCE_FIELDS_KEY));
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
