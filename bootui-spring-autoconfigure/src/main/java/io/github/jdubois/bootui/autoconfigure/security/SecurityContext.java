package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.autoconfigure.config.BootUiActuatorDefaultsEnvironmentPostProcessor;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.FilterChainModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.PasswordEncoderModel;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Read-only inputs handed to every Spring Security Advisor rule: the introspected filter chains and
 * security beans plus the application {@link Environment}.
 */
record SecurityContext(
        List<FilterChainModel> chains,
        List<PasswordEncoderModel> passwordEncoders,
        List<CorsConfigModel> corsConfigs,
        boolean corsSourcePresent,
        List<String> jwtDecoderTypes,
        boolean methodSecurityEnabled,
        boolean globalMethodSecurityLegacyPresent,
        boolean methodSecurityAnnotationsPresent,
        boolean webSecurityConfigurerAdapterPresent,
        Environment environment) {
    SecurityContext {
        chains = List.copyOf(chains);
        passwordEncoders = List.copyOf(passwordEncoders);
        corsConfigs = List.copyOf(corsConfigs);
        jwtDecoderTypes = List.copyOf(jwtDecoderTypes);
    }

    /** The fully-qualified type names of the discovered {@code PasswordEncoder} beans. */
    List<String> passwordEncoderTypes() {
        return passwordEncoders.stream().map(PasswordEncoderModel::type).toList();
    }

    boolean hasFormOrBasicChain() {
        return chains.stream().anyMatch(FilterChainModel::isFormOrBasic);
    }

    boolean hasStatefulChain() {
        return chains.stream().anyMatch(FilterChainModel::isStateful);
    }

    boolean isResourceServerConfigured() {
        return chains.stream().anyMatch(chain -> chain.hasFilterContaining("BearerTokenAuthenticationFilter"))
                || firstProperty(
                                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                                "spring.security.oauth2.resourceserver.jwt.public-key-location")
                        != null
                || !jwtDecoderTypes.isEmpty();
    }

    /**
     * {@code true} when the application configures (or is expected to run behind) TLS: server-side
     * SSL is configured, a forwarded-headers strategy indicates TLS is terminated upstream, or a
     * chain installs an HTTPS-redirect filter.
     */
    boolean isTlsConfigured() {
        if (isPropertyTrue("server.ssl.enabled")
                || firstProperty("server.ssl.key-store") != null
                || firstProperty("server.ssl.bundle") != null
                || firstProperty("server.ssl.certificate") != null) {
            return true;
        }
        String forwarded = firstProperty("server.forward-headers-strategy");
        if (forwarded != null && ("framework".equalsIgnoreCase(forwarded) || "native".equalsIgnoreCase(forwarded))) {
            return true;
        }
        return chains.stream()
                .anyMatch(
                        chain -> chain.hasFilter("ChannelProcessingFilter") || chain.hasFilter("HttpsRedirectFilter"));
    }

    String firstProperty(String... keys) {
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    boolean isPropertyTrue(String... keys) {
        String value = firstProperty(keys);
        return value != null && "true".equalsIgnoreCase(value);
    }

    boolean isPropertyFalse(String... keys) {
        String value = firstProperty(keys);
        return value != null && "false".equalsIgnoreCase(value);
    }

    String firstHostProperty(String... keys) {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return firstProperty(keys);
        }
        for (String key : keys) {
            String value = hostProperty(configurableEnvironment, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String hostProperty(ConfigurableEnvironment configurableEnvironment, String key) {
        for (PropertySource<?> propertySource : configurableEnvironment.getPropertySources()) {
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(propertySource)) {
                continue;
            }
            Object value = propertySource.getProperty(key);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (text.isBlank()) {
                continue;
            }
            if (isBootUiActuatorDefault(propertySource, key, text)) {
                continue;
            }
            return text;
        }
        return null;
    }

    private boolean isBootUiActuatorDefault(PropertySource<?> propertySource, String key, String value) {
        return DefaultPropertiesPropertySource.NAME.equals(propertySource.getName())
                && BootUiActuatorDefaultsEnvironmentPostProcessor.isBootUiActuatorDefault(key, value);
    }

    String[] activeProfiles() {
        try {
            return environment.getActiveProfiles();
        } catch (RuntimeException ex) {
            return new String[0];
        }
    }

    boolean isProductionProfileActive() {
        for (String profile : activeProfiles()) {
            if (profile == null) {
                continue;
            }
            String normalized = profile.toLowerCase(Locale.ROOT);
            if (normalized.equals("prod")
                    || normalized.equals("production")
                    || normalized.equals("staging")
                    || normalized.startsWith("prod-")
                    || normalized.endsWith("-prod")
                    || normalized.endsWith("-production")) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern SUSPECTED_SECRET_KEY = Pattern.compile(
            ".*(password|passwd|secret|token|api-?key|client-secret|private-key).*", Pattern.CASE_INSENSITIVE);

    /**
     * Configuration property names (never values) that look like they hold a credential -- matching
     * {@link #SUSPECTED_SECRET_KEY} -- and whose raw, per-source value is a non-blank literal rather
     * than an unresolved {@code ${...}} placeholder reference. Only ordinary, file-like configuration
     * sources are scanned: system properties, the OS environment, the random-value source, BootUI's
     * own defaults, and mounted config-tree secrets are excluded because they are already legitimate
     * externalization mechanisms, not hardcoded literals. The literal value itself is never returned
     * or retained here, so it cannot leak into a violation message or the browser.
     */
    Set<String> suspectedHardcodedSecretKeys() {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return Set.of();
        }
        Set<String> found = new LinkedHashSet<>();
        for (PropertySource<?> propertySource : configurableEnvironment.getPropertySources()) {
            if (!isScannableConfigSource(propertySource)) {
                continue;
            }
            if (!(propertySource instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (name == null
                        || name.isBlank()
                        || name.toLowerCase(Locale.ROOT).startsWith("bootui.")) {
                    continue;
                }
                if (!SUSPECTED_SECRET_KEY.matcher(name).matches()) {
                    continue;
                }
                Object rawValue = propertySource.getProperty(name);
                if (!(rawValue instanceof String text) || text.isBlank() || text.contains("${")) {
                    continue;
                }
                found.add(name);
            }
        }
        return found;
    }

    private static boolean isScannableConfigSource(PropertySource<?> propertySource) {
        if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(propertySource)) {
            return false;
        }
        String name = propertySource.getName();
        if (StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME.equals(name)
                || StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(name)
                || RandomValuePropertySource.RANDOM_PROPERTY_SOURCE_NAME.equals(name)
                || DefaultPropertiesPropertySource.NAME.equals(name)) {
            return false;
        }
        return !(propertySource instanceof ConfigTreePropertySource);
    }
}
