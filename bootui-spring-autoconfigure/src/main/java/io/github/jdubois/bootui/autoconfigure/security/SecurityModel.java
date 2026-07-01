package io.github.jdubois.bootui.autoconfigure.security;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

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
     * @param hstsMaxAgeSeconds the {@code HstsHeaderWriter}'s configured {@code maxAgeInSeconds},
     *     when an HSTS writer is present and the field could be read, {@code null} otherwise
     * @param hstsIncludeSubdomains the {@code HstsHeaderWriter}'s configured
     *     {@code includeSubDomains}, when an HSTS writer is present and the field could be read,
     *     {@code null} otherwise
     * @param cspPolicyDirectives the {@code ContentSecurityPolicyHeaderWriter}'s configured
     *     {@code policyDirectives}, when a CSP writer is present and the field could be read,
     *     {@code null} otherwise
     */
    record FilterChainModel(
            int index,
            String matcher,
            List<String> filterNames,
            Boolean permitsAllAnonymous,
            Boolean sessionFixationDisabled,
            List<String> headerWriterNames,
            Long hstsMaxAgeSeconds,
            Boolean hstsIncludeSubdomains,
            String cspPolicyDirectives) {

        private static final long HSTS_MIN_MAX_AGE_SECONDS = 31536000L; // HstsHeaderWriter's own 1-year default

        private static final Pattern CSP_WILDCARD_SOURCE = Pattern.compile(".*(default-src|script-src)\\s+[^;]*\\*.*");

        FilterChainModel {
            filterNames = List.copyOf(filterNames);
            headerWriterNames = headerWriterNames == null ? List.of() : List.copyOf(headerWriterNames);
        }

        /**
         * Convenience constructor for callers that do not need the HSTS/CSP policy details (e.g.
         * chains with no HSTS or CSP writer, or existing tests built before those fields existed).
         */
        FilterChainModel(
                int index,
                String matcher,
                List<String> filterNames,
                Boolean permitsAllAnonymous,
                Boolean sessionFixationDisabled,
                List<String> headerWriterNames) {
            this(
                    index,
                    matcher,
                    filterNames,
                    permitsAllAnonymous,
                    sessionFixationDisabled,
                    headerWriterNames,
                    null,
                    null,
                    null);
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
         * {@code true} when an {@code HstsHeaderWriter} is present but configured with a max-age
         * under one year or without {@code includeSubDomains}, weakening the protocol-downgrade and
         * cookie-hijacking protection HSTS is meant to provide. {@code false} when no HSTS writer was
         * detected or its fields could not be read.
         */
        boolean hasWeakHsts() {
            if (hstsMaxAgeSeconds == null) {
                return false;
            }
            return hstsMaxAgeSeconds < HSTS_MIN_MAX_AGE_SECONDS || Boolean.FALSE.equals(hstsIncludeSubdomains);
        }

        /**
         * {@code true} when a {@code ContentSecurityPolicyHeaderWriter} policy allows
         * {@code 'unsafe-inline'} / {@code 'unsafe-eval'} or a wildcard {@code default-src}/
         * {@code script-src}, which largely defeats the XSS mitigation a CSP is meant to provide.
         * {@code false} when no CSP writer was detected or its policy could not be read.
         */
        boolean hasWeakCsp() {
            if (cspPolicyDirectives == null) {
                return false;
            }
            String normalized = cspPolicyDirectives.toLowerCase(Locale.ROOT);
            if (normalized.contains("'unsafe-inline'") || normalized.contains("'unsafe-eval'")) {
                return true;
            }
            return CSP_WILDCARD_SOURCE.matcher(normalized).matches();
        }

        /**
         * A chain is considered session-creating (stateful) when it installs the session management
         * or remember-me filters, maintains concurrent-session control, or runs an interactive
         * form-login flow. Spring Security 6 no longer installs a {@code SessionManagementFilter} by
         * default, so a normal form-login chain that still creates HTTP sessions would otherwise look
         * stateless here; the form-login signal restores that. A chain that also accepts bearer tokens
         * is treated as a stateless token API and is excluded from the form-login heuristic.
         */
        boolean isStateful() {
            if (hasFilter("SessionManagementFilter")
                    || hasFilter("RememberMeAuthenticationFilter")
                    || hasFilterContaining("ConcurrentSession")) {
                return true;
            }
            boolean interactiveLogin =
                    hasFilter("UsernamePasswordAuthenticationFilter") || hasFilter("DefaultLoginPageGeneratingFilter");
            return interactiveLogin && !hasFilterContaining("BearerTokenAuthenticationFilter");
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

        /**
         * Like {@link #hasAuthenticationFilter()} but excludes the {@code AnonymousAuthenticationFilter}
         * that Spring Security installs on every chain. Used by rules that must distinguish a chain
         * configuring a real authentication mechanism (form, basic, bearer, OAuth2, SAML, X.509, CAS,
         * custom {@code *AuthenticationFilter}, ...) from one that only ever sees anonymous callers.
         */
        boolean hasRealAuthenticationFilter() {
            return filterNames.stream()
                            .anyMatch(name -> name.endsWith("AuthenticationFilter")
                                    && !name.equals("AnonymousAuthenticationFilter"))
                    || hasFilterContaining("OAuth2AuthorizationCodeGrantFilter");
        }

        boolean hasAuthorizationFilter() {
            return hasFilter("AuthorizationFilter") || hasFilter("FilterSecurityInterceptor");
        }

        boolean matchesAnyRequest() {
            if (matcher == null) {
                return false;
            }
            String normalized = matcher.toLowerCase(Locale.ROOT).trim();
            if (normalized.contains("any request") || normalized.contains("anyrequest")) {
                return true;
            }
            // An explicit whole-application matcher such as securityMatcher("/**"). The "/**" token is
            // matched only when delimited (standing alone, quoted, or bracketed) so scoped patterns
            // like "/api/**" are not mistaken for a catch-all.
            return normalized.equals("/**")
                    || normalized.contains("'/**'")
                    || normalized.contains("\"/**\"")
                    || normalized.contains("[/**]");
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

        /**
         * The configured {@code allowedOriginPatterns} that are dangerously broad (wildcard scheme,
         * wildcard host, or a too-permissive suffix such as {@code *.com}), excluding the exact
         * {@code "*"} pattern already covered by SEC-CORS-001/002. Scoped subdomain wildcards such as
         * {@code https://*.example.com} are intentionally not flagged.
         */
        List<String> broadOriginPatterns() {
            return allowedOriginPatterns.stream()
                    .filter(CorsConfigModel::isBroadOriginPattern)
                    .toList();
        }

        static boolean isBroadOriginPattern(String pattern) {
            if (pattern == null) {
                return false;
            }
            String value = pattern.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty() || !value.contains("*") || value.equals("*")) {
                return false; // exact "*" is handled by SEC-CORS-001/002
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
