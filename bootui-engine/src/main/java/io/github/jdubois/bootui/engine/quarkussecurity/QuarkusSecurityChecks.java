package io.github.jdubois.bootui.engine.quarkussecurity;

import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.engine.security.CspPolicy;
import io.github.jdubois.bootui.spi.QuarkusSecurityEndpoint;
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
    private static final int RULE_COUNT = 42;
    private static final String GUIDE = "https://quarkus.io/guides/security-overview";
    private static final Pattern MAX_AGE = Pattern.compile("max-age\\s*=\\s*(\\d+|\"\\d+\")");
    private static final long HSTS_MIN_MAX_AGE = 31536000L;

    private QuarkusSecurityChecks() {}

    static int ruleCount() {
        return RULE_COUNT;
    }

    static List<SecurityRuleResultDto> evaluate(QuarkusSecuritySnapshot s) {
        List<SecurityRuleResultDto> v = new ArrayList<>();

        if (!s.anyAuthMechanism()
                && !hasProtectivePolicy(s.permissions())
                && s.protectiveAnnotationCount() == 0
                && !s.defaultRolesAllowed()
                && !s.denyUnannotatedEndpoints()
                && s.endpointCount() > 0) {
            v.add(rule(
                    "QS-AUTH-001",
                    "No authentication mechanism configured",
                    "Authentication",
                    "HIGH",
                    "No OIDC, JWT, basic, form, or mTLS authentication is configured and no protective"
                            + " permission policy, default role, or authorization annotation was found.",
                    1,
                    List.of("authentication mechanisms and protective authorization controls absent"),
                    "Add an authentication mechanism and protect endpoints with a permission policy or"
                            + " @RolesAllowed/@PermissionsAllowed."));
        }
        if (s.basicAuth() && "enabled".equals(s.insecureRequests())) {
            v.add(rule(
                    "QS-AUTH-002",
                    "Basic authentication without TLS",
                    "Authentication",
                    "HIGH",
                    "Basic auth is enabled while the listener accepts plain HTTP. Credentials submitted on that"
                            + " listener would not be transport encrypted; ingress policy is not observed.",
                    1,
                    List.of("quarkus.http.auth.basic=true, quarkus.http.insecure-requests=enabled"),
                    "Set quarkus.http.insecure-requests=redirect and configure TLS."));
        }
        if (s.formAuth() && !s.csrfPresent()) {
            v.add(rule(
                    "QS-AUTH-003",
                    "Review form authentication CSRF defenses",
                    "Authentication",
                    "LOW",
                    "Cookie-based form authentication is enabled, but the standard CSRF extension is absent or"
                            + " verification is disabled. Custom defenses and request coverage are not observed.",
                    1,
                    List.of("quarkus.http.auth.form.enabled=true, quarkus-rest-csrf absent"),
                    "Add the io.quarkus:quarkus-rest-csrf extension and embed the CSRF token in forms."));
        }
        if (s.formAuth() && "enabled".equals(s.insecureRequests())) {
            v.add(rule(
                    "QS-AUTH-012",
                    "Form authentication without TLS",
                    "Authentication",
                    "HIGH",
                    "Form authentication is enabled while insecure HTTP requests are accepted, exposing submitted"
                            + " credentials to passive network observers and active interception.",
                    1,
                    List.of("quarkus.http.auth.form.enabled=true, quarkus.http.insecure-requests=enabled"),
                    "Set quarkus.http.insecure-requests=redirect (or disabled) and configure TLS."));
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
        if (s.embeddedUsersEnabled()) {
            v.add(rule(
                    "QS-AUTH-007",
                    "Embedded identity store enabled in the current runtime",
                    "Authentication",
                    "MEDIUM",
                    "The embedded identity store is enabled in the observed runtime. This does not establish"
                            + " that a production profile or the distinct file identity store uses these users.",
                    1,
                    List.of("quarkus.security.users.embedded.enabled=true"),
                    "Use quarkus-elytron-security-jdbc/oidc for real deployments; keep embedded users to %dev/%test."));
        }
        if (s.embeddedUsersEnabled() && s.embeddedUsersPlainText()) {
            v.add(rule(
                    "QS-AUTH-013",
                    "Embedded users stored with plain-text passwords",
                    "Authentication",
                    "HIGH",
                    "The embedded identity store explicitly accepts plain-text passwords. Quarkus defaults this"
                            + " setting to false and otherwise expects digest hashes derived from the username,"
                            + " realm, and password.",
                    1,
                    List.of("quarkus.security.users.embedded.plain-text=true"),
                    "Use an identity provider or a supported adaptive password-hashing store for production;"
                            + " the embedded store's legacy digest default is not modern password-storage advice."));
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
                    "Review static JWT trust-anchor rotation",
                    "Authentication",
                    "INFO",
                    "The configured verification trust anchor is static. This is supported and may rotate out"
                            + " of band; review its operational replacement process.",
                    1,
                    List.of("mp.jwt.verify.publickey set"),
                    "Document and test trust-anchor rotation; remote JWKS is optional, not inherently safer."));
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
        if (!hasProtectivePolicy(s.permissions())
                && s.protectiveAnnotationCount() == 0
                && !s.defaultRolesAllowed()
                && !s.denyUnannotatedEndpoints()
                && s.endpointCount() > 0
                && s.anyAuthMechanism()) {
            v.add(rule(
                    "QS-AUTHZ-001",
                    "No path or role authorization",
                    "Authorization",
                    "HIGH",
                    "An auth mechanism exists but no permission policies or authorization annotations restrict any endpoint.",
                    1,
                    List.of("no protective permission policy, default role, or authorization annotation"),
                    "Add @RolesAllowed/@PermissionsAllowed/@Authenticated or a permission policy with"
                            + " policy=authenticated."));
        }
        List<String> permitAll = new ArrayList<>();
        for (QuarkusSecurityPermission p : s.permissions()) {
            if ("permit".equals(p.policy())
                    && isBroadPath(p.paths())
                    && appliesToAllMethods(p.methods())
                    && p.knownPolicy()
                    && "all".equals(p.appliesTo())
                    && !p.shared()) {
                permitAll.add(p.name() + " (" + (p.paths() == null ? "/*" : p.paths()) + ")");
            }
        }
        if (!permitAll.isEmpty()) {
            v.add(rule(
                    "QS-AUTHZ-002",
                    "Permission policy permits all paths",
                    "Authorization",
                    "INFO",
                    "A permission mapping declares a public default at /*. More specific or shared mappings,"
                            + " endpoint annotations and REST defaults can still restrict requests.",
                    permitAll.size(),
                    permitAll,
                    "Scope the path, or use policy=authenticated/roles instead of permit."));
        }
        if (s.anyAuthMechanism()
                && !s.denyUnannotatedEndpoints()
                && !s.defaultRolesAllowed()
                && uncoveredEndpoints(s) > 0) {
            v.add(
                    rule(
                            "QS-AUTHZ-004",
                            "No deny-by-default for unannotated endpoints",
                            "Authorization",
                            "MEDIUM",
                            "Declared REST endpoints lack a restrictive annotation or supported matching path policy."
                                    + " Review their public intent; this is not an executed authorization decision.",
                            uncoveredEndpoints(s),
                            List.of(uncoveredEndpoints(s) + " declared endpoint(s) without a supported restriction"),
                            "Set quarkus.security.jaxrs.deny-unannotated-endpoints=true and mark public endpoints @PermitAll."));
        }
        if ("enabled".equals(s.insecureRequests())) {
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
        if (!s.sslConfigured()) {
            v.add(rule(
                    "QS-TLS-002",
                    "No TLS configured for the main HTTP listener",
                    "Transport",
                    "INFO",
                    "The main Quarkus HTTP listener has no HTTPS keystore or selected TLS registry bucket."
                            + " This can be acceptable behind a terminating proxy, but proxy forwarding settings"
                            + " alone do not prove that TLS termination exists.",
                    1,
                    List.of("no quarkus.http.ssl.* / selected quarkus.tls.* server keystore"),
                    "Configure TLS or document the terminating proxy."));
        }
        if (s.tlsTrustAll()) {
            v.add(rule(
                    "QS-TLS-003",
                    "TLS certificate validation disabled",
                    "Transport",
                    "HIGH",
                    "trust-all=true is set on the default TLS registry bucket or a named bucket"
                            + " (quarkus.tls.<name>.trust-all), disabling peer certificate validation wherever"
                            + " that bucket is used and enabling man-in-the-middle attacks.",
                    1,
                    List.of("quarkus.tls.trust-all=true (default or a named bucket)"),
                    "Remove trust-all; import the peer's CA into a trust-store instead."));
        }
        if (s.insecureIdentityProviderUrl()) {
            v.add(rule(
                    "QS-TLS-004",
                    "Identity-provider and JWK endpoints should use HTTPS",
                    "Transport",
                    "HIGH",
                    "An OIDC auth-server URL or remote JWT key location uses plain HTTP, allowing discovery"
                            + " metadata or signing keys to be modified by an active network attacker.",
                    1,
                    List.of("quarkus.oidc.auth-server-url or mp.jwt.verify.publickey.location uses http://"),
                    "Use HTTPS identity-provider and JWK endpoints with certificate validation enabled."));
        }
        if (!s.tlsHostnameVerificationDisabled().isEmpty()) {
            v.add(
                    rule(
                            "QS-TLS-005",
                            "TLS hostname verification disabled",
                            "Transport",
                            "HIGH",
                            "A TLS registry bucket or OIDC tenant validates certificate chains without verifying that"
                                    + " the certificate belongs to the requested host, allowing a valid certificate for"
                                    + " another host to be accepted.",
                            s.tlsHostnameVerificationDisabled().size(),
                            s.tlsHostnameVerificationDisabled(),
                            "Enable hostname verification for each applicable consumer; registry defaults depend on the consumer."));
        }
        boolean explicitWildcardCors = s.corsEnabled() && isExplicitWildcardOrigin(s.corsOrigins());
        if (explicitWildcardCors && s.corsCredentials()) {
            v.add(rule(
                    "QS-CORS-002",
                    "CORS wildcard origin with credentials",
                    "CORS",
                    "HIGH",
                    "Credentialed cross-origin requests are allowed from any origin.",
                    1,
                    List.of("a universal configured origin is allowed with credentials"),
                    "Pin explicit origins; never combine wildcard with credentials."));
        } else if (explicitWildcardCors) {
            v.add(rule(
                    "QS-CORS-001",
                    "CORS allows any origin",
                    "CORS",
                    "LOW",
                    "The configured CORS policy permits public noncredentialed response sharing with arbitrary"
                            + " origins. This can be intentional for public APIs; CORS is not authorization.",
                    1,
                    List.of("a universal noncredentialed origin is configured"),
                    "Set quarkus.http.cors.origins to explicit origins."));
        }
        if (s.hstsHeader() && isWeakHsts(s.hstsHeaderValue())) {
            v.add(rule(
                    "QS-HDR-001",
                    "Weak Strict-Transport-Security policy",
                    "Headers",
                    "LOW",
                    "The configured HSTS policy is invalid, disables HSTS with max-age=0, or uses a rollout"
                            + " lifetime shorter than one year. One year is a review baseline, not a protocol minimum.",
                    1,
                    List.of("configured Strict-Transport-Security lifetime requires review"),
                    "Use max-age=31536000 (1 year); add includeSubDomains only when every subdomain is HTTPS-ready."));
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
                    List.of("configured enforcing CSP permits unsafe or unrestricted script execution"),
                    "Remove unsafe-inline/unsafe-eval and wildcard sources; use nonces/hashes for scripts."));
        }
        if (!s.hstsHeader() && s.sslConfigured()) {
            v.add(rule(
                    "QS-HDR-003",
                    "Missing Strict-Transport-Security header",
                    "Headers",
                    "LOW",
                    "No global HSTS declaration was observed for the configured HTTPS listener. Custom filters,"
                            + " proxies and delivered headers are not inspected.",
                    1,
                    List.of("quarkus.http.header.\"Strict-Transport-Security\".value absent"),
                    "Add quarkus.http.header.\"Strict-Transport-Security\".value=max-age=31536000;"
                            + " includeSubDomains only when every subdomain is HTTPS-ready."));
        }
        if (!s.cspHeader()) {
            v.add(rule(
                    "QS-HDR-004",
                    "Missing Content-Security-Policy header",
                    "Headers",
                    "LOW",
                    "No global enforcing CSP declaration was observed for declared document endpoints."
                            + " Custom filters and proxy-delivered policies are outside this configuration review.",
                    1,
                    List.of("quarkus.http.header.\"Content-Security-Policy\".value absent"),
                    "Add a Content-Security-Policy tailored to the app's script/style/asset origins."));
        }
        var cspAnalysis = CspPolicy.analyze(s.cspHeaderValue());
        boolean cspFramingKnown = !s.cspHeader() || cspAnalysis.complete();
        boolean framingRestricted = s.cspHeader() && cspAnalysis.frameAncestorsPresent()
                ? cspAnalysis.restrictiveFrameAncestors()
                : s.xFrameOptionsHeader();
        if (cspFramingKnown && !framingRestricted) {
            v.add(rule(
                    "QS-HDR-005",
                    "Missing clickjacking protection",
                    "Headers",
                    "LOW",
                    "The supported configured framing declarations do not restrict framing of document endpoints."
                            + " An enforcing CSP frame-ancestors directive overrides X-Frame-Options even when"
                            + " permissive. Delivered headers are not inspected.",
                    1,
                    List.of("configured framing declarations do not establish a supported restriction"),
                    "Use restrictive enforcing frame-ancestors, for example 'none'. X-Frame-Options=DENY is an"
                            + " alternative only when no enforcing ancestor directive overrides it."));
        }
        if (!s.xContentTypeOptionsHeader()) {
            v.add(rule(
                    "QS-HDR-006",
                    "Missing X-Content-Type-Options header",
                    "Headers",
                    "LOW",
                    "No valid global X-Content-Type-Options=nosniff declaration was observed."
                            + " Custom filters and proxy-delivered headers are outside this configuration review.",
                    1,
                    List.of("quarkus.http.header.\"X-Content-Type-Options\".value absent"),
                    "Add quarkus.http.header.\"X-Content-Type-Options\".value=nosniff."));
        }
        if (s.oidcTlsVerificationNone()) {
            v.add(rule(
                    "QS-DEV-001",
                    "OIDC TLS verification disabled",
                    "Dev exposure",
                    "HIGH",
                    "quarkus.oidc.tls.verification=none disables provider certificate and hostname validation."
                            + " This legacy setting is deprecated in favor of the TLS registry and must never"
                            + " reach production.",
                    1,
                    List.of("quarkus.oidc.tls.verification=none"),
                    "Remove the override outside local dev; never ship with verification disabled."));
        }
        if (s.swaggerUiAlwaysInclude() || s.graphqlUiAlwaysInclude()) {
            List<String> alwaysIncluded = new ArrayList<>();
            if (s.swaggerUiAlwaysInclude()) {
                alwaysIncluded.add("swagger-ui.always-include=true");
            }
            if (s.graphqlUiAlwaysInclude()) {
                alwaysIncluded.add("smallrye-graphql.ui.always-include=true");
            }
            v.add(rule(
                    "QS-DEV-002",
                    "Swagger/GraphQL UI always included",
                    "Dev exposure",
                    "MEDIUM",
                    "Supported local configuration declares UI inclusion for the prod profile. Inclusion alone"
                            + " does not establish a running route, anonymous access or production exposure.",
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
                    "Supported local configuration declares Health UI inclusion for prod. Route availability"
                            + " and access policy are separate; a production deployment was not observed.",
                    1,
                    List.of("quarkus.smallrye-health.ui.always-include=true"),
                    "Remove the override so the Health UI is only available outside production, or protect it"
                            + " via the management interface / a permission policy."));
        }
        if (s.oidcConfigured() && s.oidcServiceTokenConsumer() && !s.oidcAudienceConfigured()) {
            v.add(rule(
                    "QS-OIDC-001",
                    "OIDC without token audience validation",
                    "OIDC",
                    "HIGH",
                    "OIDC is configured without quarkus.oidc.token.audience, so a token minted for a different"
                            + " service/audience by the same provider can be accepted by this resource server.",
                    1,
                    List.of("quarkus.oidc.auth-server-url set, quarkus.oidc.token.audience absent"),
                    "Set quarkus.oidc.token.audience to this service's expected audience."));
        }
        if (s.oidcConfigured() && s.oidcIssuerAny()) {
            v.add(rule(
                    "QS-OIDC-004",
                    "OIDC token issuer validation is bypassed",
                    "OIDC",
                    "HIGH",
                    "quarkus.oidc.token.issuer=any disables issuer matching, so a correctly signed token from an"
                            + " unintended issuer can be accepted.",
                    1,
                    List.of("quarkus.oidc.token.issuer=any"),
                    "Remove token.issuer=any and configure the exact trusted issuer; use explicit tenant"
                            + " resolution when multiple issuers are intentional."));
        }
        boolean oidcWebApp = "web-app".equals(s.oidcApplicationType()) || "hybrid".equals(s.oidcApplicationType());
        if (s.oidcConfigured() && oidcWebApp && !s.oidcCookieForceSecure() && "enabled".equals(s.insecureRequests())) {
            v.add(rule(
                    "QS-OIDC-002",
                    "OIDC web-app session cookie not forced secure",
                    "OIDC",
                    "MEDIUM",
                    "An OIDC web-app accepts HTTP and does not force cookie Secure. Secure is otherwise set per"
                            + " request; configuring HTTPS alongside accepted HTTP does not secure HTTP cookies.",
                    1,
                    List.of("quarkus.oidc.application-type=" + s.oidcApplicationType()
                            + ", cookie-force-secure=false, HTTP accepted"),
                    "Disable or redirect HTTP and review cookie-force-secure, including trusted proxy handling."));
        }
        if (s.oidcConfigured() && oidcWebApp && !s.oidcHasClientSecret() && !s.oidcPkceRequired()) {
            v.add(rule(
                    "QS-OIDC-003",
                    "Public OIDC client without PKCE",
                    "OIDC",
                    "MEDIUM",
                    "A supported public-client declaration disables PKCE for an OIDC web-app/hybrid flow."
                            + " Credential-provider, JWT client authentication and provider presets are considered;"
                            + " absent literal credentials alone do not prove a public client.",
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
        if (s.managementHostUnpinnedForProd()) {
            v.add(rule(
                    "QS-MGMT-003",
                    "Management interface has no explicit prod-scoped host binding",
                    "Management",
                    "INFO",
                    "Supported local prod declarations enable management without a host override. The prod"
                            + " default is all interfaces, unlike the dev/test loopback default. This is a"
                            + " deployment review, not proof of a running production listener or remote access.",
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
                    "MEDIUM",
                    "Supported local application configuration contains literal credential-shaped values."
                            + " This is a source-hygiene review, not proof those values are exposed or deployed.",
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
            v.add(
                    rule(
                            "QS-SESSION-002",
                            "Form-auth session cookie SameSite=None",
                            "Session",
                            "LOW",
                            "SameSite=None permits cross-site cookie use, which can be a deliberate compatibility choice."
                                    + " Review Secure and independent CSRF defenses for state-changing operations.",
                            1,
                            List.of("quarkus.http.auth.form.cookie-same-site=none"),
                            "Use Secure with SameSite=None and verify independent CSRF defenses; choose Strict/Lax if compatible."));
        }
        if (s.formAuth() && s.formSessionTimeoutExcessive()) {
            v.add(rule(
                    "QS-SESSION-003",
                    "Long form-auth idle timeout",
                    "Session",
                    "LOW",
                    "The configured form-auth idle timeout is at least eight hours, a heuristic review threshold."
                            + " This is not an absolute session lifespan; active sessions can renew.",
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
                    "Supported local prod declarations leave GraphQL schema introspection enabled."
                            + " This is often intentional; client access and production deployment are not observed.",
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
                    "An active Kafka channel has configured SASL credentials with an unencrypted protocol after"
                            + " channel/connector/global inheritance. SASL_PLAINTEXT lacks transport encryption;"
                            + " PLAINTEXT does not send SASL credentials and instead indicates an inconsistent setup.",
                    s.insecureMessagingChannels().size(),
                    s.insecureMessagingChannels(),
                    "Set security.protocol=SASL_SSL (or SSL) for each affected channel (or globally via"
                            + " kafka.security.protocol)."));
        }
        return v.stream()
                .filter(result -> !s.evidence().unknownRules().contains(result.id()))
                .toList();
    }

    private static boolean isBroadPath(String paths) {
        if (paths == null || paths.isBlank()) {
            return false;
        }
        for (String p : paths.split(",")) {
            String t = p.trim();
            if (t.equals("/*")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBroadProtectivePolicy(List<QuarkusSecurityPermission> perms) {
        for (QuarkusSecurityPermission p : perms) {
            if (p.knownPolicy()
                    && !"permit".equals(p.policy())
                    && isBroadPath(p.paths())
                    && ("all".equals(p.appliesTo()) || "jaxrs".equals(p.appliesTo()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasProtectivePolicy(List<QuarkusSecurityPermission> perms) {
        return perms.stream().anyMatch(p -> p.knownPolicy() && !"permit".equals(p.policy()));
    }

    /** A permission with no {@code methods} restriction applies to every HTTP method (Quarkus semantics). */
    private static boolean appliesToAllMethods(String methods) {
        return methods == null || methods.isBlank();
    }

    /** Literal wildcard is singleton-only; a universally matching regex also works inside a list. */
    private static boolean isExplicitWildcardOrigin(String corsOrigins) {
        if (corsOrigins == null || corsOrigins.isBlank()) {
            return false;
        }
        String[] parts = corsOrigins.split(",");
        for (String part : parts) {
            if ("/.*/".equals(part.trim()) || "/^.*$/".equals(part.trim())) {
                return true;
            }
        }
        return parts.length == 1 && "*".equals(parts[0].trim());
    }

    private static boolean isWeakHsts(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.length() > 8192 || value.contains(",")) {
            return false;
        }
        String v = value.toLowerCase(java.util.Locale.ROOT);
        long maxAge = 0;
        int ages = 0;
        for (String directive : v.split(";")) {
            String trimmed = directive.trim();
            if (trimmed.startsWith("max-age")) {
                Matcher matcher = MAX_AGE.matcher(trimmed);
                if (++ages > 1 || !matcher.matches()) {
                    return true;
                }
                try {
                    maxAge = Long.parseLong(matcher.group(1).replace("\"", ""));
                } catch (NumberFormatException ignored) {
                    return true;
                }
            }
        }
        return ages != 1 || maxAge < HSTS_MIN_MAX_AGE;
    }

    private static boolean isWeakCsp(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        var policy = CspPolicy.analyze(value);
        return policy.complete()
                && (policy.unsafeInlineScript() || policy.unsafeEvalScript() || policy.unrestrictedScript());
    }

    private static int uncoveredEndpoints(QuarkusSecuritySnapshot snapshot) {
        if (!snapshot.evidence().endpointMetadata()) {
            return hasBroadProtectivePolicy(snapshot.permissions())
                    ? 0
                    : Math.max(0, snapshot.endpointCount() - snapshot.securedEndpointCount());
        }
        return (int) snapshot.evidence().endpoints().stream()
                .filter(endpoint -> endpoint.access() == QuarkusSecurityEndpoint.Access.UNANNOTATED)
                .filter(endpoint -> QuarkusPermissionEvidence.decision(snapshot.permissions(), endpoint)
                        == QuarkusPermissionEvidence.Decision.PUBLIC)
                .count();
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
                id,
                name,
                category,
                severity,
                description,
                VIOLATION,
                count,
                samples.stream().limit(20).toList(),
                recommendation,
                GUIDE);
    }
}
