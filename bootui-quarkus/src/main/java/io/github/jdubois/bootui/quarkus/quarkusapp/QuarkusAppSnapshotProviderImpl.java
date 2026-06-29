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
                false);
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

    private boolean bool(String key) {
        return config.getOptionalValue(key, Boolean.class).orElse(false);
    }

    private int count(String key) {
        return config.getOptionalValue(key, Integer.class).orElse(0);
    }
}
