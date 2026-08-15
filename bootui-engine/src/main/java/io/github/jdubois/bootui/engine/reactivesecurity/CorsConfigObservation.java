package io.github.jdubois.bootui.engine.reactivesecurity;

import java.util.List;
import java.util.Locale;

/**
 * Framework-neutral, read-only observation of one reactive CORS configuration entry (from a
 * {@code CorsConfigurationSource} bean), collected by the Spring adapter.
 */
public record CorsConfigObservation(
        String pattern,
        List<String> allowedOrigins,
        List<String> allowedOriginPatterns,
        List<String> allowedMethods,
        List<String> allowedHeaders,
        Boolean allowCredentials) {

    public CorsConfigObservation {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        allowedOriginPatterns = allowedOriginPatterns == null ? List.of() : List.copyOf(allowedOriginPatterns);
        allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
        allowedHeaders = allowedHeaders == null ? List.of() : List.copyOf(allowedHeaders);
    }

    boolean hasWildcardOrigin() {
        return allowedOrigins.contains("*");
    }

    boolean hasWildcardOriginPattern() {
        return allowedOriginPatterns.stream().anyMatch("*"::equals);
    }

    /**
     * The configured {@code allowedOriginPatterns} that are dangerously broad (wildcard scheme,
     * wildcard host, or a too-permissive suffix such as {@code *.com}), excluding the exact
     * {@code "*"} pattern already covered by SEC-RXF-CORS-001/002. Scoped subdomain wildcards such
     * as {@code https://*.example.com} are intentionally not flagged.
     */
    List<String> broadOriginPatterns() {
        return allowedOriginPatterns.stream()
                .filter(CorsConfigObservation::isBroadOriginPattern)
                .toList();
    }

    static boolean isBroadOriginPattern(String pattern) {
        if (pattern == null) {
            return false;
        }
        String value = pattern.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || !value.contains("*") || value.equals("*")) {
            return false; // exact "*" is handled by SEC-RXF-CORS-001/002
        }
        if (value.equals("**") || value.contains("*://")) {
            return true; // wildcard everything or wildcard scheme
        }
        String host = value;
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        if (!host.contains("*")) {
            return false;
        }
        int dot = host.indexOf('.');
        String firstLabel = dot >= 0 ? host.substring(0, dot) : host;
        String rest = dot >= 0 ? host.substring(dot + 1) : "";
        if (rest.contains("*")) {
            return true; // wildcard beyond the leftmost host label
        }
        if (firstLabel.contains("*")) {
            // A leftmost-label wildcard is only acceptable with a concrete, multi-label suffix.
            return rest.isEmpty() || !rest.contains(".");
        }
        return false;
    }
}
