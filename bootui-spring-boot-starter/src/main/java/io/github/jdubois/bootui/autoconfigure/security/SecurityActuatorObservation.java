package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.autoconfigure.config.BootUiActuatorDefaultsEnvironmentPostProcessor;
import io.github.jdubois.bootui.autoconfigure.config.BootUiContributedProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * Security-only view of host Actuator selection and access intent. This does not establish that an
 * endpoint exists or that its operations are anonymous; adapters must inspect operation metadata.
 */
public final class SecurityActuatorObservation {
    private static final Set<String> KNOWN = Set.of(
            "health",
            "info",
            "env",
            "beans",
            "configprops",
            "heapdump",
            "threaddump",
            "shutdown",
            "loggers",
            "mappings",
            "metrics",
            "prometheus",
            "httpexchanges",
            "startup",
            "caches",
            "scheduledtasks",
            "conditions",
            "logfile",
            "sessions",
            "flyway",
            "liquibase",
            "quartz",
            "auditevents");
    private static final Set<String> SENSITIVE = Set.of(
            "env",
            "beans",
            "configprops",
            "heapdump",
            "threaddump",
            "shutdown",
            "loggers",
            "mappings",
            "httpexchanges",
            "startup",
            "logfile");

    private SecurityActuatorObservation() {}

    public record Snapshot(
            Set<String> exposedEndpoints,
            Set<String> sensitiveEndpoints,
            boolean wildcardIncluded,
            boolean managementDisabled,
            boolean separateManagementPort,
            boolean complete,
            List<String> errors) {
        public Snapshot {
            exposedEndpoints = Set.copyOf(exposedEndpoints);
            sensitiveEndpoints = Set.copyOf(sensitiveEndpoints);
            errors = List.copyOf(errors);
        }

        public boolean exposed(String id) {
            return exposedEndpoints.contains(id);
        }
    }

    public static Snapshot observe(Environment environment) {
        try {
            return observeSupported(SecurityEnvironmentSnapshot.capture(environment));
        } catch (ObservationLimitException ex) {
            return new Snapshot(Set.of(), Set.of(), false, false, false, false, List.of());
        }
    }

    private static Snapshot observeSupported(Environment environment) {
        List<String> errors = new ArrayList<>();
        Set<String> included = tokens(environment, "management.endpoints.web.exposure.include");
        Set<String> excluded = tokens(environment, "management.endpoints.web.exposure.exclude");
        if (included.isEmpty()) included = Set.of("health");
        boolean wildcard = included.contains("*");
        Integer applicationPort = port(environment, "server.port", 8080, errors);
        Integer managementPort = port(environment, "management.server.port", applicationPort, errors);
        boolean disabled = Integer.valueOf(-1).equals(managementPort)
                || "none".equalsIgnoreCase(host(environment, "spring.main.web-application-type"));
        boolean separate = managementPort != null
                && applicationPort != null
                && !disabled
                && (!managementPort.equals(applicationPort)
                        || managementPort == 0 && host(environment, "management.server.port") != null);
        Set<String> exposed = new LinkedHashSet<>();
        Set<String> sensitive = new LinkedHashSet<>();
        Set<String> candidates = new LinkedHashSet<>(KNOWN);
        candidates.addAll(included);
        candidates.remove("*");
        boolean incomplete = candidates.size() > 256;
        if (incomplete) {
            candidates = new LinkedHashSet<>(KNOWN);
        }
        int maximum = access(host(environment, "management.endpoints.access.max-permitted"), 2, errors);
        String defaultAccess = host(environment, "management.endpoints.access.default");
        String defaultEnabled = host(environment, "management.endpoints.enabled-by-default");
        if (defaultAccess != null && defaultEnabled != null)
            errors.add("Conflicting Actuator default access settings.");
        for (String id : candidates) {
            if (!id.matches("[a-zA-Z0-9-]{1,80}")) {
                errors.add("Unsupported Actuator endpoint identifier.");
                continue;
            }
            String access = host(environment, "management.endpoint." + id + ".access");
            String enabled = host(environment, "management.endpoint." + id + ".enabled");
            if (access != null && enabled != null) {
                errors.add("Conflicting endpoint access settings.");
                continue;
            }
            int rank = id.equals("shutdown") || id.equals("heapdump") ? 0 : 2;
            if (access != null) rank = access(access, -1, errors);
            else if (enabled != null) rank = enabled(enabled, errors);
            else if (defaultAccess != null) rank = access(defaultAccess, -1, errors);
            else if (defaultEnabled != null) rank = enabled(defaultEnabled, errors);
            rank = Math.min(rank, maximum);
            int operation = id.equals("shutdown") ? 2 : 1;
            if (!disabled
                    && !excluded.contains("*")
                    && !excluded.contains(id)
                    && (wildcard || included.contains(id))
                    && rank >= operation) {
                exposed.add(id);
                if (SENSITIVE.contains(id)) sensitive.add(id);
            }
        }
        return new Snapshot(exposed, sensitive, wildcard, disabled, separate, !incomplete && errors.isEmpty(), errors);
    }

