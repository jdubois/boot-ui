package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.engine.security.CspPolicy;
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
     * @param permitsAllAnonymous structural unconditional-grant observation; no authorization
     *     manager or matcher is executed. {@code null} denotes unsupported or incomplete structure.
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
     * @param cspReportOnly whether the CSP writer emits Content-Security-Policy-Report-Only instead of
     *     an enforcing policy
     * @param authorizationRuleShadowed {@code TRUE} when an earlier, broader {@code
     *     authorizeHttpRequests} matcher in this chain shadows a later, narrower one (so the later
     *     rule can never take effect), {@code FALSE} when no shadowing was detected, {@code null}
     *     when the chain's {@code AuthorizationManager} was absent, wrapped by an unrecognized
     *     implementation, or could not be introspected
     * @param rememberMeKeyLength the length of the configured remember-me signing key, when a
     *     {@code RememberMeAuthenticationFilter} is present and its key could be read, {@code null}
     *     otherwise. Only the length is retained -- never the key itself -- so a short/predictable
     *     key can be flagged without the key value ever leaving this process.
     * @param statelessSecurityContext holder-filter repository scope; this does not establish the
     *     authentication filters' save behavior
     * @param matchesActuatorPath legacy compatibility field; exact operation scope is in {@code details}
     * @param actuatorAnonymousAllowed legacy compatibility field; exact authorization facts are in {@code details}
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
            Boolean cspReportOnly,
            Boolean authorizationRuleShadowed,
            Integer rememberMeKeyLength,
            Boolean statelessSecurityContext,
            Boolean matchesActuatorPath,
            Boolean actuatorAnonymousAllowed,
            ChainDetails details) {

        FilterChainModel(
                int index,
                String matcher,
                List<String> filterNames,
                Boolean permitsAllAnonymous,
                Boolean sessionFixationDisabled,
                List<String> headerWriterNames,
                Long hstsMaxAgeSeconds,
                Boolean hstsIncludeSubdomains,
                String cspPolicyDirectives,
                Boolean cspReportOnly,
                Boolean authorizationRuleShadowed,
                Integer rememberMeKeyLength,
                Boolean statelessSecurityContext,
                Boolean matchesActuatorPath,
                Boolean actuatorAnonymousAllowed) {
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
                    cspReportOnly,
                    authorizationRuleShadowed,
                    rememberMeKeyLength,
                    statelessSecurityContext,
                    matchesActuatorPath,
                    actuatorAnonymousAllowed,
                    new ChainDetails(
                            true,
                            true,
                            "any request".equals(matcher) || "/**".equals(matcher),
                            null,
                            List.of(),
                            null,
                            false));
        }

        private static final long HSTS_MIN_MAX_AGE_SECONDS = 31536000L; // HstsHeaderWriter's own 1-year default

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
                    cspPolicyDirectives == null ? null : Boolean.FALSE,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        FilterChainModel(
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
                    cspPolicyDirectives == null ? null : Boolean.FALSE,
                    authorizationRuleShadowed,
                    rememberMeKeyLength,
                    null,
                    null,
                    null);
        }

        /**
         * Convenience constructor for callers that predate exact operation observations.
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
                String cspPolicyDirectives,
                Boolean cspReportOnly,
                Boolean authorizationRuleShadowed,
                Integer rememberMeKeyLength,
                Boolean statelessSecurityContext) {
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
                    cspReportOnly,
                    authorizationRuleShadowed,
                    rememberMeKeyLength,
                    statelessSecurityContext,
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

        boolean hasCspDirective(String directive) {
            if ("frame-ancestors".equals(directive)) {
                return Boolean.FALSE.equals(cspReportOnly)
                        && CspPolicy.analyze(cspPolicyDirectives).restrictiveFrameAncestors();
            }
            return Boolean.FALSE.equals(cspReportOnly)
                    && cspPolicyDirectives != null
                    && hasDirective(cspPolicyDirectives.toLowerCase(Locale.ROOT), directive.toLowerCase(Locale.ROOT));
        }

        Boolean framingProtected() {
            if (!details.headersKnown()) return null;
            boolean frameOptions = hasHeaderWriterContaining("XFrameOptions");
            if (!hasHeaderWriterContaining("ContentSecurityPolicy") || Boolean.TRUE.equals(cspReportOnly)) {
                return frameOptions;
            }
            if (!Boolean.FALSE.equals(cspReportOnly)) return null;
            CspPolicy.Analysis policy = CspPolicy.analyze(cspPolicyDirectives);
            if (!policy.complete()) return null;
            return policy.frameAncestorsPresent() ? policy.restrictiveFrameAncestors() : frameOptions;
        }

        /**
         * Whether a known HSTS policy is shorter than the framework's one-year default.
         */
        boolean hasWeakHsts() {
            if (!details.headersKnown() || hstsMaxAgeSeconds == null) {
                return false;
            }
            return hstsMaxAgeSeconds < HSTS_MIN_MAX_AGE_SECONDS;
        }

        /**
         * Whether the bounded shared parser recognizes permissive effective script controls.
         */
        boolean hasWeakCsp() {
            CspPolicy.Analysis policy = CspPolicy.analyze(cspPolicyDirectives);
            return details.headersKnown()
                    && Boolean.FALSE.equals(cspReportOnly)
                    && policy.complete()
                    && (policy.unsafeInlineScript() || policy.unsafeEvalScript() || policy.unrestrictedScript());
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

        /** Legacy contextual session heuristic, not a CSRF or bearer-persistence observation. */
        boolean isStateful() {
            if (hasFilter("RememberMeAuthenticationFilter") || hasFilterContaining("ConcurrentSession")) {
                return true;
            }
            if (Boolean.TRUE.equals(statelessSecurityContext)) {
                return false;
            }
            if (hasFilter("SessionManagementFilter")) {
                return true;
            }
            boolean interactiveLogin = hasFilter("UsernamePasswordAuthenticationFilter")
                    || hasFilterContaining("OAuth2LoginAuthenticationFilter");
            return interactiveLogin;
        }

        boolean browserCredentials() {
            return hasFilter("UsernamePasswordAuthenticationFilter")
                    || hasFilter("OAuth2LoginAuthenticationFilter")
                    || hasFilter("RememberMeAuthenticationFilter");
        }

        boolean isFormOrBasic() {
            return hasFilter("UsernamePasswordAuthenticationFilter") || hasFilter("BasicAuthenticationFilter");
        }

        boolean hasAuthenticationFilter() {
            return isFormOrBasic()
                    || hasFilterContaining("BearerTokenAuthenticationFilter")
                    || hasFilterContaining("OAuth2LoginAuthenticationFilter")
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
                    .anyMatch(name ->
                            name.endsWith("AuthenticationFilter") && !name.equals("AnonymousAuthenticationFilter"));
        }

        boolean hasAuthorizationFilter() {
            return hasFilter("AuthorizationFilter") || hasFilter("FilterSecurityInterceptor");
        }

        boolean matchesAnyRequest() {
            return details.unconditional();
        }

        /**
         * {@code true} when this chain's {@code securityMatcher} description mentions the actuator
         * base path. Only a textual signal, used as a fallback when {@link #matchesActuatorPath()}
         * could not be evaluated: a whole-application chain that protects the actuator through its
         * authorization rules alone renders as {@code any request} and is not recognized here.
         */
        boolean matcherReferences(String basePath) {
            return matcher != null
                    && basePath != null
                    && matcher.toLowerCase(Locale.ROOT).contains(basePath.toLowerCase(Locale.ROOT));
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
            Boolean allowCredentials,
            Integer ownerChainIndex) {

        CorsConfigModel(
                String pattern,
                List<String> allowedOrigins,
                List<String> allowedOriginPatterns,
                List<String> allowedMethods,
                List<String> allowedHeaders,
                Boolean allowCredentials) {
            this(
                    pattern,
                    allowedOrigins,
                    allowedOriginPatterns,
                    allowedMethods,
                    allowedHeaders,
                    allowCredentials,
                    null);
        }

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
            if (value.equals("**")) {
                return true;
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
                return rest.isEmpty();
            }
            return false;
        }

        String describe() {
            return ownerChainIndex == null
                    ? "An attached CORS policy"
                    : "An attached CORS policy in chain #" + ownerChainIndex;
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

    record ChainDetails(
            boolean filtersKnown,
            boolean headersKnown,
            boolean unconditional,
            MatcherFacts matcher,
            List<AuthorizationMapping> mappings,
            Boolean bearerSavesSession,
            boolean httpsRedirect) {
        ChainDetails {
            mappings = List.copyOf(mappings);
        }
    }

    record MatcherFacts(String kind, String method, String path, List<MatcherFacts> children) {
        MatcherFacts {
            children = List.copyOf(children);
        }

        Boolean matches(String requestMethod, String requestPath) {
            if ("any".equals(kind)) return true;
            if ("unknown".equals(kind)) return null;
            if ("path".equals(kind)) {
                if (method != null && !method.equals(requestMethod)) return false;
                if ("/**".equals(path)) return true;
                if (path != null
                        && !path.contains("*")
                        && !path.contains("{")
                        && !path.contains("}")
                        && !path.contains("?")) {
                    return path.equals(requestPath);
                }
                if (path != null
                        && path.endsWith("/**")
                        && path.indexOf('*') == path.length() - 2
                        && !path.contains("{")
                        && !path.contains("}")
                        && !path.contains("?")) {
                    String prefix = path.substring(0, path.length() - 3);
                    return requestPath.equals(prefix) || requestPath.startsWith(prefix + "/");
                }
                return null;
            }
            if ("not".equals(kind) && children.size() == 1) {
                Boolean child = children.get(0).matches(requestMethod, requestPath);
                return child == null ? null : !child;
            }
            boolean unknown = false;
            for (MatcherFacts child : children) {
                Boolean result = child.matches(requestMethod, requestPath);
                if (result == null) return null;
                if ("or".equals(kind) && Boolean.TRUE.equals(result)) return true;
                if ("and".equals(kind) && Boolean.FALSE.equals(result)) return false;
                unknown |= result == null;
            }
            return unknown || children.isEmpty() ? null : "and".equals(kind);
        }

        boolean unconditional() {
            if ("or".equals(kind)) {
                for (MatcherFacts child : children) {
                    if (!child.complete()) return false;
                    if (child.unconditional()) return true;
                }
                return false;
            }
            return "any".equals(kind)
                    || ("path".equals(kind) && method == null && "/**".equals(path))
                    || ("and".equals(kind)
                            && !children.isEmpty()
                            && children.stream().allMatch(MatcherFacts::unconditional));
        }

        boolean complete() {
            return !"unknown".equals(kind)
                    && (!"path".equals(kind) || matches(method, "/__bootui_observation__") != null)
                    && children.stream().allMatch(MatcherFacts::complete);
        }
    }

    record AuthorizationMapping(MatcherFacts matcher, Boolean grant) {}
}
