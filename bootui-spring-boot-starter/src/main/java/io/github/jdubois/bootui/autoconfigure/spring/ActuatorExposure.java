package io.github.jdubois.bootui.autoconfigure.spring;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;

/**
 * Shared, read-only model of how Spring Boot 4 Actuator endpoints are exposed and what access level
 * is effective for each one. Several management rules (SPRING-MGMT-001..004) reason about the same
 * include/exclude/access configuration, so the parsing lives here to stay consistent.
 *
 * <p>Exposure alone is not enough: in Boot 4 an endpoint can be web-exposed but still unreachable
 * because its effective {@code access} is {@code none} (the default for {@code shutdown}), or only
 * readable ({@code read-only}). These helpers therefore separate "is web exposed" from "permits a
 * read / write operation".</p>
 */
final class ActuatorExposure {

    private static final String INCLUDE = "management.endpoints.web.exposure.include";
    private static final String EXCLUDE = "management.endpoints.web.exposure.exclude";
    private static final String ACCESS_DEFAULT = "management.endpoints.access.default";
    private static final String ACCESS_MAX = "management.endpoints.access.max-permitted";
    private static final String ENABLED_BY_DEFAULT = "management.endpoints.enabled-by-default";

    private static final int ACCESS_NONE = 0;
    private static final int ACCESS_READ_ONLY = 1;
    private static final int ACCESS_UNRESTRICTED = 2;

    /** Same constants as Boot Access, without linking this helper to the optional Actuator API. */
    private enum Access {
        NONE,
        READ_ONLY,
        UNRESTRICTED
    }

    /** Sensitive read endpoints owned by SPRING-MGMT-002 (shutdown/heapdump belong to MGMT-004). */
    static final Set<String> SENSITIVE_READ_ENDPOINTS =
            Set.of("env", "configprops", "beans", "threaddump", "loggers", "httpexchanges", "startup", "mappings");

    /** Endpoints Spring Boot web-exposes by default when no explicit include list is configured. */
    private static final Set<String> DEFAULT_WEB_EXPOSED = Set.of("health");

    private ActuatorExposure() {}

    static boolean applicable(SpringContext context) {
        if (!context.observations().known(SpringObservations.Fact.ENDPOINTS)) return false;
        // Boot validates global settings when constructing its resolver, even if all web endpoints
        // are excluded. Do not reinterpret malformed live configuration as default access.
        configuredAccess(context, ACCESS_DEFAULT, ENABLED_BY_DEFAULT);
        maxPermitted(context);
        includeTokens(context);
        excludeTokens(context);
        context.managementWebDisabled();
        for (Object endpoint : context.observations().get(SpringObservations.Fact.ENDPOINTS, Set.class)) {
            String id = (String) endpoint;
            effectiveAccess(
                    context, id, "heapdump".equals(id) || "shutdown".equals(id) ? ACCESS_NONE : ACCESS_UNRESTRICTED);
        }
        return true;
    }

    static boolean anyAccessible(SpringContext context) {
        Set<?> endpoints = context.observations().get(SpringObservations.Fact.ENDPOINTS, Set.class);
        if (endpoints == null) return false;
        for (Object endpoint : endpoints) {
            String id = (String) endpoint;
            if ("shutdown".equals(id) ? shutdownAccessible(context) : isReadable(context, id)) return true;
        }
        return false;
    }

    static Set<String> includeTokens(SpringContext context) {
        return tokens(context, INCLUDE);
    }

    static Set<String> excludeTokens(SpringContext context) {
        return tokens(context, EXCLUDE);
    }

    /**
     * The effective set of included endpoints: an explicit include list when configured, otherwise
     * Spring Boot's built-in default web exposure (just {@code health}). Setting include to anything
     * replaces — rather than extends — that default.
     */
    private static Set<String> effectiveIncludeTokens(SpringContext context) {
        Set<String> include = includeTokens(context);
        return include.isEmpty() ? DEFAULT_WEB_EXPOSED : include;
    }

    /** True when {@code include} lists every endpoint via {@code *} and is not cancelled by {@code exclude=*}. */
    static boolean exposesAll(SpringContext context) {
        return includeTokens(context).contains("*") && !excludeTokens(context).contains("*");
    }

