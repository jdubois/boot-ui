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
 * @param permitsAllAnonymous best-effort result of whether the chain permits all requests without
 *     authorization ({@code TRUE} when no {@code AuthorizationWebFilter} is found, {@code null} when
 *     it could not be determined)
 * @param bearerTokenAuthentication whether the chain contains an {@code AuthenticationWebFilter}
 *     backed by Spring Security's reactive bearer-token converter
 * @param headerWriterNames simple class names of the {@code ServerHttpHeadersWriter}s installed by the
 *     chain's {@code HttpHeaderWriterWebFilter}, when one is present
 * @param hstsMaxAgeSeconds the HSTS writer's configured {@code maxAgeInSeconds}, when an HSTS writer
 *     is present and the field could be read
 * @param hstsIncludeSubdomains the HSTS writer's configured {@code includeSubDomains}
 * @param cspPolicyDirectives the CSP writer's configured {@code policyDirectives}
 * @param cspReportOnly whether the CSP writer emits Content-Security-Policy-Report-Only
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
        Boolean cspReportOnly) {

    private static final long HSTS_MIN_MAX_AGE_SECONDS = 31536000L;

    public WebFilterChainObservation {
        webFilterNames = List.copyOf(webFilterNames);
        headerWriterNames = headerWriterNames == null ? List.of() : List.copyOf(headerWriterNames);
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
                cspReportOnly);
    }

    boolean hasWebFilter(String simpleName) {
        return webFilterNames.stream().anyMatch(name -> name.equals(simpleName));
    }

    boolean hasAuthorizationWebFilter() {
        return hasWebFilter("AuthorizationWebFilter");
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

    boolean isStateful() {
        // Reactive chains using OAuth2 login are stateful; pure bearer-token resource servers are
        // stateless.
        return hasWebFilter("OAuth2LoginAuthenticationWebFilter")
                || hasWebFilter("OAuth2AuthorizationCodeGrantWebFilter");
    }
}
