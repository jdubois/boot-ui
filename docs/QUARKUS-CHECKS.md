# Quarkus Security Advisor checks

The Security panel, on Quarkus, runs a fixed, on-demand ruleset against the host application's
**Quarkus security configuration** — not Spring Security. It reads the effective `quarkus.http.*`,
`quarkus.oidc.*`, `quarkus.smallrye-jwt.*`, `quarkus.tls.*`, `quarkus.management.*` and related
MicroProfile config, plus build-time counts of the standard authorization annotations (`@RolesAllowed`,
`@PermitAll`, `@DenyAll`, `@Authenticated`) discovered in the application's own classes. It never
intercepts live traffic, exposes credentials or secrets, or modifies the configuration. Findings are
heuristic review prompts; the right remediation depends on the application's threat model and deployment
topology.

This is the Quarkus replacement for the Spring ruleset in [SECURITY-CHECKS.md](SECURITY-CHECKS.md):
the panel and DTO are shared, but the rules are framework-specific (Elytron/OIDC vs Spring Security),
so there is no overlap. Spring-only concepts (filter chains, `FilterChainProxy`, method-security
proxies) are simply not evaluated here, and Quarkus-only concepts below are not evaluated on Spring.

## Availability and bounds

The advisor is always available on Quarkus (no extension required) and reads config live. Annotation
counts are captured at build time; when an app has zero secured endpoints and no auth mechanism, that
is itself a finding, not an unavailable panel. Missing/invalid values fail safe (counted as absent).
Several rules are suppressed when the app is configured behind a TLS-terminating reverse proxy
(`quarkus.http.proxy.proxy-address-forwarding=true`), since transport hardening then lives at the proxy.

## Severity scale

- **CRITICAL** - exposes credentials/secrets or disables a critical control.
- **HIGH** - commonly leaves the app exposed; usually fix before production.
- **MEDIUM** - a hardening gap that warrants review.
- **LOW** - lower-impact hygiene.
- **INFO** - informational; fix depends on context.

The panel lists only checks with findings, ordered by severity, count, then rule id.

---

## Authentication

### QS-AUTH-001 - No authentication mechanism configured (HIGH)
No OIDC, JWT, basic, form, or mTLS auth is configured and no endpoints are role-protected, yet the app
exposes JAX-RS endpoints. Add an auth mechanism (`quarkus-oidc`, `quarkus-smallrye-jwt`,
`quarkus.http.auth.basic`) or restrict endpoints with `@RolesAllowed`/`quarkus.http.auth.permission.*`.
(Only raised when at least one endpoint is discovered.)

### QS-AUTH-002 - Basic authentication without TLS (HIGH)
`quarkus.http.auth.basic=true` while `quarkus.http.insecure-requests=enabled` sends credentials in
clear text. Set `insecure-requests=redirect` (or `disabled`) and configure SSL.

### QS-AUTH-003 - Form authentication without CSRF protection (HIGH)
`quarkus.http.auth.form.enabled=true` (cookie-based login) without the `quarkus-csrf-reactive` extension
leaves state-changing requests open to cross-site request forgery. Add `quarkus-csrf-reactive` and embed
the CSRF token in forms.

### QS-AUTH-004 - JWT verification without an expected issuer (MEDIUM)
SmallRye JWT verification is configured without `mp.jwt.verify.issuer`, so tokens from any issuer signed
with a trusted key are accepted. Set `mp.jwt.verify.issuer` to the expected token issuer.

### QS-AUTH-005 - Proactive authentication disabled (INFO)
`quarkus.http.auth.proactive=false` defers authentication until a secured resource is hit. This is a valid
pattern, but unannotated endpoints then run anonymously unless explicitly secured — pair it with
deny-by-default (`quarkus.security.jaxrs.deny-unannotated-endpoints=true`).

## Authorization

### QS-AUTHZ-001 - No path or role authorization (HIGH)
An auth mechanism exists but no `quarkus.http.auth.permission.*` policies and no authorization annotations
restrict any endpoint. Add `@RolesAllowed`/`@Authenticated` or path permissions with
`policy=authenticated`/roles.

### QS-AUTHZ-002 - Permission policy permits all paths (HIGH)
A permission policy applies `policy=permit` to a root path (`/` or `/*`), disabling authentication across
the whole application. Scope the path, or use `policy=authenticated`/roles instead of `permit`. (Paths are
parsed as a comma-separated list and matched exactly, so a scoped path like `/public/*` is not flagged.)

### QS-AUTHZ-003 - Mostly unsecured endpoints (LOW)
Fewer than half of discovered endpoints carry an authorization annotation. Confirm the open endpoints are
intentionally public; add `@Authenticated`/`@RolesAllowed` otherwise.

