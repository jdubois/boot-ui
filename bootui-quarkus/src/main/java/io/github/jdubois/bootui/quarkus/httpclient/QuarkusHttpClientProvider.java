package io.github.jdubois.bootui.quarkus.httpclient;

import io.github.jdubois.bootui.spi.DiscoveredHttpClient;
import io.github.jdubois.bootui.spi.DiscoveredHttpClientSetting;
import io.github.jdubois.bootui.spi.HttpClientProvider;
import io.github.jdubois.bootui.spi.HttpClientVocabulary;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Quarkus {@link HttpClientProvider} backed by the build-time-captured {@link QuarkusHttpClients} holder.
 *
 * <p>The deployment processor exposes the synthetic holder only when a REST Client capability is present and
 * the launch mode is non-production, so this provider is wired unconditionally but tolerates the bean's
 * absence: an unsatisfied {@code Instance} means no REST Client extension is installed, {@link #available()}
 * is {@code false}, and no MicroProfile REST Client class is ever loaded.</p>
 *
 * <p>Configuration is read at request time through SmallRye {@link SmallRyeConfig}, never at build time, so a
 * live configuration change and an unresolved placeholder are both reported honestly. Lookups cover the
 * Quarkus per-client namespace first, then the Quarkus global namespace, then the MicroProfile standard
 * {@code <key>/mp-rest/<member>} form, and the reported provenance always matches where the value was found.
 * Nothing here resolves a client bean, opens a connection, or reads credential material: proxy user,
 * password and key/trust-store passwords are never looked up at all, and store configuration is reported as
 * a presence flag only.</p>
 */
@Singleton
public class QuarkusHttpClientProvider implements HttpClientProvider {

    private static final String QUARKUS_PREFIX = "quarkus.rest-client";

    /** Probe keys the application actually declared before keys that only resolve through a fallback. */
    private static final boolean[] DECLARED_FIRST = {true, false};

    private static final String REASON_NO_CLIENT =
            "No declarative HTTP client found. Add the quarkus-rest-client extension and annotate an "
                    + "interface with @RegisterRestClient.";

    private final Instance<QuarkusHttpClients> capturedClients;
    private final SmallRyeConfig config;

    @Inject
    public QuarkusHttpClientProvider(Instance<QuarkusHttpClients> capturedClients, SmallRyeConfig config) {
        this.capturedClients = capturedClients;
        this.config = config;
    }

    @Override
    public boolean available() {
        return !capturedClients.isUnsatisfied()
                && !capturedClients.get().clients().isEmpty();
    }

    @Override
    public String unavailableReason() {
        return REASON_NO_CLIENT;
    }

    @Override
    public List<DiscoveredHttpClient> clients() {
        if (capturedClients.isUnsatisfied()) {
            return List.of();
        }
        List<DiscoveredHttpClient> clients = new ArrayList<>();
        for (RawHttpClient raw : capturedClients.get().clients()) {
            clients.add(toDiscovered(raw));
        }
        return List.copyOf(clients);
    }

    private DiscoveredHttpClient toDiscovered(RawHttpClient raw) {
        String configKey = raw.configKey().isBlank() ? null : raw.configKey();
        Keys keys = keysFor(raw);

        Value baseUrl = urlValue(keys, raw);
        List<DiscoveredHttpClientSetting> settings = new ArrayList<>();
        settings.add(setting(
                HttpClientVocabulary.CATEGORY_TIMEOUT,
                "Connect timeout",
                keys,
                "connect-timeout",
                "connectTimeout",
                true));
        settings.add(setting(
                HttpClientVocabulary.CATEGORY_TIMEOUT, "Read timeout", keys, "read-timeout", "readTimeout", true));
        settings.add(setting(
                HttpClientVocabulary.CATEGORY_REDIRECT,
                "Follow redirects",
                keys,
                "follow-redirects",
                "followRedirects",
                false));
        settings.add(setting(
                HttpClientVocabulary.CATEGORY_CONNECTION_POOL,
                "Connection pool size",
                keys,
                "connection-pool-size",
                null,
                false));
        settings.add(setting(
                HttpClientVocabulary.CATEGORY_CONNECTION_POOL, "Connection TTL", keys, "connection-ttl", null, false));
        settings.add(setting(
                HttpClientVocabulary.CATEGORY_PROXY, "Proxy address", keys, "proxy-address", "proxyAddress", false));
        settings.add(setting(
                HttpClientVocabulary.CATEGORY_TLS, "TLS configuration", keys, "tls-configuration-name", null, false));
        settings.add(setting(HttpClientVocabulary.CATEGORY_TLS, "Verify host", keys, "verify-host", null, false));
        settings.add(storePresence("Trust store", keys, "trust-store"));
        settings.add(storePresence("Key store", keys, "key-store"));
        settings.add(setting(HttpClientVocabulary.CATEGORY_TRANSPORT, "Scope", keys, "scope", "scope", false));

        return new DiscoveredHttpClient(
                configKey != null ? configKey : simpleName(raw.interfaceName()),
                HttpClientVocabulary.KIND_MICROPROFILE_REST_CLIENT,
                raw.interfaceName(),
                configKey,
                baseUrl == null ? null : baseUrl.raw(),
                baseUrl == null ? null : baseUrl.resolved(),
                baseUrl == null ? null : baseUrl.provenance(),
                baseUrl == null ? null : baseUrl.source(),
                settings);
    }

    /**
     * The declared target, in Quarkus's own precedence: the per-client {@code url}/{@code uri} property under
     * any of the key forms Quarkus accepts, then the MicroProfile standard {@code /mp-rest/url} form, then the
     * {@code @RegisterRestClient(baseUri)} annotation member.
     */
    private Value urlValue(Keys keys, RawHttpClient raw) {
        for (String suffix : new String[] {"url", "uri"}) {
            Value value = quarkusScoped(keys, suffix, HttpClientVocabulary.PROVENANCE_CLIENT);
            if (value != null) {
                return value;
            }
        }
        for (String member : new String[] {"url", "uri"}) {
            Value value = mpRest(keys, member, HttpClientVocabulary.PROVENANCE_CLIENT);
            if (value != null) {
                return value;
            }
        }
        if (!raw.baseUri().isBlank()) {
            return new Value(
                    raw.baseUri(),
                    raw.baseUri(),
                    HttpClientVocabulary.PROVENANCE_ANNOTATION,
                    "@RegisterRestClient(baseUri)");
        }
        return null;
    }

    private DiscoveredHttpClientSetting setting(
            String category, String label, Keys keys, String quarkusSuffix, String mpMember, boolean duration) {
        // Every per-client form outranks the global default, exactly as Quarkus resolves it: a client-scoped
        // MicroProfile member must not be reported as an inherited application default.
        Value value = quarkusScoped(keys, quarkusSuffix, HttpClientVocabulary.PROVENANCE_CLIENT);
        if (value == null && mpMember != null) {
            value = mpRest(keys, mpMember, HttpClientVocabulary.PROVENANCE_CLIENT);
        }
        if (value == null) {
            value = raw(QUARKUS_PREFIX + "." + quarkusSuffix, HttpClientVocabulary.PROVENANCE_APPLICATION);
        }
        if (value == null) {
            return DiscoveredHttpClientSetting.unavailable(category, label);
        }
        String display = duration ? describeDuration(value.resolved()) : value.resolved();
        return new DiscoveredHttpClientSetting(category, label, display, value.provenance(), value.source());
    }

    /**
     * Key and trust stores are reported as a presence flag only. The store path is not interesting without
     * its password, and the password must never leave the process, so BootUI reads neither.
     */
    private DiscoveredHttpClientSetting storePresence(String label, Keys keys, String suffix) {
        Value value = quarkusScoped(keys, suffix, HttpClientVocabulary.PROVENANCE_CLIENT);
        if (value == null) {
            value = raw(QUARKUS_PREFIX + "." + suffix, HttpClientVocabulary.PROVENANCE_APPLICATION);
        }
        if (value == null) {
            return DiscoveredHttpClientSetting.unavailable(HttpClientVocabulary.CATEGORY_TLS, label);
        }
        return new DiscoveredHttpClientSetting(
                HttpClientVocabulary.CATEGORY_TLS, label, "Configured", value.provenance(), value.source());
    }

    /**
     * The key forms Quarkus itself accepts for one client, in its own resolution order: the fully-qualified
     * interface name, then the simple name, then the {@code configKey}, each in quoted and unquoted form.
     * Reporting only one of them would make a fully-configured client look undeclared.
     */
    private static Keys keysFor(RawHttpClient raw) {
        List<String> names = new ArrayList<>();
        addName(names, raw.interfaceName());
        addName(names, simpleName(raw.interfaceName()));
        addName(names, raw.configKey());
        List<String> quarkusScopes = new ArrayList<>();
        for (String name : names) {
            quarkusScopes.add("\"" + name + "\"");
            quarkusScopes.add(name);
        }
        return new Keys(List.copyOf(quarkusScopes), List.copyOf(names));
    }

    private static void addName(List<String> names, String candidate) {
        if (candidate != null && !candidate.isBlank() && !names.contains(candidate)) {
            names.add(candidate);
        }
    }

    /** Reads {@code quarkus.rest-client.<scope>.<suffix>} over every key form Quarkus accepts. */
    private Value quarkusScoped(Keys keys, String suffix, String provenance) {
        // Quarkus lets one key form fall back to another, so several forms resolve even though the
        // application only ever wrote one. Report the key that actually exists first, and only then the
        // resolution order, so the source column names a key the developer can search for.
        for (boolean declaredOnly : DECLARED_FIRST) {
            for (String scope : keys.quarkusScopes()) {
                Value value = raw(QUARKUS_PREFIX + "." + scope + "." + suffix, provenance, declaredOnly);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private Value mpRest(Keys keys, String member, String provenance) {
        for (boolean declaredOnly : DECLARED_FIRST) {
            for (String name : keys.mpScopes()) {
                Value value = raw(name + "/mp-rest/" + member, provenance, declaredOnly);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /** The ordered configuration key forms that identify one REST client. */
    private record Keys(List<String> quarkusScopes, List<String> mpScopes) {}

    /**
     * Reads a property twice: the raw text as written, so an unresolved {@code ${...}} expression is visible
     * instead of throwing, and the expanded text for display.
     *
     * <p>SmallRye expands expressions eagerly, so a key whose expression cannot be resolved makes
     * {@link SmallRyeConfig#getConfigValue(String)} itself fail. That must not make the setting disappear —
     * an unresolved target is exactly the state this panel exists to surface — so the raw text is then read
     * straight from the configuration sources and reported as both the configured and the resolved value.</p>
     */
    private Value raw(String key, String provenance) {
        return raw(key, provenance, false);
    }

    private Value raw(String key, String provenance, boolean declaredOnly) {
        if (declaredOnly && rawFromSources(key) == null) {
            return null;
        }
        String rawValue = null;
        String resolved = null;
        try {
            ConfigValue value = config.getConfigValue(key);
            if (value != null) {
                rawValue = value.getRawValue();
                resolved = value.getValue();
            }
        } catch (RuntimeException ex) {
            // An expression that cannot expand: fall through to the raw source scan below.
        }
        if (blank(rawValue)) {
            rawValue = rawFromSources(key);
        }
        if (blank(rawValue)) {
            return null;
        }
        if (blank(resolved)) {
            resolved = rawValue;
        }
        return new Value(rawValue, resolved, provenance, key);
    }

    /**
     * The value exactly as a configuration source holds it, used when expression expansion failed. Active
     * profiles are probed first, in the same order SmallRye applies them, so a profiled key whose expression
     * cannot expand stays visible instead of vanishing from the panel.
     */
    private String rawFromSources(String key) {
        try {
            for (String profile : config.getProfiles()) {
                String value = rawFromSources(config.getConfigSources(), "%" + profile + "." + key);
                if (value != null) {
                    return value;
                }
            }
            return rawFromSources(config.getConfigSources(), key);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String rawFromSources(Iterable<ConfigSource> sources, String key) {
        for (ConfigSource source : sources) {
            String value = source.getValue(key);
            if (!blank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String describeDuration(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("PT") || trimmed.startsWith("pt")) {
            return trimmed.toUpperCase(java.util.Locale.ROOT);
        }
        return trimmed.matches("-?\\d+") ? trimmed + "ms" : trimmed;
    }

    private static String simpleName(String interfaceName) {
        if (interfaceName == null || interfaceName.isBlank()) {
            return "REST client";
        }
        int lastDot = interfaceName.lastIndexOf('.');
        return lastDot < 0 ? interfaceName : interfaceName.substring(lastDot + 1);
    }

    /** One configuration value: raw text, expanded text, provenance and the key it came from. */
    private record Value(String raw, String resolved, String provenance, String source) {}
}