    public static Set<String> tokens(Environment environment, String key) {
        environment = SecurityEnvironmentSnapshot.capture(environment);
        if (environment instanceof ConfigurableEnvironment configurable) {
            int count = 0;
            for (var source : configurable.getPropertySources()) {
                if (++count > 128) throw new ObservationLimitException();
                if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) continue;
                Object scalar = source.getProperty(key);
                if (scalar != null) {
                    String text;
                    if (scalar instanceof String value) text = value;
                    else if (scalar instanceof List<?> values
                            && scalar.getClass().getName().startsWith("java.util.")
                            && values.size() <= 256
                            && values.stream().allMatch(String.class::isInstance)) {
                        text = String.join(
                                ",", values.stream().map(String.class::cast).toList());
                    } else if (scalar instanceof String[] values && values.length <= 256)
                        text = String.join(",", values);
                    else throw new ObservationLimitException();
                    if (DefaultPropertiesPropertySource.NAME.equals(source.getName())
                            && BootUiActuatorDefaultsEnvironmentPostProcessor.isBootUiActuatorDefault(key, text.trim()))
                        continue;
                    return splitTokens(text);
                }
                if (source.getProperty(key + "[0]") == null) continue;
                Set<String> values = new LinkedHashSet<>();
                for (int index = 0; index <= 256; index++) {
                    Object value = source.getProperty(key + "[" + index + "]");
                    if (value == null) break;
                    if (index == 256 || !(value instanceof String text) || text.length() > 16384)
                        throw new ObservationLimitException();
                    SecurityEnvironmentSnapshot.supportedText(text);
                    if (!text.isBlank()) values.add(text.trim().toLowerCase(Locale.ROOT));
                }
                return Set.copyOf(values);
            }
            return Set.of();
        }
        String scalar = host(environment, key);
        if (scalar != null) return splitTokens(scalar);
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index <= 256; index++) {
            String value = host(environment, key + "[" + index + "]");
            if (value == null) break;
            if (index == 256) throw new ObservationLimitException();
            if (!value.isBlank()) result.add(value.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    private static Set<String> splitTokens(String scalar) {
        SecurityEnvironmentSnapshot.supportedText(scalar);
        Set<String> result = new LinkedHashSet<>();
        String normalized = scalar.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]"))
            normalized = normalized.substring(1, normalized.length() - 1);
        String[] parts = normalized.split(",", 257);
        if (parts.length > 256) throw new ObservationLimitException();
        for (String value : parts) {
            if (!value.isBlank()) result.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    public static boolean permitsOperation(Environment environment, String id, String method) {
        return permitsOperation(
                environment, id, method, id.equals("shutdown") || id.equals("heapdump") ? "none" : "unrestricted");
    }

    public static boolean permitsOperation(Environment environment, String id, String method, String defaultAccess) {
        environment = SecurityEnvironmentSnapshot.capture(environment);
        Snapshot snapshot = observe(environment);
        if (snapshot.managementDisabled() || !snapshot.complete()) return false;
        Set<String> included = tokens(environment, "management.endpoints.web.exposure.include");
        if (included.isEmpty()) included = Set.of("health");
        Set<String> excluded = tokens(environment, "management.endpoints.web.exposure.exclude");
        if (excluded.contains("*") || excluded.contains(id) || !included.contains("*") && !included.contains(id))
            return false;
        List<String> errors = new ArrayList<>();
        String value = host(environment, "management.endpoint." + id + ".access");
        String enabled = host(environment, "management.endpoint." + id + ".enabled");
        if (value != null && enabled != null) return false;
        int rank;
        if (value != null) rank = access(value, -1, errors);
        else if (enabled != null) rank = enabled(enabled, errors);
        else {
            value = host(environment, "management.endpoints.access.default");
            enabled = host(environment, "management.endpoints.enabled-by-default");
            rank = value != null
                    ? access(value, -1, errors)
                    : enabled != null ? enabled(enabled, errors) : access(defaultAccess, -1, errors);
        }
        int required = "GET".equals(method) || "HEAD".equals(method) ? 1 : 2;
        return Math.min(rank, access(host(environment, "management.endpoints.access.max-permitted"), 2, errors))
                        >= required
                && errors.isEmpty();
    }

    private static String host(Environment environment, String key) {
        return SecurityEnvironmentSnapshot.supportedText(
                BootUiContributedProperties.hostProperty(SecurityEnvironmentSnapshot.capture(environment), key));
    }

    private static Integer port(Environment environment, String key, Integer fallback, List<String> errors) {
        String value = host(environment, key);
        if (value == null) return fallback;
        try {
            int port = Integer.parseInt(value);
            if (port < -1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException ex) {
            errors.add("Invalid server port configuration.");
            return null;
        }
    }

    private static int access(String value, int fallback, List<String> errors) {
        if (value == null) return fallback;
        return switch (value.toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "none" -> 0;
            case "read-only" -> 1;
            case "unrestricted" -> 2;
            default -> {
                errors.add("Invalid Actuator access setting.");
                yield -1;
            }
        };
    }

    private static int enabled(String value, List<String> errors) {
        if ("true".equalsIgnoreCase(value)) return 2;
        if ("false".equalsIgnoreCase(value)) return 0;
        errors.add("Invalid Actuator enabled setting.");
        return -1;
    }

    public static final class ObservationLimitException extends RuntimeException {
        ObservationLimitException() {
            super("Configuration observation is incomplete.");
        }
    }
}
