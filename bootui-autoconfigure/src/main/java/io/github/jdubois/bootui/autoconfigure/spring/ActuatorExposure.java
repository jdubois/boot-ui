package io.github.jdubois.bootui.autoconfigure.spring;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

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

    private static final int ACCESS_NONE = 0;
    private static final int ACCESS_READ_ONLY = 1;
    private static final int ACCESS_UNRESTRICTED = 2;

    /** Sensitive read endpoints owned by SPRING-MGMT-002 (shutdown/heapdump belong to MGMT-004). */
    static final Set<String> SENSITIVE_READ_ENDPOINTS =
            Set.of("env", "configprops", "beans", "threaddump", "loggers", "httpexchanges", "startup", "mappings");

    /** Endpoints Spring Boot web-exposes by default when no explicit include list is configured. */
    private static final Set<String> DEFAULT_WEB_EXPOSED = Set.of("health");

    private ActuatorExposure() {}

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
        return isWebExposed(context, id) && effectiveAccess(context, id, ACCESS_READ_ONLY) >= ACCESS_READ_ONLY;
    }

    /**
     * True when the {@code shutdown} endpoint is web-exposed and a write operation is permitted —
     * either an explicit {@code access=unrestricted} or the legacy {@code enabled=true} flag, and
     * never when capped to read-only or disabled. The default ({@code access=none}) is not flagged.
     */
    static boolean shutdownAccessible(SpringContext context) {
        if (!isWebExposed(context, "shutdown")) {
            return false;
        }
        if (isPropertyFalse(context, "management.endpoint.shutdown.enabled")) {
            return false;
        }
        String access = context.firstProperty("management.endpoint.shutdown.access", ACCESS_DEFAULT);
        boolean unrestricted = "unrestricted".equalsIgnoreCase(access);
        boolean legacyEnabled = context.isPropertyTrue("management.endpoint.shutdown.enabled");
        boolean writable = unrestricted || legacyEnabled;
        return writable && maxPermitted(context) >= ACCESS_UNRESTRICTED;
    }

    /** True when the {@code heapdump} endpoint is web-exposed and at least readable (its default). */
    static boolean heapdumpAccessible(SpringContext context) {
        if (isPropertyFalse(context, "management.endpoint.heapdump.enabled")) {
            return false;
        }
        return isReadable(context, "heapdump");
    }

    private static int effectiveAccess(SpringContext context, String id, int defaultRank) {
        if (isPropertyFalse(context, "management.endpoint." + id + ".enabled")) {
            return ACCESS_NONE;
        }
        String access = context.firstProperty("management.endpoint." + id + ".access", ACCESS_DEFAULT);
        int rank = access != null ? rank(access) : defaultRank;
        if (rank < 0) {
            rank = defaultRank;
        }
        return Math.min(rank, maxPermitted(context));
    }

    private static int maxPermitted(SpringContext context) {
        String max = context.firstProperty(ACCESS_MAX);
        if (max == null) {
            return ACCESS_UNRESTRICTED;
        }
        int rank = rank(max);
        return rank >= 0 ? rank : ACCESS_UNRESTRICTED;
    }

    private static int rank(String access) {
        return switch (access.toLowerCase(Locale.ROOT).trim()) {
            case "none" -> ACCESS_NONE;
            case "read-only" -> ACCESS_READ_ONLY;
            case "unrestricted" -> ACCESS_UNRESTRICTED;
            default -> -1;
        };
    }

    private static boolean isPropertyFalse(SpringContext context, String key) {
        String value = context.firstProperty(key);
        return value != null && "false".equalsIgnoreCase(value);
    }

    /** Binds the include/exclude property as a Set so comma strings and YAML lists both resolve. */
    private static Set<String> tokens(SpringContext context, String key) {
        Set<String> normalized = new LinkedHashSet<>();
        try {
            Set<String> bound = Binder.get(context.environment())
                    .bind(key, Bindable.setOf(String.class))
                    .orElseGet(Set::of);
            for (String token : bound) {
                if (token != null && !token.isBlank()) {
                    normalized.add(token.toLowerCase(Locale.ROOT).trim());
                }
            }
        } catch (RuntimeException ex) {
            // Fall back to an empty set if the property cannot be bound.
        }
        return normalized;
    }
}