    /** True when the given endpoint id is reachable over the web exposure (ignoring access level). */
    static boolean isWebExposed(SpringContext context, String id) {
        Set<?> endpoints = context.observations().get(SpringObservations.Fact.ENDPOINTS, Set.class);
        if (endpoints == null || !endpoints.contains(id)) return false;
        if (context.managementWebDisabled()) {
            return false;
        }
        Set<String> exclude = excludeTokens(context);
        if (exclude.contains("*") || exclude.contains(id)) {
            return false;
        }
        Set<String> include = effectiveIncludeTokens(context);
        return include.contains("*") || include.contains(id);
    }

    /** True when the endpoint is web-exposed and its effective access permits read operations. */
    static boolean isReadable(SpringContext context, String id) {
        return isWebExposed(context, id)
                && effectiveAccess(
                                context,
                                id,
                                "heapdump".equals(id) || "shutdown".equals(id) ? ACCESS_NONE : ACCESS_UNRESTRICTED)
                        >= ACCESS_READ_ONLY;
    }

    /**
     * True when the {@code shutdown} endpoint is web-exposed and a write operation is permitted —
     * either an explicit {@code access=unrestricted} or the legacy {@code enabled=true} flag, and
     * never when capped to read-only or disabled. The default ({@code access=none}) is not flagged.
     */
    static boolean shutdownAccessible(SpringContext context) {
        return isWebExposed(context, "shutdown")
                && effectiveAccess(context, "shutdown", ACCESS_NONE) >= ACCESS_UNRESTRICTED;
    }

    /** Heapdump also defaults to NONE in Boot 4.1.1; include alone never grants permission. */
    static boolean heapdumpAccessible(SpringContext context) {
        return isReadable(context, "heapdump");
    }

    private static int effectiveAccess(SpringContext context, String id, int defaultRank) {
        Integer endpointAccess = configuredAccess(
                context, "management.endpoint." + id + ".access", "management.endpoint." + id + ".enabled");
        Integer defaultAccess = configuredAccess(context, ACCESS_DEFAULT, ENABLED_BY_DEFAULT);
        if (endpointAccess != null) {
            return capped(context, endpointAccess);
        }
        if (defaultAccess != null) {
            return capped(context, defaultAccess);
        }
        return capped(context, defaultRank);
    }

    private static Integer configuredAccess(SpringContext context, String accessKey, String enabledKey) {
        Access access = SpringProperties.bind(context.environment(), true, accessKey, Bindable.of(Access.class));
        Boolean enabled = SpringProperties.bind(context.environment(), true, enabledKey, Bindable.of(Boolean.class));
        if (access != null && enabled != null)
            throw new IllegalArgumentException("Conflicting endpoint access settings");
        if (access != null) return rank(access);
        return enabled == null ? null : enabled ? ACCESS_UNRESTRICTED : ACCESS_NONE;
    }

    private static int capped(SpringContext context, int access) {
        return Math.min(access, maxPermitted(context));
    }

    private static int rankOrDefault(SpringContext context, String key, int defaultRank) {
        Access access = SpringProperties.bind(context.environment(), true, key, Bindable.of(Access.class));
        return access == null ? defaultRank : rank(access);
    }

    private static int rank(Access access) {
        return switch (access) {
            case NONE -> ACCESS_NONE;
            case READ_ONLY -> ACCESS_READ_ONLY;
            case UNRESTRICTED -> ACCESS_UNRESTRICTED;
        };
    }

    private static int maxPermitted(SpringContext context) {
        return rankOrDefault(context, ACCESS_MAX, ACCESS_UNRESTRICTED);
    }

    /** Binds the include/exclude property as a Set so comma strings and YAML lists both resolve. */
    private static Set<String> tokens(SpringContext context, String key) {
        Set<String> normalized = new LinkedHashSet<>();
        Set<String> bound = SpringProperties.bind(context.environment(), true, key, Bindable.setOf(String.class));
        if (bound == null) return Set.of();
        if (bound.size() > 100) throw new IllegalArgumentException("Exposure list exceeds limit");
        for (String token : bound) {
            if (token != null && !token.isBlank()) {
                String id = token.toLowerCase(Locale.ROOT).trim();
                if (!id.equals("*") && !id.matches("[a-z][a-z0-9-]{0,99}"))
                    throw new IllegalArgumentException("Invalid endpoint ID");
                normalized.add(id);
            }
        }
        return normalized;
    }
}
