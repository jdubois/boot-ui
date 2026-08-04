package io.github.jdubois.bootui.engine.reactivesecurity;

import java.util.List;

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
        return allowedOriginPatterns.stream().anyMatch(p -> p.equals("*") || p.endsWith("**"));
    }
}
