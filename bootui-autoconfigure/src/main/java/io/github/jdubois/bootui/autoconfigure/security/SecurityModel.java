package io.github.jdubois.bootui.autoconfigure.security;

import java.util.List;
import java.util.Locale;

/**
 * Bounded, read-only snapshot of the host application's Spring Security configuration. Built by the
 * scanner from the registered {@code SecurityFilterChain} beans and related security beans, and
 * consumed by the static advisor ruleset. Holds no credentials, keys, or session identifiers.
 */
final class SecurityModel {

    private SecurityModel() {}

    /**
     * One {@code SecurityFilterChain} and the salient, read-only facts the advisor needs about it.
     *
     * @param permitsAllAnonymous best-effort result of simulating an anonymous request through the
     *     chain's authorization manager ({@code TRUE} when fully public, {@code null} when it could
     *     not be determined)
     * @param sessionFixationDisabled {@code TRUE} when the session-management strategy was detected
     *     to skip session-fixation protection, {@code null} when it could not be determined
     * @param headerWriterNames simple class names of the {@code HeaderWriter}s installed by the
     *     chain's {@code HeaderWriterFilter}, when one is present
     */
    record FilterChainModel(
            int index,
            String matcher,
            List<String> filterNames,
            Boolean permitsAllAnonymous,
            Boolean sessionFixationDisabled,
            List<String> headerWriterNames) {

        FilterChainModel {
            filterNames = List.copyOf(filterNames);
            headerWriterNames = headerWriterNames == null ? List.of() : List.copyOf(headerWriterNames);
        }

        boolean hasFilter(String simpleClassName) {
            return filterNames.contains(simpleClassName);
        }

        boolean hasFilterContaining(String fragment) {
            return filterNames.stream().anyMatch(name -> name.contains(fragment));
        }

        boolean headerWriterFilterPresent() {
            return hasFilter("HeaderWriterFilter");
        }

        boolean hasHeaderWriterContaining(String fragment) {
            return headerWriterNames.stream().anyMatch(name -> name.contains(fragment));
        }

        /**
         * A chain is considered session-creating (stateful) when it installs the session management
         * or remember-me filters, or maintains concurrent-session control.
         */
        boolean isStateful() {
            return hasFilter("SessionManagementFilter")
                    || hasFilter("RememberMeAuthenticationFilter")
                    || hasFilterContaining("ConcurrentSession");
        }

        boolean isFormOrBasic() {
            return hasFilter("UsernamePasswordAuthenticationFilter")
                    || hasFilter("BasicAuthenticationFilter")
                    || hasFilter("DefaultLoginPageGeneratingFilter");
        }

        boolean hasAuthenticationFilter() {
            return isFormOrBasic()
                    || hasFilterContaining("BearerTokenAuthenticationFilter")
                    || hasFilterContaining("OAuth2LoginAuthenticationFilter")
                    || hasFilterContaining("OAuth2AuthorizationCodeGrantFilter")
                    || hasFilterContaining("AuthenticationFilter");
        }

        boolean hasAuthorizationFilter() {
            return hasFilter("AuthorizationFilter") || hasFilter("FilterSecurityInterceptor");
        }

        boolean matchesAnyRequest() {
            if (matcher == null) {
                return false;
            }
            String normalized = matcher.toLowerCase(Locale.ROOT);
            return normalized.contains("any request") || normalized.contains("anyrequest");
        }

        String describe() {
            return "Chain #" + index + " (" + matcher + ")";
        }
    }

    /**
     * One resolved CORS configuration entry: the path pattern plus the origin and credential settings
     * that matter for advisor checks.
     */
    record CorsConfigModel(
            String pattern,
            List<String> allowedOrigins,
            List<String> allowedOriginPatterns,
            List<String> allowedMethods,
            List<String> allowedHeaders,
            Boolean allowCredentials) {

        CorsConfigModel {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
            allowedOriginPatterns = allowedOriginPatterns == null ? List.of() : List.copyOf(allowedOriginPatterns);
            allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
            allowedHeaders = allowedHeaders == null ? List.of() : List.copyOf(allowedHeaders);
        }

        boolean allowsWildcardOrigin() {
            return allowedOrigins.contains("*") || allowedOriginPatterns.contains("*");
        }

        boolean allowsWildcardMethod() {
            return allowedMethods.contains("*");
        }

        boolean allowsWildcardHeader() {
            return allowedHeaders.contains("*");
        }

        boolean allowsCredentials() {
            return Boolean.TRUE.equals(allowCredentials);
        }

        String describe() {
            String path = (pattern == null || pattern.isBlank()) ? "(all paths)" : pattern;
            List<String> origins = allowedOrigins.isEmpty() ? allowedOriginPatterns : allowedOrigins;
            return path + " allows origins " + origins;
        }
    }

    /**
     * One resolved {@code PasswordEncoder} bean: its fully-qualified type plus, when the encoder is a
     * {@code BCryptPasswordEncoder}, the configured work factor.
     *
     * @param bcryptStrength the BCrypt {@code strength} field when it could be read, {@code null}
     *     otherwise (non-BCrypt encoder or strength not introspectable). A value of {@code -1}
     *     represents the framework default (effective strength 10).
     */
    record PasswordEncoderModel(String type, Integer bcryptStrength) {}
}