### QS-AUTHZ-004 - No deny-by-default for unannotated endpoints (MEDIUM)
Authentication is configured but endpoints without an authorization annotation are reachable anonymously:
`quarkus.security.jaxrs.deny-unannotated-endpoints` is off and no broad permission policy covers them. Set
`deny-unannotated-endpoints=true` and mark public endpoints `@PermitAll`. (Suppressed when a broad
non-permit permission policy on `/` or `/*` already protects everything.)

## Transport

### QS-TLS-001 - Insecure requests enabled (LOW)
`quarkus.http.insecure-requests=enabled` serves plain HTTP. Acceptable in local dev or behind a
TLS-terminating proxy; risky if exposed directly. Prefer `redirect` once TLS is available, or document the
terminating proxy. (Suppressed when the app is configured behind a proxy.)

### QS-TLS-002 - No TLS configured (INFO)
No HTTPS keystore/TLS registry (`quarkus.http.ssl.*` / `quarkus.tls.*`) is configured. Acceptable behind a
terminating proxy. (Suppressed when the app is configured behind a proxy.)

### QS-TLS-003 - Outbound TLS certificate validation disabled (HIGH)
`quarkus.tls.trust-all=true` disables certificate validation for all outbound TLS (REST clients, OIDC,
datasources), enabling man-in-the-middle attacks. Remove `trust-all`; import the peer's CA into a
trust-store instead.

## CORS

### QS-CORS-001 - CORS allows any origin (MEDIUM)
CORS is enabled without pinned origins (`quarkus.http.cors.origins` unset or `*`), allowing any site to
call the API. Set explicit origins.

### QS-CORS-002 - CORS wildcard origin with credentials (CRITICAL)
`quarkus.http.cors.access-control-allow-credentials=true` combined with a wildcard origin allows
credentialed cross-origin requests from any origin. Pin explicit origins; never combine wildcard with
credentials.

### QS-CORS-003 - Credentialed CORS with wildcard methods or headers (MEDIUM)
CORS allows credentials with a pinned origin but a wildcard `quarkus.http.cors.methods`/`headers` list,
widening the cross-origin surface. List the exact methods and headers the client needs instead of `*`.

## Headers

### QS-HDR-001 - No security headers configured (LOW)
No `Strict-Transport-Security` or `Content-Security-Policy` response headers are configured
(`quarkus.http.header.*` absent). Add HSTS and CSP headers.

### QS-HDR-002 - Weak Strict-Transport-Security policy (LOW)
The HSTS header has a `max-age` under one year or omits `includeSubDomains`, weakening HTTPS enforcement.
Use `max-age=31536000` (1 year) and add `includeSubDomains`.

### QS-HDR-003 - Missing clickjacking/MIME-sniffing headers (LOW)
No `X-Frame-Options`/CSP `frame-ancestors` (clickjacking) or `X-Content-Type-Options=nosniff` (MIME
sniffing) protection is configured. Add `X-Frame-Options=DENY` (or CSP `frame-ancestors 'none'`) and
`X-Content-Type-Options=nosniff`.

### QS-HDR-004 - Weak Content-Security-Policy (MEDIUM)
The CSP allows `'unsafe-inline'`/`'unsafe-eval'` or a wildcard `default-src`/`script-src`, undermining its
XSS protection. Remove unsafe-inline/unsafe-eval and wildcard sources; use nonces/hashes for scripts.

## Dev exposure

### QS-DEV-001 - OIDC TLS verification disabled (MEDIUM)
`quarkus.oidc.tls.verification=none` disables provider certificate validation. Sometimes used against a
local dev provider, but must never reach production.

### QS-DEV-002 - OpenAPI/Swagger UI always included (MEDIUM)
`quarkus.swagger-ui.always-include=true` or `quarkus.smallrye-openapi.always-include=true` exposes API
docs in all profiles, including production. Restrict to dev, or remove `always-include`.

## OIDC

### QS-OIDC-001 - OIDC without token audience validation (MEDIUM)
OIDC is configured without `quarkus.oidc.token.audience`, so a token minted for a different client/audience
by the same provider is accepted. Set `quarkus.oidc.token.audience` to this service's expected audience.

### QS-OIDC-002 - OIDC web-app session cookie not forced secure (MEDIUM)
An OIDC `web-app`/`hybrid` app stores the session in a cookie but `cookie-force-secure` is off and the app
does not terminate TLS, so the session cookie can travel over plain HTTP. Set
`quarkus.oidc.authentication.cookie-force-secure=true` (required behind a TLS proxy).

## Management

### QS-MGMT-001 - Management interface on a non-loopback host (LOW)
The separate management interface (`quarkus.management.enabled=true`, health/metrics) binds a non-loopback
host, exposing it beyond the local machine. Bind `quarkus.management.host` to `127.0.0.1`, or protect the
management endpoints.

## Config hygiene

### QS-CFG-001 - Possible secret in configuration (CRITICAL)
A config key looks like a password/secret/token set to a literal. Move to a vault/env var; never commit
literals.
