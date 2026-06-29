# Quarkus Security Advisor checks

The Security panel, on Quarkus, runs a fixed, on-demand ruleset against the host application's
**Quarkus security configuration** — not Spring Security. It reads the effective `quarkus.http.*`,
`quarkus.oidc.*`, `quarkus.smallrye-jwt.*` and related MicroProfile config, plus build-time counts of
the standard authorization annotations (`@RolesAllowed`, `@PermitAll`, `@DenyAll`, `@Authenticated`)
discovered in the application's own classes. It never intercepts live traffic, exposes credentials or
secrets, or modifies the configuration. Findings are heuristic review prompts; the right remediation
depends on the application's threat model and deployment topology.

This is the Quarkus replacement for the Spring ruleset in [SECURITY-CHECKS.md](SECURITY-CHECKS.md):
the panel and DTO are shared, but the rules are framework-specific (Elytron/OIDC vs Spring Security),
so there is no overlap. Spring-only concepts (filter chains, `FilterChainProxy`, method-security
proxies) are simply not evaluated here, and Quarkus-only concepts below are not evaluated on Spring.

## Availability and bounds

The advisor is always available on Quarkus (no extension required) and reads config live. Annotation
counts are captured at build time; when an app has zero secured endpoints and no auth mechanism, that
is itself a finding, not an unavailable panel. Missing/invalid values fail safe (counted as absent).

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
serves HTTP. Add an auth mechanism (`quarkus-oidc`, `quarkus-smallrye-jwt`, `quarkus.http.auth.basic`)
or restrict endpoints with `@RolesAllowed`/`quarkus.http.auth.permission.*`.

### QS-AUTH-002 - Basic authentication enabled without TLS (HIGH)
`quarkus.http.auth.basic=true` while `quarkus.http.insecure-requests=enabled` sends credentials in
clear text. Set `insecure-requests=redirect` (or `disabled`) and configure SSL.

## Authorization

### QS-AUTHZ-001 - No path or role authorization (HIGH)
No `quarkus.http.auth.permission.*` policies and no authorization annotations: every endpoint is open.
Add `@RolesAllowed`/`@Authenticated` or path permissions with `policy=authenticated`/roles.

### QS-AUTHZ-002 - Permission policy permits all paths (MEDIUM)
A permission policy uses `policy=permit` on a broad path (`/*`), bypassing authentication for that path.

### QS-AUTHZ-003 - Mostly unsecured endpoints (LOW)
A large share of discovered endpoints have no `@RolesAllowed`/`@Authenticated`/`@PermitAll`. Confirm
the open endpoints are intentionally public.

## Transport

### QS-TLS-001 - Insecure requests enabled (MEDIUM)
`quarkus.http.insecure-requests=enabled` (default). Prefer `redirect` once TLS is available.

### QS-TLS-002 - No TLS configured (LOW)
No `quarkus.http.ssl.*` / `quarkus.tls.*` keystore. Acceptable behind a terminating proxy; flagged for review.

## CORS

### QS-CORS-001 - CORS allows any origin (HIGH)
`quarkus.http.cors.origins=*` (or unset with CORS enabled) lets any site call the API. Pin explicit origins.

### QS-CORS-002 - CORS wildcard origin with credentials (CRITICAL)
`quarkus.http.cors.access-control-allow-credentials=true` with wildcard origin exposes credentialed cross-origin calls.

## Headers

### QS-HDR-001 - No security headers configured (LOW)
No `quarkus.http.header."Strict-Transport-Security"`/`Content-Security-Policy`. Add HSTS/CSP defaults.

## Dev exposure

### QS-DEV-001 - OIDC TLS verification disabled (HIGH)
`quarkus.oidc.tls.verification=none` disables provider certificate validation; never ship to production.

### QS-DEV-002 - OpenAPI/Swagger UI always included (MEDIUM)
`quarkus.swagger-ui.always-include=true` or `quarkus.smallrye-openapi.always-include=true` exposes API docs in prod.

## Config hygiene

### QS-CFG-001 - Possible secret in configuration (CRITICAL)
A config key looks like a password/secret/token set to a literal. Move to a vault/env var.
