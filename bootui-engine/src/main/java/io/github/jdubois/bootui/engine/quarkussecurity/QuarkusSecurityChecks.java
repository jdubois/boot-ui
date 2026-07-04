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
    private static final int RULE_COUNT = 45;
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
                    "Cookie-based form authentication is enabled but the CSRF filter is absent, leaving"
                            + " state-changing requests open to cross-site request forgery.",
                    1,
                    List.of("quarkus.http.auth.form.enabled=true, quarkus-rest-csrf absent"),
                    "Add the io.quarkus:quarkus-rest-csrf extension and embed the CSRF token in forms."));
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
        if (s.jwtAlgorithmUnpinnedForRemoteJwks()) {
            v.add(rule(
                    "QS-AUTH-006",
                    "JWT signature algorithm not pinned for a remote JWKS",
                    "Authentication",
                    "MEDIUM",
                    "mp.jwt.verify.publickey.location points at a remote (http/https) JWKS endpoint but"
                            + " mp.jwt.verify.publickey.algorithm is left unset, relying on SmallRye JWT's"
                            + " implicit RS256-only default instead of explicitly pinning the expected"
                            + " algorithm(s) for a key source that can rotate/change independently of this"
                            + " application.",
                    1,
                    List.of("mp.jwt.verify.publickey.location=http(s)://…, mp.jwt.verify.publickey.algorithm absent"),
                    "Set mp.jwt.verify.publickey.algorithm explicitly to the algorithm(s) this application expects."));
        }
        if (s.embeddedUsersEnabled()) {
            v.add(rule(
                    "QS-AUTH-007",
                    "Embedded properties-file users enabled",
                    "Authentication",
                    "MEDIUM",
                    "quarkus.security.users.embedded.enabled=true authenticates against a static in-memory/"
                            + "properties-file user list — a convenience meant for demos/tests, not a real"
                            + " identity store.",
                    1,
                    List.of("quarkus.security.users.embedded.enabled=true"),
                    "Use quarkus-elytron-security-jdbc/oidc for real deployments; keep embedded users to %dev/%test."));
        }
        if (s.jwtConfigured() && !s.jwtAudiencesConfigured()) {
            v.add(rule(
                    "QS-AUTH-008",
                    "JWT verification without audience validation",
                    "Authentication",
                    "MEDIUM",
                    "SmallRye JWT verification is configured without mp.jwt.verify.audiences, so a token minted"
                            + " for a different client/service by the same trusted issuer is still accepted.",
                    1,
                    List.of("mp.jwt.verify.publickey* set, mp.jwt.verify.audiences absent"),
                    "Set mp.jwt.verify.audiences to this service's expected audience(s)."));
        }
        if (s.jwtConfigured() && s.jwtInlinePublicKey()) {
            v.add(rule(
                    "QS-AUTH-009",
                    "JWT public key configured inline",
                    "Authentication",
                    "LOW",
                    "mp.jwt.verify.publickey holds a static inline key. Unlike a JWKS location, an inline key"
                            + " cannot be rotated without a redeploy.",
                    1,
                    List.of("mp.jwt.verify.publickey set"),
                    "Prefer mp.jwt.verify.publickey.location pointing at a JWKS endpoint that supports rotation."));
        }
        if (s.jdbcClearPasswordMapperEnabled()) {
            v.add(rule(
                    "QS-AUTH-010",
                    "JDBC identity store using clear-text password mapper",
                    "Authentication",
                    "HIGH",
                    "A quarkus-elytron-security-jdbc principal-query uses the clear-password mapper, meaning"
                            + " passwords are compared/stored in plain text rather than hashed.",
                    1,
                    List.of("principal-query *.clear-password-mapper.enabled=true"),
                    "Switch to bcrypt-password-mapper (or another hashing mapper) and re-hash stored passwords."));
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
            if ("permit".equalsIgnoreCase(p.policy()) && isBroadPath(p.paths()) && appliesToAllMethods(p.methods())) {
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
                    "trust-all=true is set on the default TLS registry bucket or a named bucket"
                            + " (quarkus.tls.<name>.trust-all), disabling certificate validation for outbound"
                            + " TLS (REST clients, OIDC, datasources, gRPC), enabling man-in-the-middle attacks.",
                    1,
                    List.of("quarkus.tls.trust-all=true (default or a named bucket)"),
                    "Remove trust-all; import the peer's CA into a trust-store instead."));
        }
        boolean explicitWildcardCors = s.corsEnabled() && isExplicitWildcardOrigin(s.corsOrigins());
        boolean unsetOriginsCors =
                s.corsEnabled() && (s.corsOrigins() == null || s.corsOrigins().isBlank());
        if (explicitWildcardCors && s.corsCredentials()) {
            v.add(rule(
                    "QS-CORS-002",
                    "CORS wildcard origin with credentials",
                    "CORS",
                    "CRITICAL",
                    "Credentialed cross-origin requests are allowed from any origin.",
                    1,
                    List.of("quarkus.http.cors.origins=" + s.corsOrigins() + " with allow-credentials=true"),
                    "Pin explicit origins; never combine wildcard with credentials."));
        } else if (explicitWildcardCors) {
            v.add(rule(
                    "QS-CORS-001",
                    "CORS allows any origin",
                    "CORS",
                    "MEDIUM",
                    "CORS is enabled with an explicit wildcard origin (* or /.*/), allowing any site to call"
                            + " the API.",
                    1,
                    List.of("quarkus.http.cors.origins=" + s.corsOrigins()),
                    "Set quarkus.http.cors.origins to explicit origins."));
        }
        if (unsetOriginsCors) {
            v.add(rule(
                    "QS-CORS-005",
                    "CORS enabled with no origins configured",
                    "CORS",
                    "INFO",
                    "quarkus.http.cors is enabled but quarkus.http.cors.origins is unset. Quarkus's CORSFilter"
                            + " then only permits same-origin requests (the most restrictive possible outcome),"
                            + " so the filter is effectively inert until origins are configured.",
                    1,
                    List.of("quarkus.http.cors=true, quarkus.http.cors.origins unset"),
                    "If cross-origin access is intended, configure quarkus.http.cors.origins explicitly;"
                            + " otherwise this has no practical effect."));
        }
        if (s.corsEnabled()
                && s.corsCredentials()
                && s.corsOrigins() != null
                && !s.corsOrigins().isBlank()
                && !explicitWildcardCors
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
        if (s.hstsHeader() && isWeakHsts(s.hstsHeaderValue())) {
            v.add(rule(
                    "QS-HDR-001",
                    "Weak Strict-Transport-Security policy",
                    "Headers",
                    "LOW",
                    "The HSTS header has a max-age under one year or omits includeSubDomains, weakening"
                            + " HTTPS enforcement.",
                    1,
                    List.of("Strict-Transport-Security: " + nullToEmpty(s.hstsHeaderValue())),
                    "Use max-age=31536000 (1 year) and add includeSubDomains."));
        }
        if (s.cspHeader() && isWeakCsp(s.cspHeaderValue())) {
            v.add(rule(
                    "QS-HDR-002",
                    "Weak Content-Security-Policy",
                    "Headers",
                    "MEDIUM",
                    "The CSP allows 'unsafe-inline'/'unsafe-eval' or a wildcard script source, undermining its"
                            + " XSS protection.",
                    1,
                    List.of("Content-Security-Policy: " + nullToEmpty(s.cspHeaderValue())),
                    "Remove unsafe-inline/unsafe-eval and wildcard sources; use nonces/hashes for scripts."));
        }
        if (!s.hstsHeader()) {
            v.add(
                    rule(
                            "QS-HDR-003",
                            "Missing Strict-Transport-Security header",
                            "Headers",
                            "LOW",
                            "No Strict-Transport-Security response header is configured, so browsers fall back to"
                                    + " trusting whatever scheme a link/redirect uses instead of enforcing HTTPS.",
                            1,
                            List.of("quarkus.http.header.\"Strict-Transport-Security\".value absent"),
                            "Add quarkus.http.header.\"Strict-Transport-Security\".value=max-age=31536000; includeSubDomains."));
        }
        if (!s.cspHeader()) {
            v.add(rule(
                    "QS-HDR-004",
                    "Missing Content-Security-Policy header",
                    "Headers",
                    "LOW",
                    "No Content-Security-Policy response header is configured, losing a defense-in-depth"
                            + " control against XSS and data-injection attacks.",
                    1,
                    List.of("quarkus.http.header.\"Content-Security-Policy\".value absent"),
                    "Add a Content-Security-Policy tailored to the app's script/style/asset origins."));
        }
        boolean cspFrameAncestors =
                s.cspHeaderValue() != null && s.cspHeaderValue().toLowerCase().contains("frame-ancestors");
        if (!s.xFrameOptionsHeader() && !cspFrameAncestors) {
            v.add(rule(
                    "QS-HDR-005",
                    "Missing clickjacking protection",
                    "Headers",
                    "LOW",
                    "Neither X-Frame-Options nor a CSP frame-ancestors directive is configured, so the app can"
                            + " be embedded in a hidden/opaque iframe on an attacker's page (clickjacking).",
                    1,
                    List.of("X-Frame-Options and CSP frame-ancestors both absent"),
                    "Add quarkus.http.header.\"X-Frame-Options\".value=DENY (or a CSP frame-ancestors 'none')."));
        }
        if (!s.xContentTypeOptionsHeader()) {
            v.add(rule(
                    "QS-HDR-006",
                    "Missing X-Content-Type-Options header",
                    "Headers",
                    "LOW",
                    "No X-Content-Type-Options=nosniff response header is configured, allowing browsers to"
                            + " MIME-sniff responses and potentially execute content served with the wrong"
                            + " Content-Type.",
                    1,
                    List.of("quarkus.http.header.\"X-Content-Type-Options\".value absent"),
                    "Add quarkus.http.header.\"X-Content-Type-Options\".value=nosniff."));
        }
        if (!s.referrerPolicyHeader()) {
            v.add(
                    rule(
                            "QS-HDR-007",
                            "Missing Referrer-Policy header",
                            "Headers",
                            "INFO",
                            "No Referrer-Policy response header is configured, so browsers may forward the full"
                                    + " request URL (including any sensitive query parameters) to third-party sites"
                                    + " linked from the app.",
                            1,
                            List.of("quarkus.http.header.\"Referrer-Policy\".value absent"),
                            "Add quarkus.http.header.\"Referrer-Policy\".value=strict-origin-when-cross-origin (or stricter)."));
        }
        if (!s.permissionsPolicyHeader()) {
            v.add(rule(
                    "QS-HDR-008",
                    "Missing Permissions-Policy header",
                    "Headers",
                    "INFO",
                    "No Permissions-Policy response header is configured, leaving browser features (camera,"
                            + " microphone, geolocation, …) at their default availability instead of explicitly"
                            + " disabled where unused.",
                    1,
                    List.of("quarkus.http.header.\"Permissions-Policy\".value absent"),
                    "Add quarkus.http.header.\"Permissions-Policy\".value listing only the features the app uses."));
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
        if (s.swaggerUiAlwaysInclude() || s.openApiAlwaysInclude() || s.graphqlUiAlwaysInclude()) {
            List<String> alwaysIncluded = new ArrayList<>();
            if (s.swaggerUiAlwaysInclude()) {
                alwaysIncluded.add("swagger-ui.always-include=true");
            }
            if (s.openApiAlwaysInclude()) {
                alwaysIncluded.add("smallrye-openapi.always-include=true");
            }
            if (s.graphqlUiAlwaysInclude()) {
                alwaysIncluded.add("smallrye-graphql.ui.always-include=true");
            }
            v.add(rule(
                    "QS-DEV-002",
                    "OpenAPI/Swagger/GraphQL UI always included",
                    "Dev exposure",
                    "MEDIUM",
                    "API documentation and/or the GraphQL UI is exposed in all profiles, including production.",
                    alwaysIncluded.size(),
                    alwaysIncluded,
                    "Restrict to dev, or remove always-include."));
        }
        if (s.healthUiAlwaysInclude()) {
            v.add(rule(
                    "QS-DEV-003",
                    "SmallRye Health UI always included",
                    "Dev exposure",
                    "LOW",
                    "quarkus.smallrye-health.ui.always-include=true exposes the Health UI in every profile,"
                            + " including production, revealing the app's health-check topology to anyone who"
                            + " can reach it.",
                    1,
                    List.of("quarkus.smallrye-health.ui.always-include=true"),
                    "Remove the override so the Health UI is only available outside production, or protect it"
                            + " via the management interface / a permission policy."));
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
        if (oidcWebApp && !s.oidcHasClientSecret() && !s.oidcPkceRequired()) {
            v.add(rule(
                    "QS-OIDC-003",
                    "Public OIDC client without PKCE",
                    "OIDC",
                    "MEDIUM",
                    "An OIDC web-app/hybrid client has no client secret configured (a public client, e.g. an SPA"
                            + " or mobile app) and quarkus.oidc.authentication.pkce-required is not enabled,"
                            + " leaving the authorization-code flow vulnerable to interception.",
                    1,
                    List.of("quarkus.oidc.application-type=" + s.oidcApplicationType()
                            + ", no client secret, pkce-required=false"),
                    "Set quarkus.oidc.authentication.pkce-required=true for public clients."));
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
        if ("/".equals(s.nonApplicationRootPath())) {
            v.add(rule(
                    "QS-MGMT-002",
                    "Non-application endpoints merged into the main application path",
                    "Management",
                    "MEDIUM",
                    "quarkus.http.non-application-root-path=/ collapses health/metrics/OpenAPI endpoints into the"
                            + " main application namespace instead of keeping them on the separate /q root,"
                            + " widening the app's exposed surface and risking accidental path collisions"
                            + " (quarkusio/quarkus#14800). Quarkus-specific: there is no Spring equivalent of this"
                            + " particular footgun.",
                    1,
                    List.of("quarkus.http.non-application-root-path=/"),
                    "Leave non-application-root-path at its default (/q), or use the separate management"
                            + " interface (quarkus.management.enabled=true) instead."));
        }
        if (s.managementEnabled() && s.managementHostUnpinnedForProd()) {
            v.add(rule(
                    "QS-MGMT-003",
                    "Management interface has no explicit prod-scoped host binding",
                    "Management",
                    "INFO",
                    "The separate management interface is enabled but neither quarkus.management.host nor a"
                            + " %prod-scoped override is configured, so Quarkus's own built-in profile-dependent"
                            + " default silently applies: localhost in dev/test, but 0.0.0.0 (all interfaces) in"
                            + " a real production deployment.",
                    1,
                    List.of("quarkus.management.enabled=true, quarkus.management.host /"
                            + " %prod.quarkus.management.host absent"),
                    "Explicitly pin %prod.quarkus.management.host to 127.0.0.1, or to the intended bind address."));
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
        if (s.formAuth() && !s.formCookieHttpOnly()) {
            v.add(rule(
                    "QS-SESSION-001",
                    "Form-auth session cookie not HttpOnly",
                    "Session",
                    "HIGH",
                    "quarkus.http.auth.form.http-only-cookie defaults to false, so the form-auth session cookie"
                            + " is readable from JavaScript — a single XSS bug is enough to steal the session.",
                    1,
                    List.of("quarkus.http.auth.form.http-only-cookie=false (the Quarkus default)"),
                    "Set quarkus.http.auth.form.http-only-cookie=true."));
        }
        if (s.formAuth() && s.formCookieSameSiteNone()) {
            v.add(rule(
                    "QS-SESSION-002",
                    "Form-auth session cookie SameSite=None",
                    "Session",
                    "MEDIUM",
                    "quarkus.http.auth.form.cookie-same-site was weakened from the secure default (strict) to"
                            + " none, letting the session cookie be sent on cross-site requests (CSRF exposure).",
                    1,
                    List.of("quarkus.http.auth.form.cookie-same-site=none"),
                    "Remove the override (default strict), or use lax only if cross-site GET flows require it."));
        }
        if (s.formAuth() && s.formSessionTimeoutExcessive()) {
            v.add(rule(
                    "QS-SESSION-003",
                    "Excessive form-auth session timeout",
                    "Session",
                    "LOW",
                    "quarkus.http.auth.form.timeout is set to 8 hours or more, keeping an authenticated session"
                            + " alive long after a user has stepped away.",
                    1,
                    List.of("quarkus.http.auth.form.timeout >= 8h"),
                    "Lower the timeout (the Quarkus default is 30 minutes) and pair it with new-cookie-interval."));
        }
        if (s.grpcReflectionEnabledInProd()) {
            v.add(rule(
                    "QS-GRPC-001",
                    "gRPC server reflection enabled in the prod profile",
                    "gRPC",
                    "MEDIUM",
                    "quarkus.grpc.server.enable-reflection-service is enabled for the prod profile. Quarkus"
                            + " disables reflection in prod by default specifically so the full service/method/"
                            + " message schema isn't discoverable; an explicit override re-exposes it. No Spring"
                            + " equivalent — Spring has no first-party gRPC server support.",
                    1,
                    List.of("%prod quarkus.grpc.server.enable-reflection-service=true"),
                    "Remove the %prod override; keep reflection enabled only in %dev/%test."));
        }
        if (s.graphqlPresent() && s.graphqlIntrospectionEnabled()) {
            v.add(rule(
                    "QS-GRAPHQL-001",
                    "GraphQL schema introspection enabled",
                    "GraphQL",
                    "LOW",
                    "GraphQL schema introspection is enabled (the Quarkus default) in every profile, including"
                            + " production, letting any client enumerate the full schema (types, fields,"
                            + " mutations). Often intentional for public APIs, but worth a deliberate decision."
                            + " No Spring equivalent — Spring has no first-party GraphQL server support.",
                    1,
                    List.of("quarkus.smallrye-graphql.field-visibility does not include no-introspection (the"
                            + " Quarkus default)"),
                    "Add no-introspection to quarkus.smallrye-graphql.field-visibility in %prod unless the"
                            + " schema is meant to be publicly discoverable."));
        }
        if (!s.insecureMessagingChannels().isEmpty()) {
            v.add(rule(
                    "QS-MSG-001",
                    "Messaging credentials configured without an encrypted protocol",
                    "Messaging",
                    "HIGH",
                    "A Kafka/SmallRye Reactive Messaging channel configures SASL credentials (username/password"
                            + " or JAAS config) without a corresponding SASL_SSL/SSL security.protocol (its own,"
                            + " or a global fallback), sending broker credentials in clear text over the wire."
                            + " Each channel is evaluated independently so one channel's secure protocol can't"
                            + " mask another channel's insecure one. No Spring equivalent in the same idiomatic"
                            + " reactive-messaging form.",
                    s.insecureMessagingChannels().size(),
                    s.insecureMessagingChannels(),
                    "Set security.protocol=SASL_SSL (or SSL) for each affected channel (or globally via"
                            + " kafka.security.protocol)."));
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
            if (!"permit".equalsIgnoreCase(p.policy()) && isBroadPath(p.paths()) && appliesToAllMethods(p.methods())) {
                return true;
            }
        }
        return false;
    }

    /** A permission with no {@code methods} restriction applies to every HTTP method (Quarkus semantics). */
    private static boolean appliesToAllMethods(String methods) {
        return methods == null || methods.isBlank();
    }

    private static boolean wildcard(String csv) {
        return csv != null && csv.contains("*");
    }

    /**
     * Mirrors Quarkus's real {@code CORSFilter.isOriginConfiguredWithWildcard}: only a single configured origin
     * entry equal to exactly {@code *} or the bare regex wildcard {@code /} + {@code .*} + {@code /} counts as
     * an explicit wildcard. A multi-entry list such as {@code "*,https://foo.com"} is NOT treated as a wildcard
     * by real Quarkus.
     */
    private static boolean isExplicitWildcardOrigin(String corsOrigins) {
        if (corsOrigins == null || corsOrigins.isBlank()) {
            return false;
        }
        String[] parts = corsOrigins.split(",");
        if (parts.length != 1) {
            return false;
        }
        String only = parts[0].trim();
        return only.equals("*") || only.equals("/.*/");
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
