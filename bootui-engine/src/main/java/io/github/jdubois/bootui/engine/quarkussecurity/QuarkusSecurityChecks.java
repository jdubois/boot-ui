package io.github.jdubois.bootui.engine.quarkussecurity;

import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.spi.QuarkusSecurityPermission;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixed Quarkus-native security ruleset (see {@code docs/QUARKUS-CHECKS.md}). Each rule inspects the
 * neutral {@link QuarkusSecuritySnapshot} and, when triggered, emits one {@link SecurityRuleResultDto}
 * with status {@code VIOLATION}. The full set evaluated is {@link #ruleCount()}; only violations are returned.
 */
final class QuarkusSecurityChecks {

    private static final String VIOLATION = "VIOLATION";
    private static final int RULE_COUNT = 25;
    private static final String GUIDE = "https://quarkus.io/guides/security-overview";
    private static final Pattern MAX_AGE = Pattern.compile("max-age\\s*=\\s*(\\d+)");
    private static final long HSTS_MIN_MAX_AGE = 31536000L;

    private QuarkusSecurityChecks() {}

    static int ruleCount() {
        return RULE_COUNT;
    }

    static List<SecurityRuleResultDto> evaluate(QuarkusSecuritySnapshot s) {
        List<SecurityRuleResultDto> v = new ArrayList<>();

        if (!s.anyAuthMechanism() && s.permissions().isEmpty() && s.annotationCount() == 0 && s.endpointCount() > 0) {
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
        if (s.formAuth() && !s.csrfPresent()) {
            v.add(rule(
                    "QS-AUTH-003",
                    "Form authentication without CSRF protection",
                    "Authentication",
                    "HIGH",
                    "Cookie-based form authentication is enabled but the CSRF reactive filter is absent, leaving"
                            + " state-changing requests open to cross-site request forgery.",
                    1,
                    List.of("quarkus.http.auth.form.enabled=true, quarkus-csrf-reactive absent"),
                    "Add the quarkus-csrf-reactive extension and embed the CSRF token in forms."));
        }
        if (s.jwtConfigured() && !s.jwtIssuerConfigured()) {
            v.add(rule(
                    "QS-AUTH-004",
                    "JWT verification without an expected issuer",
                    "Authentication",
                    "MEDIUM",
                    "SmallRye JWT verification is configured without mp.jwt.verify.issuer, so tokens from any"
                            + " issuer signed with a trusted key are accepted.",
                    1,
                    List.of("mp.jwt.verify.publickey* set, mp.jwt.verify.issuer absent"),
                    "Set mp.jwt.verify.issuer to the expected token issuer."));
        }
        if (s.proactiveAuthDisabled()) {
            v.add(rule(
                    "QS-AUTH-005",
                    "Proactive authentication disabled",
                    "Authentication",
                    "INFO",
                    "quarkus.http.auth.proactive=false defers authentication until a secured resource is hit."
                            + " This is a valid pattern, but unannotated endpoints then run anonymously unless"
                            + " explicitly secured — pair it with deny-by-default.",
                    1,
                    List.of("quarkus.http.auth.proactive=false"),
                    "Confirm this is intentional; enable quarkus.security.jaxrs.deny-unannotated-endpoints."));
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
            if ("permit".equalsIgnoreCase(p.policy()) && isBroadPath(p.paths())) {
                permitAll.add(p.name() + " (" + (p.paths() == null ? "/*" : p.paths()) + ")");
            }
        }
        if (!permitAll.isEmpty()) {
            v.add(rule(
                    "QS-AUTHZ-002",
                    "Permission policy permits all paths",
                    "Authorization",
                    "HIGH",
                    "A permission policy applies permit to a root path (/ or /*), disabling authentication across"
                            + " the whole application.",
                    permitAll.size(),
                    permitAll,
                    "Scope the path, or use policy=authenticated/roles instead of permit."));
        }
        if (s.endpointCount() > 0
                && s.securedEndpointCount() * 2 < s.endpointCount()
                && (s.rolesAllowedCount() + s.authenticatedCount()) > 0) {
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
        if (s.anyAuthMechanism()
                && !s.denyUnannotatedEndpoints()
                && s.endpointCount() > s.securedEndpointCount()
                && !hasBroadProtectivePolicy(s.permissions())) {
            v.add(
                    rule(
                            "QS-AUTHZ-004",
                            "No deny-by-default for unannotated endpoints",
                            "Authorization",
                            "MEDIUM",
                            "Authentication is configured but endpoints without an authorization annotation are reachable"
                                    + " anonymously: deny-unannotated-endpoints is off and no broad permission policy covers them.",
                            s.endpointCount() - s.securedEndpointCount(),
                            List.of((s.endpointCount() - s.securedEndpointCount())
                                    + " endpoint(s) without an authz annotation"),
                            "Set quarkus.security.jaxrs.deny-unannotated-endpoints=true and mark public endpoints @PermitAll."));
        }
        if ("enabled".equals(s.insecureRequests()) && !s.behindProxy()) {
            v.add(rule(
                    "QS-TLS-001",
                    "Insecure requests enabled",
                    "Transport",
                    "LOW",
                    "quarkus.http.insecure-requests=enabled serves plain HTTP. Acceptable in local dev or behind a"
                            + " TLS-terminating proxy; risky if exposed directly.",
                    1,
                    List.of("quarkus.http.insecure-requests=enabled"),
                    "Prefer redirect once TLS is available, or document the terminating proxy."));
        }
        if (!s.sslConfigured() && !s.behindProxy()) {
            v.add(rule(
                    "QS-TLS-002",
                    "No TLS configured",
                    "Transport",
                    "INFO",
                    "No HTTPS keystore/TLS registry is configured. Acceptable behind a terminating proxy.",
                    1,
                    List.of("no quarkus.http.ssl.* / quarkus.tls.*"),
                    "Configure TLS or document the terminating proxy."));
        }
        if (s.tlsTrustAll()) {
            v.add(rule(
                    "QS-TLS-003",
                    "Outbound TLS certificate validation disabled",
                    "Transport",
                    "HIGH",
                    "quarkus.tls.trust-all=true disables certificate validation for all outbound TLS (REST clients,"
                            + " OIDC, datasources), enabling man-in-the-middle attacks.",
                    1,
                    List.of("quarkus.tls.trust-all=true"),
                    "Remove trust-all; import the peer's CA into a trust-store instead."));
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
                    "MEDIUM",
                    "CORS is enabled without pinned origins, allowing any site to call the API.",
                    1,
                    List.of("quarkus.http.cors.origins unset or *"),
                    "Set quarkus.http.cors.origins to explicit origins."));
        }
        if (s.corsEnabled()
                && s.corsCredentials()
                && !wildcardCors
                && (wildcard(s.corsMethods()) || wildcard(s.corsHeaders()))) {
            v.add(rule(
                    "QS-CORS-003",
                    "Credentialed CORS with wildcard methods or headers",
                    "CORS",
                    "MEDIUM",
                    "CORS allows credentials with a wildcard methods/headers list, widening the cross-origin"
                            + " surface even though the origin is pinned.",
                    1,
                    List.of("cors.access-control-allow-credentials=true with cors.methods/headers=*"),
                    "List the exact methods and headers the client needs instead of *."));
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
        if (s.hstsHeader() && isWeakHsts(s.hstsHeaderValue())) {
            v.add(rule(
                    "QS-HDR-002",
                    "Weak Strict-Transport-Security policy",
                    "Headers",
                    "LOW",
                    "The HSTS header has a max-age under one year or omits includeSubDomains, weakening"
                            + " HTTPS enforcement.",
                    1,
                    List.of("Strict-Transport-Security: " + nullToEmpty(s.hstsHeaderValue())),
                    "Use max-age=31536000 (1 year) and add includeSubDomains."));
        }
        if (missingFramingHeaders(s)) {
            v.add(rule(
                    "QS-HDR-003",
                    "Missing clickjacking/MIME-sniffing headers",
                    "Headers",
                    "LOW",
                    "No X-Frame-Options/CSP frame-ancestors (clickjacking) or X-Content-Type-Options=nosniff"
                            + " (MIME sniffing) protection is configured.",
                    1,
                    List.of("X-Frame-Options/frame-ancestors and/or X-Content-Type-Options absent"),
                    "Add X-Frame-Options=DENY (or CSP frame-ancestors 'none') and X-Content-Type-Options=nosniff."));
        }
        if (s.cspHeader() && isWeakCsp(s.cspHeaderValue())) {
            v.add(rule(
                    "QS-HDR-004",
                    "Weak Content-Security-Policy",
                    "Headers",
                    "MEDIUM",
                    "The CSP allows 'unsafe-inline'/'unsafe-eval' or a wildcard script source, undermining its"
                            + " XSS protection.",
                    1,
                    List.of("Content-Security-Policy: " + nullToEmpty(s.cspHeaderValue())),
                    "Remove unsafe-inline/unsafe-eval and wildcard sources; use nonces/hashes for scripts."));
        }
        if (s.oidcTlsVerificationNone()) {
            v.add(rule(
                    "QS-DEV-001",
                    "OIDC TLS verification disabled",
                    "Dev exposure",
                    "MEDIUM",
                    "quarkus.oidc.tls.verification=none disables provider certificate validation. Sometimes used"
                            + " against a local dev provider, but must never reach production.",
                    1,
                    List.of("quarkus.oidc.tls.verification=none"),
                    "Remove the override outside local dev; never ship with verification disabled."));
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
        if (s.oidcConfigured() && !s.oidcAudienceConfigured()) {
            v.add(rule(
                    "QS-OIDC-001",
                    "OIDC without token audience validation",
                    "OIDC",
                    "MEDIUM",
                    "OIDC is configured without quarkus.oidc.token.audience, so a token minted for a different"
                            + " client/audience by the same provider is accepted.",
                    1,
                    List.of("quarkus.oidc.auth-server-url set, quarkus.oidc.token.audience absent"),
                    "Set quarkus.oidc.token.audience to this service's expected audience."));
        }
        boolean oidcWebApp = "web-app".equals(s.oidcApplicationType()) || "hybrid".equals(s.oidcApplicationType());
        if (oidcWebApp && !s.oidcCookieForceSecure() && !s.sslConfigured()) {
            v.add(rule(
                    "QS-OIDC-002",
                    "OIDC web-app session cookie not forced secure",
                    "OIDC",
                    "MEDIUM",
                    "An OIDC web-app stores the session in a cookie but cookie-force-secure is off and the app does"
                            + " not terminate TLS, so the session cookie can travel over plain HTTP.",
                    1,
                    List.of("quarkus.oidc.application-type=" + s.oidcApplicationType()
                            + ", cookie-force-secure=false, no TLS"),
                    "Set quarkus.oidc.authentication.cookie-force-secure=true (required behind a TLS proxy)."));
        }
        if (s.managementEnabled() && s.managementHostNonLoopback()) {
            v.add(rule(
                    "QS-MGMT-001",
                    "Management interface on a non-loopback host",
                    "Management",
                    "LOW",
                    "The separate management interface (health/metrics) binds a non-loopback host, exposing it"
                            + " beyond the local machine. Confirm it is firewalled or authenticated.",
                    1,
                    List.of("quarkus.management.enabled=true on a non-loopback host"),
                    "Bind quarkus.management.host to 127.0.0.1, or protect the management endpoints."));
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

    private static boolean isBroadPath(String paths) {
        if (paths == null || paths.isBlank()) {
            return true;
        }
        for (String p : paths.split(",")) {
            String t = p.trim();
            if (t.equals("/") || t.equals("/*")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBroadProtectivePolicy(List<QuarkusSecurityPermission> perms) {
        for (QuarkusSecurityPermission p : perms) {
            if (!"permit".equalsIgnoreCase(p.policy()) && isBroadPath(p.paths())) {
                return true;
            }
        }
        return false;
    }

    private static boolean wildcard(String csv) {
        return csv != null && csv.contains("*");
    }

    private static boolean isWeakHsts(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.toLowerCase();
        long maxAge = 0L;
        Matcher m = MAX_AGE.matcher(v);
        if (m.find()) {
            try {
                maxAge = Long.parseLong(m.group(1));
            } catch (NumberFormatException ignored) {
                maxAge = 0L;
            }
        }
        return maxAge < HSTS_MIN_MAX_AGE || !v.contains("includesubdomains");
    }

    private static boolean isWeakCsp(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.toLowerCase();
        if (v.contains("'unsafe-inline'") || v.contains("'unsafe-eval'")) {
            return true;
        }
        return v.matches(".*(default-src|script-src)\\s+[^;]*\\*.*");
    }

    private static boolean missingFramingHeaders(QuarkusSecuritySnapshot s) {
        boolean cspFrameAncestors =
                s.cspHeaderValue() != null && s.cspHeaderValue().toLowerCase().contains("frame-ancestors");
        boolean clickjackingProtected = s.xFrameOptionsHeader() || cspFrameAncestors;
        return !clickjackingProtected || !s.xContentTypeOptionsHeader();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
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
