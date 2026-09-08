package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.autoconfigure.config.BootUiContributedProperties;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.FilterChainModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.PasswordEncoderModel;
import io.github.jdubois.bootui.engine.security.SecurityEvaluation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
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
        boolean customCorsSourcePresent,
        List<String> oauth2TokenValidatorTypes,
        boolean strictHttpFirewallWeakened,
        boolean hideUserNotFoundExceptionsDisabled,
        List<String> opaqueTokenIntrospectorTypes,
        boolean generatedUserDetailsManagerPresent,
        boolean securityDebugFilterPresent,
        Environment environment,
        Evidence evidence) {
    record Operation(String endpoint, String method, String path, String defaultAccess) {
        Operation(String endpoint, String method, String path) {
            this(
                    endpoint,
                    method,
                    path,
                    endpoint.equals("shutdown") || endpoint.equals("heapdump") ? "none" : "unrestricted");
        }
    }

    record Evidence(
            List<Operation> operations,
            boolean operationsKnown,
            boolean bootManagedJwt,
            Set<String> enabledMethodFamilies,
            Set<String> usedMethodFamilies,
            boolean methodFamiliesKnown,
            SecurityEvaluation evaluation) {
        Evidence(
                List<Operation> operations,
                boolean operationsKnown,
                boolean bootManagedJwt,
                Set<String> enabledMethodFamilies,
                Set<String> usedMethodFamilies,
                boolean methodFamiliesKnown) {
            this(
                    operations,
                    operationsKnown,
                    bootManagedJwt,
                    enabledMethodFamilies,
                    usedMethodFamilies,
                    methodFamiliesKnown,
                    new SecurityEvaluation());
        }

        Evidence {
            operations = List.copyOf(operations);
            enabledMethodFamilies = Set.copyOf(enabledMethodFamilies);
            usedMethodFamilies = Set.copyOf(usedMethodFamilies);
        }
    }

    boolean applies(boolean applicable) {
        return evidence.evaluation().applies(applicable);
    }

    boolean required(boolean known) {
        return evidence.evaluation().required(known);
    }

    <T> List<T> targets(List<T> targets) {
        applies(!targets.isEmpty());
        return targets;
    }

    SecurityContext(
            List<FilterChainModel> chains,
            List<PasswordEncoderModel> passwordEncoders,
            List<CorsConfigModel> corsConfigs,
            boolean corsSourcePresent,
            List<String> jwtDecoderTypes,
            boolean methodSecurityEnabled,
            boolean globalMethodSecurityLegacyPresent,
            boolean methodSecurityAnnotationsPresent,
            boolean customCorsSourcePresent,
            List<String> oauth2TokenValidatorTypes,
            boolean strictHttpFirewallWeakened,
            boolean hideUserNotFoundExceptionsDisabled,
            List<String> opaqueTokenIntrospectorTypes,
            boolean generatedUserDetailsManagerPresent,
            boolean securityDebugFilterPresent,
            Environment environment) {
        this(
                chains,
                passwordEncoders,
                corsConfigs,
                corsSourcePresent,
                jwtDecoderTypes,
                methodSecurityEnabled,
                globalMethodSecurityLegacyPresent,
                methodSecurityAnnotationsPresent,
                customCorsSourcePresent,
                oauth2TokenValidatorTypes,
                strictHttpFirewallWeakened,
                hideUserNotFoundExceptionsDisabled,
                opaqueTokenIntrospectorTypes,
                generatedUserDetailsManagerPresent,
                securityDebugFilterPresent,
                environment,
                new Evidence(
                        List.of(),
                        false,
                        false,
                        methodSecurityEnabled ? Set.of("pre-post", "secured", "jsr250") : Set.of(),
                        methodSecurityAnnotationsPresent ? Set.of("pre-post") : Set.of(),
                        true));
    }

    SecurityContext {
        environment = SecurityEnvironmentSnapshot.capture(environment);
        chains = List.copyOf(chains);
        passwordEncoders = List.copyOf(passwordEncoders);
        corsConfigs = List.copyOf(corsConfigs);
        jwtDecoderTypes = List.copyOf(jwtDecoderTypes);
        oauth2TokenValidatorTypes = List.copyOf(oauth2TokenValidatorTypes);
        opaqueTokenIntrospectorTypes = List.copyOf(opaqueTokenIntrospectorTypes);
    }

    SecurityContext(
            List<FilterChainModel> chains,
            List<PasswordEncoderModel> passwordEncoders,
            List<CorsConfigModel> corsConfigs,
            boolean corsSourcePresent,
            List<String> jwtDecoderTypes,
            boolean methodSecurityEnabled,
            boolean globalMethodSecurityLegacyPresent,
            boolean methodSecurityAnnotationsPresent,
            boolean customCorsSourcePresent,
            List<String> oauth2TokenValidatorTypes,
            boolean strictHttpFirewallWeakened,
            boolean hideUserNotFoundExceptionsDisabled,
            List<String> opaqueTokenIntrospectorTypes,
            boolean generatedUserDetailsManagerPresent,
            Environment environment) {
        this(
                chains,
                passwordEncoders,
                corsConfigs,
                corsSourcePresent,
                jwtDecoderTypes,
                methodSecurityEnabled,
                globalMethodSecurityLegacyPresent,
                methodSecurityAnnotationsPresent,
                customCorsSourcePresent,
                oauth2TokenValidatorTypes,
                strictHttpFirewallWeakened,
                hideUserNotFoundExceptionsDisabled,
                opaqueTokenIntrospectorTypes,
                generatedUserDetailsManagerPresent,
                false,
                environment);
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

    /**
     * Local TLS configuration or supported chain-local redirect intent, not proof of deployed transport.
     */
    boolean isTlsConfigured() {
        return isGlobalTlsConfigured() || !chains.isEmpty() && chains.stream().allMatch(this::hasHttpsRedirect);
    }

    boolean isTlsConfiguredFor(FilterChainModel chain) {
        return isGlobalTlsConfigured() || hasHttpsRedirect(chain);
    }

    private boolean isGlobalTlsConfigured() {
        if (isPropertyFalse("server.ssl.enabled")) return false;
        if (isPropertyTrue("server.ssl.enabled")
                || firstProperty("server.ssl.key-store") != null
                || firstProperty("server.ssl.bundle") != null
                || firstProperty("server.ssl.certificate") != null) {
            return true;
        }
        return false;
    }

    private boolean hasHttpsRedirect(FilterChainModel chain) {
        String port = firstProperty("server.port");
        return chain.details().httpsRedirect() && (port == null || port.equals("80") || port.equals("8080"));
    }

    /**
     * Actuator endpoint ids this advisor treats as sensitive: capable of leaking configuration,
     * environment variables, credentials, thread/heap contents, or letting a caller reconfigure or
     * shut down the application.
     */
    static final List<String> SENSITIVE_ACTUATOR_ENDPOINTS =
            List.of("env", "beans", "configprops", "heapdump", "threaddump", "shutdown", "loggers", "mappings");

    private static Set<String> tokenize(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : commaSeparated.toLowerCase(Locale.ROOT).split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }

    /**
     * The subset of {@link #SENSITIVE_ACTUATOR_ENDPOINTS} still reachable once
     * {@code management.endpoints.web.exposure.exclude} has been applied to
     * {@code management.endpoints.web.exposure.include}. A wildcard include with no exclude at all
     * returns an empty set so callers don't double-report the blanket-exposure finding that
     * {@code SEC-ACT-001} already raises for that exact (unhardened) case.
     */
    Set<String> effectiveSensitiveActuatorExposure() {
        Set<String> selected = actuator().sensitiveEndpoints();
        if (!evidence.operationsKnown()) return selected;
        return evidence.operations().stream()
                .map(Operation::endpoint)
                .filter(selected::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * {@code true} when the effective actuator exposure (include minus exclude) reaches beyond the
     * always-safe {@code health}/{@code info} endpoints. Used by rules that flag "more than the
     * basics are reachable" regardless of which specific sensitive endpoint is involved.
     */
    boolean exposesBeyondHealthAndInfo() {
        Set<String> selected = actuator().exposedEndpoints();
        if (evidence.operationsKnown()) {
            return evidence.operations().stream()
                    .anyMatch(operation -> !operation.endpoint().equals("health")
                            && !operation.endpoint().equals("info")
                            && selectedOperation(operation));
        }
        return selected.stream().anyMatch(id -> !id.equals("health") && !id.equals("info"));
    }

    SecurityActuatorObservation.Snapshot actuator() {
        return SecurityActuatorObservation.observe(environment);
    }

    boolean observedEndpoint(String id) {
        return evidence.operations().stream()
                .anyMatch(operation -> operation.endpoint().equals(id));
    }

    boolean selectedOperation(Operation operation) {
        return SecurityActuatorObservation.permitsOperation(
                environment, operation.endpoint(), operation.method(), operation.defaultAccess());
    }

    /**
     * The configured actuator base path ({@code management.endpoints.web.base-path}), falling back to
     * Spring Boot's own {@code /actuator} default. Resolved from the {@link Environment} so the
     * rules can describe host configuration consistently with the native operation inventory.
     */
    static String actuatorBasePath(Environment environment) {
        String base =
                SecurityEnvironmentSnapshot.capture(environment).getProperty("management.endpoints.web.base-path");
        return (base == null || base.isBlank()) ? "/actuator" : base.trim();
    }

    String actuatorBasePath() {
        return actuatorBasePath(environment);
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
        return SecurityEnvironmentSnapshot.supportedText(
                BootUiContributedProperties.firstHostProperty(environment, keys));
    }

    String[] activeProfiles() {
        try {
            return environment.getActiveProfiles();
        } catch (SecurityActuatorObservation.ObservationLimitException ex) {
            throw ex;
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
            ".*(?:^|[.-])(password|passwd|secret|token|api-?key|secret-key|client-secret|private-key)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Key suffixes that indicate a property configures the <em>lifetime</em> or <em>shape</em> of a
     * credential/token rather than holding its literal value -- e.g. {@code jwt.token.expiration=3600}
     * is a TTL in seconds, not a hardcoded secret, even though its key contains "token" and would
     * otherwise match {@link #SUSPECTED_SECRET_KEY}.
     */
    private static final Pattern NON_SECRET_VALUE_KEY_SUFFIX = Pattern.compile(
            ".*[.-](expiration|expiry|expires|ttl|timeout|duration|validity|max-age|maxage|refresh-interval)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Configuration property names (never values) that look like they hold a credential -- matching
     * {@link #SUSPECTED_SECRET_KEY} but not the non-secret {@link #NON_SECRET_VALUE_KEY_SUFFIX} (a
     * TTL/expiry/timeout key) -- and whose raw, per-source value is a non-blank literal rather than
     * an unresolved {@code ${...}} placeholder reference. Only ordinary, file-like configuration
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
        Set<String> shadowed = new LinkedHashSet<>();
        int sourceCount = 0;
        boolean opaqueHigherSource = false;
        for (PropertySource<?> propertySource : configurableEnvironment.getPropertySources()) {
            if (++sourceCount > 128) throw new SecurityRuleSupport.IncompleteObservationException();
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(propertySource)) continue;
            if (!isScannableConfigSource(propertySource)) {
                String type = propertySource.getClass().getName();
                if (Set.of(
                                        "org.springframework.core.env.MapPropertySource",
                                        "org.springframework.core.env.PropertiesPropertySource",
                                        "org.springframework.core.env.SystemEnvironmentPropertySource",
                                        "org.springframework.mock.env.MockPropertySource",
                                        "org.springframework.boot.env.OriginTrackedMapPropertySource")
                                .contains(type)
                        && propertySource.getSource() instanceof java.util.Map<?, ?> values) {
                    if (values.size() > 5000) throw new SecurityRuleSupport.IncompleteObservationException();
                    for (Object key : values.keySet()) {
                        if (key instanceof String name) {
                            shadowed.add(name);
                            shadowed.add(name.toLowerCase(Locale.ROOT).replace('_', '.'));
                        }
                    }
                } else if (!(propertySource instanceof RandomValuePropertySource)
                        && !(propertySource instanceof PropertySource.StubPropertySource)) {
                    opaqueHigherSource = true;
                }
                continue;
            }
            if (!(propertySource instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            String[] names = enumerable.getPropertyNames();
            if (names.length > 5000) throw new SecurityRuleSupport.IncompleteObservationException();
            for (String name : names) {
                if (name == null
                        || name.isBlank()
                        || name.toLowerCase(Locale.ROOT).startsWith("bootui.")) {
                    continue;
                }
                if (!SUSPECTED_SECRET_KEY.matcher(name).matches()
                        || NON_SECRET_VALUE_KEY_SUFFIX.matcher(name).matches()) {
                    continue;
                }
                if (!shadowed.add(name)) continue;
                if (opaqueHigherSource) continue;
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
        return propertySource.getClass() == org.springframework.boot.env.OriginTrackedMapPropertySource.class
                && name.startsWith("Config resource 'class path resource");
    }

    boolean secretObservationComplete() {
        if (!(environment instanceof ConfigurableEnvironment configurable)) return false;
        int count = 0;
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (++count > 128) return false;
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) continue;
            if (source instanceof PropertySource.StubPropertySource || source instanceof RandomValuePropertySource)
                continue;
            if (!Set.of(
                            "org.springframework.core.env.MapPropertySource",
                            "org.springframework.core.env.PropertiesPropertySource",
                            "org.springframework.core.env.SystemEnvironmentPropertySource",
                            "org.springframework.mock.env.MockPropertySource",
                            "org.springframework.boot.env.OriginTrackedMapPropertySource")
                    .contains(source.getClass().getName())) return false;
        }
        return true;
    }

    Set<String> securityLoggerNames() {
        Set<String> names = new LinkedHashSet<>(List.of(
                "org.springframework.security",
                "org.springframework.security.web",
                "org.springframework.security.authentication",
                "org.springframework.security.authorization",
                "org.springframework.security.oauth2"));
        if (!(environment instanceof ConfigurableEnvironment configurable)) return names;
        int sources = 0;
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (++sources > 128) throw new SecurityRuleSupport.IncompleteObservationException();
            String type = source.getClass().getName();
            if (!Set.of(
                                    "org.springframework.core.env.MapPropertySource",
                                    "org.springframework.core.env.PropertiesPropertySource",
                                    "org.springframework.boot.env.OriginTrackedMapPropertySource",
                                    "org.springframework.mock.env.MockPropertySource")
                            .contains(type)
                    || !(source.getSource() instanceof java.util.Map<?, ?> map)) continue;
            if (map.size() > 5000) throw new SecurityRuleSupport.IncompleteObservationException();
            for (Object key : map.keySet()) {
                if (key instanceof String name
                        && name.startsWith("logging.level.org.springframework.security.")
                        && name.length() <= 300) names.add(name.substring("logging.level.".length()));
                if (names.size() > 256) throw new SecurityRuleSupport.IncompleteObservationException();
            }
        }
        return Set.copyOf(names);
    }
}
