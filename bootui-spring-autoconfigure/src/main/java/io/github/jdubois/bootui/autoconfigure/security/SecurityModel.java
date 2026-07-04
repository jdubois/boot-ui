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
     * @param authorizationRuleShadowed {@code TRUE} when an earlier, broader {@code
     *     authorizeHttpRequests} matcher in this chain shadows a later, narrower one (so the later
     *     rule can never take effect), {@code FALSE} when no shadowing was detected, {@code null}
     *     when the chain's {@code AuthorizationManager} could not be introspected
     * @param rememberMeKeyLength the length of the configured remember-me signing key, when a
     *     {@code RememberMeAuthenticationFilter} is present and its key could be read, {@code null}
     *     otherwise. Only the length is retained -- never the key itself -- so a short/predictable
     *     key can be flagged without the key value ever leaving this process.
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
            String cspPolicyDirectives,
            Boolean authorizationRuleShadowed,
            Integer rememberMeKeyLength) {

        private static final long HSTS_MIN_MAX_AGE_SECONDS = 31536000L; // HstsHeaderWriter's own 1-year default

        /**
         * Matches a Spring Security 7 {@code PathPatternRequestMatcher} toString of the form
         * {@code "PathPattern [/**]"} or {@code "PathPattern [GET /**]"} (an optional HTTP method
         * followed by the catch-all pattern), so a whole-chain matcher is recognized whether or not
         * it is method-qualified, without mistaking a scoped pattern like {@code "PathPattern
         * [/api/**]"} for a catch-all.
         */
        private static final Pattern CATCH_ALL_BRACKETED_PATTERN = Pattern.compile("\\[(?:[a-z]+\\s+)?/\\*\\*]");

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
                    null,
                    null,
                    null);
        }

        /**
         * Convenience constructor for callers that need the HSTS/CSP policy details but predate the
         * authorization-shadowing and remember-me-key fields.
         */
        FilterChainModel(
                int index,
                String matcher,
                List<String> filterNames,
                Boolean permitsAllAnonymous,
                Boolean sessionFixationDisabled,
                List<String> headerWriterNames,
                Long hstsMaxAgeSeconds,
                Boolean hstsIncludeSubdomains,
                String cspPolicyDirectives) {
            this(
                    index,
                    matcher,
                    filterNames,
                    permitsAllAnonymous,
                    sessionFixationDisabled,
                    headerWriterNames,
                    hstsMaxAgeSeconds,
                    hstsIncludeSubdomains,
                    cspPolicyDirectives,
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
         * {@code 'unsafe-inline'} / {@code 'unsafe-eval'}, a bare/unscoped wildcard {@code *} source
         * in {@code default-src}/{@code script-src} (a scoped wildcard such as {@code
         * https://*.example.com} is not flagged), or omits the {@code base-uri} / {@code
         * frame-ancestors} hardening directives entirely (neither falls back to {@code default-src}
         * per the CSP spec, unlike {@code object-src}). {@code false} when no CSP writer was detected
         * or its policy could not be read.
         */
        boolean hasWeakCsp() {
            if (cspPolicyDirectives == null) {
                return false;
            }
            String normalized = cspPolicyDirectives.toLowerCase(Locale.ROOT);
            if (normalized.contains("'unsafe-inline'") || normalized.contains("'unsafe-eval'")) {
                return true;
            }
            if (hasUnscopedWildcardSource(normalized, "default-src")
                    || hasUnscopedWildcardSource(normalized, "script-src")) {
                return true;
            }
            if (!hasDirective(normalized, "base-uri") || !hasDirective(normalized, "frame-ancestors")) {
                return true;
            }
            // object-src falls back to default-src per the CSP spec, so only flag its absence when
            // default-src is also missing (nothing would restrict plugin/object content at all).
            return !hasDirective(normalized, "object-src") && !hasDirective(normalized, "default-src");
        }

        /**
         * {@code true} when the named directive's source list includes a bare, unscoped {@code *}
         * token -- as opposed to a scoped wildcard such as {@code https://*.example.com}, which
         * legitimately restricts the wildcard to a single trusted registrable domain and is not
         * flagged.
         */
        private static boolean hasUnscopedWildcardSource(String normalizedPolicy, String directive) {
            for (String segment : normalizedPolicy.split(";")) {
                String remainder = directiveValue(segment, directive);
                if (remainder == null) {
                    continue;
                }
                for (String token : remainder.split("\\s+")) {
                    if (token.equals("*")) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** {@code true} when the named directive appears anywhere in the policy (with any value). */
        private static boolean hasDirective(String normalizedPolicy, String directive) {
            for (String segment : normalizedPolicy.split(";")) {
                if (directiveValue(segment, directive) != null) {
                    return true;
                }
            }
            return false;
        }

        /**
         * When {@code segment} (one {@code ;}-delimited part of a CSP policy) is the named directive,
         * returns its value portion (possibly empty); otherwise returns {@code null}.
         */
        private static String directiveValue(String segment, String directive) {
            String trimmed = segment.trim();
            if (trimmed.equals(directive)) {
                return "";
            }
            if (trimmed.startsWith(directive + " ") || trimmed.startsWith(directive + "\t")) {
                return trimmed.substring(directive.length()).trim();
            }
            return null;
        }

        /**
         * A chain is considered session-creating (stateful) when it installs the session management
         * or remember-me filters, maintains concurrent-session control, or runs an interactive
         * form-login or OAuth2/OIDC login flow. Spring Security 6 no longer installs a {@code
         * SessionManagementFilter} by default, so a normal form-login chain that still creates HTTP
         * sessions would otherwise look stateless here; the interactive-login signal restores that.
         * {@code OAuth2LoginAuthenticationFilter} is included because the authorization_code login
         * flow stores request state (state/nonce/PKCE, the pre-auth redirect target) in the HTTP
         * session just like form login does. A chain that also accepts bearer tokens is treated as a
         * stateless token API and is excluded from the interactive-login heuristic.
         */
        boolean isStateful() {
            if (hasFilter("SessionManagementFilter")
                    || hasFilter("RememberMeAuthenticationFilter")
                    || hasFilterContaining("ConcurrentSession")) {
                return true;
            }
            boolean interactiveLogin = hasFilter("UsernamePasswordAuthenticationFilter")
                    || hasFilter("DefaultLoginPageGeneratingFilter")
                    || hasFilterContaining("OAuth2LoginAuthenticationFilter");
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
            // matched only when delimited -- standing alone, quoted, or bracketed (optionally with a
            // leading HTTP method inside the brackets, e.g. Spring Security 7's
            // PathPatternRequestMatcher toString "PathPattern [/**]" / "PathPattern [GET /**]") -- so
            // scoped patterns like "/api/**" or "PathPattern [/api/**]" are not mistaken for a
            // catch-all.
            return normalized.equals("/**")
                    || normalized.contains("'/**'")
                    || normalized.contains("\"/**\"")
                    || CATCH_ALL_BRACKETED_PATTERN.matcher(normalized).find();
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
