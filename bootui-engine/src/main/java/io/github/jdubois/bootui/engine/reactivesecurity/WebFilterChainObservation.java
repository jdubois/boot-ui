package io.github.jdubois.bootui.engine.reactivesecurity;

import java.util.List;
import java.util.Locale;

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
        boolean formLoginAuthentication) {

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
        return hasWebFilter("HttpsRedirectWebFilter");
    }

    boolean hasHstsWriter() {
        return headerWriterNames.stream()
                .anyMatch(name -> name.contains("Hsts") || name.contains("StrictTransportSecurity"));
    }

    boolean hasFrameOptionsWriter() {
        return headerWriterNames.stream().anyMatch(name -> name.contains("FrameOptions") || name.contains("XFrame"));
    }

    boolean hasCspWriter() {
        return cspPolicyDirectives != null && !cspPolicyDirectives.isBlank();
    }

    boolean hasEnforcingFrameAncestorsPolicy() {
        if (!hasCspWriter() || Boolean.TRUE.equals(cspReportOnly)) {
            return false;
        }
        for (String directive : cspPolicyDirectives.split(";")) {
            String[] parts = directive.trim().toLowerCase(Locale.ROOT).split("\\s+", 2);
            if (parts.length > 0 && "frame-ancestors".equals(parts[0])) {
                if (parts.length < 2 || parts[1].isBlank()) {
                    return false;
                }
                for (String source : parts[1].split("\\s+")) {
                    if (source.contains("*") || source.matches("[a-z][a-z0-9+.-]*:")) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    boolean hasContentTypeOptionsWriter() {
        return headerWriterNames.stream().anyMatch(name -> name.contains("ContentTypeOptions"));
    }

    boolean hasWeakHsts() {
        return hstsMaxAgeSeconds != null && hstsMaxAgeSeconds < HSTS_MIN_MAX_AGE_SECONDS;
    }

    boolean matchesAnyRequest() {
        if (matcher == null) {
            return false;
        }
        String normalized = matcher.toLowerCase(Locale.ROOT).trim();
        return normalized.equals("any request") || normalized.contains("anyrequest") || normalized.contains("[/**]");
    }

    boolean hasAuthenticationFilter() {
        return webFilterNames.stream()
                .anyMatch(name -> (name.contains("Authentication") && name.contains("WebFilter"))
                        || name.contains("OAuth2Login")
                        || name.contains("OidcLogin"));
    }

    String describe() {
        return "Chain " + index + " (" + (matcher != null ? matcher : "unknown matcher") + ")";
    }

    boolean hasObservedInteractiveLoginFilter() {
        return hasWebFilter("OAuth2LoginAuthenticationWebFilter")
                || hasWebFilter("OidcSessionRegistryAuthenticationWebFilter")
                || hasWebFilter("OAuth2AuthorizationCodeGrantWebFilter")
                || formLoginAuthentication;
    }
}
