package io.github.jdubois.bootui.engine.quarkussecurity;

import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.spi.QuarkusSecurityPermission;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixed Quarkus-native security ruleset (see {@code docs/QUARKUS-CHECKS.md}). Each rule inspects the
 * neutral {@link QuarkusSecuritySnapshot} and, when triggered, emits one {@link SecurityRuleResultDto}
 * with status {@code VIOLATION}. The full set evaluated is {@link #ruleCount()}; only violations are returned.
 */
final class QuarkusSecurityChecks {

    private static final String VIOLATION = "VIOLATION";
    private static final int RULE_COUNT = 13;
    private static final String GUIDE = "https://quarkus.io/guides/security-overview";

    private QuarkusSecurityChecks() {}

    static int ruleCount() {
        return RULE_COUNT;
    }

    static List<SecurityRuleResultDto> evaluate(QuarkusSecuritySnapshot s) {
        List<SecurityRuleResultDto> v = new ArrayList<>();

        if (!s.anyAuthMechanism() && s.permissions().isEmpty() && s.annotationCount() == 0) {
            v.add(
                    rule(
                            "QS-AUTH-001",
                            "No authentication mechanism configured",
                            "Authentication",
                            "HIGH",
                            "No OIDC, JWT, basic, form, or mTLS authentication is configured and no endpoints are role-protected.",
                            1,
                            List.of("quarkus.oidc / smallrye-jwt / http.auth.basic all absent"),
                            "Add quarkus-oidc, quarkus-smallrye-jwt, or quarkus.http.auth.basic, or protect endpoints with @RolesAllowed."));
        }
        if (s.basicAuth() && "enabled".equals(s.insecureRequests())) {
            v.add(rule(
                    "QS-AUTH-002",
                    "Basic authentication without TLS",
                    "Authentication",
                    "HIGH",
                    "Basic auth is enabled while insecure HTTP is allowed, sending credentials in clear text.",
                    1,
                    List.of("quarkus.http.auth.basic=true, quarkus.http.insecure-requests=enabled"),
                    "Set quarkus.http.insecure-requests=redirect and configure TLS."));
        }
        if (s.permissions().isEmpty() && s.annotationCount() == 0 && s.anyAuthMechanism()) {
            v.add(rule(
                    "QS-AUTHZ-001",
                    "No path or role authorization",
                    "Authorization",
                    "HIGH",
                    "An auth mechanism exists but no permission policies or authorization annotations restrict any endpoint.",
                    1,
                    List.of("no quarkus.http.auth.permission.* and no @RolesAllowed/@Authenticated"),
                    "Add @RolesAllowed/@Authenticated or quarkus.http.auth.permission.* with policy=authenticated."));
        }
        List<String> permitAll = new ArrayList<>();
        for (QuarkusSecurityPermission p : s.permissions()) {
            if ("permit".equalsIgnoreCase(p.policy())
                    && (p.paths() == null || p.paths().contains("/*"))) {
                permitAll.add(p.name() + " (" + p.paths() + ")");
            }
        }
        if (!permitAll.isEmpty()) {
            v.add(rule(
                    "QS-AUTHZ-002",
                    "Permission policy permits all paths",
                    "Authorization",
                    "MEDIUM",
                    "A permission policy uses permit on a broad path, bypassing authentication there.",
                    permitAll.size(),
                    permitAll,
                    "Scope the path or use policy=authenticated/roles instead of permit."));
        }
        if (s.endpointCount() > 0 && s.securedEndpointCount() * 2 < s.endpointCount() && s.annotationCount() > 0) {
            v.add(
                    rule(
                            "QS-AUTHZ-003",
                            "Mostly unsecured endpoints",
                            "Authorization",
                            "LOW",
                            "Fewer than half of discovered endpoints carry an authorization annotation.",
                            s.endpointCount() - s.securedEndpointCount(),
                            List.of(s.securedEndpointCount() + " of " + s.endpointCount() + " endpoints secured"),
                            "Confirm the open endpoints are intentionally public; add @Authenticated/@RolesAllowed otherwise."));
        }
        if ("enabled".equals(s.insecureRequests())) {
            v.add(rule(
                    "QS-TLS-001",
                    "Insecure requests enabled",
                    "Transport",
                    "MEDIUM",
                    "quarkus.http.insecure-requests=enabled serves plain HTTP.",
                    1,
                    List.of("quarkus.http.insecure-requests=enabled"),
                    "Prefer redirect once TLS is available."));
        }
        if (!s.sslConfigured()) {
            v.add(rule(
                    "QS-TLS-002",
                    "No TLS configured",
                    "Transport",
                    "LOW",
                    "No HTTPS keystore/TLS registry is configured. Acceptable behind a terminating proxy.",
                    1,
                    List.of("no quarkus.http.ssl.* / quarkus.tls.*"),
                    "Configure TLS or document the terminating proxy."));
        }
        boolean wildcardCors =
                s.corsEnabled() && (s.corsOrigins() == null || s.corsOrigins().contains("*"));
        if (wildcardCors && s.corsCredentials()) {
            v.add(rule(
                    "QS-CORS-002",
                    "CORS wildcard origin with credentials",
                    "CORS",
                    "CRITICAL",
                    "Credentialed cross-origin requests are allowed from any origin.",
                    1,
                    List.of("quarkus.http.cors.origins=* with allow-credentials=true"),
                    "Pin explicit origins; never combine wildcard with credentials."));
        } else if (wildcardCors) {
            v.add(rule(
                    "QS-CORS-001",
                    "CORS allows any origin",
                    "CORS",
                    "HIGH",
                    "CORS is enabled without pinned origins, allowing any site to call the API.",
                    1,
                    List.of("quarkus.http.cors.origins unset or *"),
                    "Set quarkus.http.cors.origins to explicit origins."));
        }
        if (!s.hstsHeader() && !s.cspHeader()) {
            v.add(rule(
                    "QS-HDR-001",
                    "No security headers configured",
                    "Headers",
                    "LOW",
                    "No Strict-Transport-Security or Content-Security-Policy response headers are configured.",
                    1,
                    List.of("quarkus.http.header.* absent"),
                    "Add HSTS and CSP headers via quarkus.http.header.\"...\"."));
        }
        if (s.oidcTlsVerificationNone()) {
            v.add(rule(
                    "QS-DEV-001",
                    "OIDC TLS verification disabled",
                    "Dev exposure",
                    "HIGH",
                    "quarkus.oidc.tls.verification=none disables provider certificate validation.",
                    1,
                    List.of("quarkus.oidc.tls.verification=none"),
                    "Remove the override; never ship with verification disabled."));
        }
        if (s.swaggerUiAlwaysInclude() || s.openApiAlwaysInclude()) {
            v.add(rule(
                    "QS-DEV-002",
                    "OpenAPI/Swagger UI always included",
                    "Dev exposure",
                    "MEDIUM",
                    "API documentation is exposed in all profiles, including production.",
                    1,
                    List.of("swagger-ui/smallrye-openapi always-include=true"),
                    "Restrict to dev, or remove always-include."));
        }
        if (!s.suspectedSecretKeys().isEmpty()) {
            v.add(rule(
                    "QS-CFG-001",
                    "Possible secret in configuration",
                    "Config hygiene",
                    "CRITICAL",
                    "Configuration keys look like literal passwords/secrets/tokens.",
                    s.suspectedSecretKeys().size(),
                    s.suspectedSecretKeys(),
                    "Move secrets to a vault or environment variables; never commit literals."));
        }
        return v;
    }

    private static SecurityRuleResultDto rule(
            String id,
            String name,
            String category,
            String severity,
            String description,
            int count,
            List<String> samples,
            String recommendation) {
        return new SecurityRuleResultDto(
                id, name, category, severity, description, VIOLATION, count, samples, recommendation, GUIDE);
    }
}
