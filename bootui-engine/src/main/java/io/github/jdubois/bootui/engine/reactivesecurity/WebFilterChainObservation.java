package io.github.jdubois.bootui.engine.reactivesecurity;

import io.github.jdubois.bootui.engine.security.CspPolicy;
import java.util.List;

/**
 * Framework-neutral, read-only observation of one {@code SecurityWebFilterChain} bean, collected by
 * the Spring adapter and consumed by the reactive Spring Security advisor ruleset. Holds no
 * credentials, keys, or session identifiers — only bean class names and header-writer configuration
 * facts.
 *
 * @param index index of this chain among the application's registered {@code SecurityWebFilterChain}
 *     beans (BootUI's own chain already excluded by the adapter)
 * @param matcher a bounded, non-sensitive description of the chain's security matcher
 * @param webFilterNames simple class names of the {@code WebFilter}s installed in the chain
 * @param permitsAllAnonymous legacy best-effort inverse of whether an {@code
 *     AuthorizationWebFilter} was observed ({@code TRUE} when none was found, {@code null} when the
 *     chain's filters could not be collected); this does not reveal the filter's authorization
 *     decisions
 * @param bearerTokenAuthentication whether the chain contains an {@code AuthenticationWebFilter}
 *     backed by Spring Security's reactive bearer-token converter
 * @param headerWriterNames simple class names of the {@code ServerHttpHeadersWriter}s installed by the
 *     chain's {@code HttpHeaderWriterWebFilter}, when one is present
 * @param hstsMaxAgeSeconds the HSTS writer's configured {@code maxAgeInSeconds}, when an HSTS writer
 *     is present and the field could be read
 * @param hstsIncludeSubdomains the HSTS writer's configured {@code includeSubDomains}
 * @param cspPolicyDirectives the CSP writer's configured {@code policyDirectives}
 * @param cspReportOnly whether the CSP writer emits Content-Security-Policy-Report-Only
 * @param headerWritersObserved whether header-writer extraction completed; {@code false} means
 *     rules that require writer details must remain inconclusive
 * @param formLoginAuthentication whether the chain contains an {@code AuthenticationWebFilter}
 *     backed by Spring Security's reactive {@code ServerFormLoginAuthenticationConverter}, i.e. a
 *     {@code formLogin()} chain
 */
public record WebFilterChainObservation(
        int index,
        String matcher,
        List<String> webFilterNames,
        Boolean permitsAllAnonymous,
        boolean bearerTokenAuthentication,
        List<String> headerWriterNames,
        Long hstsMaxAgeSeconds,
        Boolean hstsIncludeSubdomains,
        String cspPolicyDirectives,
        Boolean cspReportOnly,
        boolean headerWritersObserved,
        boolean formLoginAuthentication,
        boolean basicAuthentication,
        boolean authenticationObserved,
        Boolean unconditionalMatcher,
        List<CspObservation> cspPolicies,
        boolean unconditionalHttpsRedirect,
        String analysisFailure) {

    public WebFilterChainObservation(
            int index,
            String matcher,
            List<String> webFilterNames,
            Boolean permitsAllAnonymous,
            boolean bearerTokenAuthentication,
            List<String> headerWriterNames,
            Long hstsMaxAgeSeconds,
            Boolean hstsIncludeSubdomains,
            String cspPolicyDirectives,
            Boolean cspReportOnly,
            boolean headerWritersObserved,
            boolean formLoginAuthentication,
            boolean basicAuthentication,
            boolean authenticationObserved,
            Boolean unconditionalMatcher,
            List<CspObservation> cspPolicies,
            boolean unconditionalHttpsRedirect) {
        this(
                index,
                matcher,
                webFilterNames,
                permitsAllAnonymous,
                bearerTokenAuthentication,
                headerWriterNames,
                hstsMaxAgeSeconds,
                hstsIncludeSubdomains,
                cspPolicyDirectives,
                cspReportOnly,
                headerWritersObserved,
                formLoginAuthentication,
                basicAuthentication,
                authenticationObserved,
                unconditionalMatcher,
                cspPolicies,
                unconditionalHttpsRedirect,
                null);
    }

    public record CspObservation(String policy, Boolean reportOnly) {}

    /** Compatibility constructor; display text does not establish matcher semantics. */
    public WebFilterChainObservation(
            int index,
            String matcher,
            List<String> webFilterNames,
            Boolean permitsAllAnonymous,
            boolean bearerTokenAuthentication,
            List<String> headerWriterNames,
            Long hstsMaxAgeSeconds,
            Boolean hstsIncludeSubdomains,
            String cspPolicyDirectives,
            Boolean cspReportOnly,
            boolean headerWritersObserved,
            boolean formLoginAuthentication) {
        this(
                index,
                matcher,
                webFilterNames,
                permitsAllAnonymous,
                bearerTokenAuthentication,
                headerWriterNames,
                hstsMaxAgeSeconds,
                hstsIncludeSubdomains,
                cspPolicyDirectives,
                cspReportOnly,
                headerWritersObserved,
                formLoginAuthentication,
                false,
                true,
                null,
                cspPolicyDirectives == null
                        ? List.of()
                        : List.of(new CspObservation(cspPolicyDirectives, cspReportOnly)),
                false);
    }

    /** Compatibility constructor for observations created before formLogin() detection was added. */
    public WebFilterChainObservation(
            int index,
            String matcher,
            List<String> webFilterNames,
            Boolean permitsAllAnonymous,
            boolean bearerTokenAuthentication,
            List<String> headerWriterNames,
            Long hstsMaxAgeSeconds,
            Boolean hstsIncludeSubdomains,
            String cspPolicyDirectives,
            Boolean cspReportOnly,
            boolean headerWritersObserved) {
        this(
                index,
                matcher,
                webFilterNames,
                permitsAllAnonymous,
                bearerTokenAuthentication,
                headerWriterNames,
                hstsMaxAgeSeconds,
                hstsIncludeSubdomains,
                cspPolicyDirectives,
                cspReportOnly,
                headerWritersObserved,
                false);
    }

    private static final long HSTS_MIN_MAX_AGE_SECONDS = 31536000L;

    public WebFilterChainObservation {
        webFilterNames = List.copyOf(webFilterNames);
        headerWriterNames = headerWriterNames == null ? List.of() : List.copyOf(headerWriterNames);
        cspPolicies = cspPolicies == null ? List.of() : List.copyOf(cspPolicies);
    }

    /** Compatibility constructor for observations created before header extraction became tri-state. */
    public WebFilterChainObservation(
            int index,
            String matcher,
            List<String> webFilterNames,
            Boolean permitsAllAnonymous,
            boolean bearerTokenAuthentication,
            List<String> headerWriterNames,
            Long hstsMaxAgeSeconds,
            Boolean hstsIncludeSubdomains,
            String cspPolicyDirectives,
            Boolean cspReportOnly) {
        this(
                index,
                matcher,
                webFilterNames,
                permitsAllAnonymous,
                bearerTokenAuthentication,
                headerWriterNames,
                hstsMaxAgeSeconds,
                hstsIncludeSubdomains,
                cspPolicyDirectives,
                cspReportOnly,
                true);
    }

    public WebFilterChainObservation(
            int index,
            String matcher,
            List<String> webFilterNames,
            Boolean permitsAllAnonymous,
            List<String> headerWriterNames,
            Long hstsMaxAgeSeconds,
            Boolean hstsIncludeSubdomains,
            String cspPolicyDirectives,
            Boolean cspReportOnly) {
        this(
                index,
                matcher,
                webFilterNames,
                permitsAllAnonymous,
                false,
                headerWriterNames,
                hstsMaxAgeSeconds,
                hstsIncludeSubdomains,
                cspPolicyDirectives,
                cspReportOnly,
                true);
    }

    boolean hasWebFilter(String simpleName) {
        return webFilterNames.stream().anyMatch(name -> name.equals(simpleName));
    }

    boolean hasAuthorizationWebFilter() {
        return Boolean.TRUE.equals(authorizationFilterPresent());
    }

    boolean filtersObserved() {
        return permitsAllAnonymous != null;
    }

    /**
     * Legacy inverse filter-presence signal. It cannot establish an actual {@code permitAll}
     * authorization decision.
     *
     * @deprecated use {@link #authorizationFilterPresent()} for the directly observed fact
     */
    @Deprecated
    public Boolean permitsAllAnonymous() {
        return permitsAllAnonymous;
    }

    /**
     * Returns the directly observed authorization-filter state without inferring its decisions.
     *
     * @return {@code TRUE} when an {@code AuthorizationWebFilter} was observed, {@code FALSE} when
     *     filters were observed without one, or {@code null} when filter collection failed
     */
    public Boolean authorizationFilterPresent() {
        return permitsAllAnonymous == null ? null : !permitsAllAnonymous;
    }

    boolean hasCsrfWebFilter() {
        return hasWebFilter("CsrfWebFilter");
    }

    boolean hasHeaderWriterWebFilter() {
        return hasWebFilter("HttpHeaderWriterWebFilter");
    }

    boolean hasHttpsRedirectFilter() {
        return unconditionalHttpsRedirect;
    }

    boolean hasHstsWriter() {
        return headerWriterNames.contains("StrictTransportSecurityServerHttpHeadersWriter");
    }

    boolean hasFrameOptionsWriter() {
        return headerWriterNames.contains("XFrameOptionsServerHttpHeadersWriter");
    }

    boolean hasCspWriter() {
        return cspPolicies.stream()
                .anyMatch(policy -> policy.policy() != null && !policy.policy().isBlank());
    }

    boolean hasEnforcingFrameAncestorsPolicy() {
        List<CspObservation> enforcing = cspPolicies.stream()
                .filter(policy -> Boolean.FALSE.equals(policy.reportOnly()))
                .toList();
        return enforcing.size() == 1
                && CspPolicy.analyze(enforcing.get(0).policy()).complete()
                && CspPolicy.analyze(enforcing.get(0).policy()).restrictiveFrameAncestors();
    }

    boolean hasEnforcingFrameAncestorsDirective() {
        return cspPolicies.stream()
                .filter(policy -> Boolean.FALSE.equals(policy.reportOnly()))
                .anyMatch(policy -> CspPolicy.analyze(policy.policy()).frameAncestorsPresent());
    }

    boolean cspObserved() {
        return cspPolicies.stream()
                        .allMatch(policy -> policy.reportOnly() != null
                                && CspPolicy.analyze(policy.policy()).complete())
                && cspPolicies.stream()
                                .filter(policy -> Boolean.FALSE.equals(policy.reportOnly()))
                                .count()
                        <= 1
                && cspPolicies.stream()
                                .filter(policy -> Boolean.TRUE.equals(policy.reportOnly()))
                                .count()
                        <= 1;
    }

    boolean onlyReportOnlyCsp() {
        return hasCspWriter()
                && cspObserved()
                && cspPolicies.stream().allMatch(policy -> Boolean.TRUE.equals(policy.reportOnly()));
    }

    boolean hasContentTypeOptionsWriter() {
        return headerWriterNames.contains("ContentTypeOptionsServerHttpHeadersWriter");
    }

    boolean hasWeakHsts() {
        return hstsMaxAgeSeconds != null && hstsMaxAgeSeconds < HSTS_MIN_MAX_AGE_SECONDS;
    }

    boolean matchesAnyRequest() {
        return Boolean.TRUE.equals(unconditionalMatcher);
    }

    boolean hasAuthenticationFilter() {
        return hasWebFilter("AuthenticationWebFilter") || hasObservedInteractiveLoginFilter();
    }

    String describe() {
        return "Chain " + index + " (" + (matcher != null ? matcher : "unknown matcher") + ")";
    }

    boolean hasObservedInteractiveLoginFilter() {
        return hasWebFilter("OAuth2LoginAuthenticationWebFilter")
                || hasWebFilter("OidcSessionRegistryAuthenticationWebFilter")
                || formLoginAuthentication;
    }
}
