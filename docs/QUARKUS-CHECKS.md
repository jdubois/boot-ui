# Quarkus Security Advisor checks

The Security panel, on Quarkus, runs a fixed, on-demand ruleset against the host application's
**Quarkus security configuration** — not Spring Security. It reads the effective `quarkus.http.*`,
`quarkus.oidc.*`, `quarkus.smallrye-jwt.*`, `quarkus.tls.*`, `quarkus.management.*`,
`quarkus.security.users.embedded.*`, `quarkus.rest-csrf.*` (the CSRF extension), `quarkus.grpc.server.*`,
`quarkus.smallrye-graphql.*`, `quarkus-elytron-security-jdbc` principal-query settings, and Kafka/SmallRye
Reactive Messaging channel security settings, plus build-time counts of the standard authorization
annotations (`@RolesAllowed`, `@PermitAll`, `@DenyAll`, `@Authenticated`) discovered in the application's
own classes. It never intercepts live traffic, exposes credentials or secrets, or modifies the
configuration. Findings are heuristic review prompts; the right remediation depends on the application's
threat model and deployment topology.

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
A handful of rules — marked **Quarkus-specific** below — have no Spring Security equivalent at all: they
cover Quarkus-only capabilities (gRPC, GraphQL, SmallRye Reactive Messaging) or Quarkus-only footguns (the
non-application root path). The rest are Quarkus ports of the same risk the Spring Security advisor
already checks, adapted to Quarkus's own config keys and extensions.

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
`quarkus.http.auth.form.enabled=true` (cookie-based login) without the `io.quarkus:quarkus-rest-csrf`
extension (feature name `rest-csrf`) leaves state-changing requests open to cross-site request forgery. Add
`quarkus-rest-csrf` and embed the CSRF token in forms. (Also fires when the extension is present but
explicitly disabled via `quarkus.rest-csrf.enabled=false`.)

### QS-AUTH-004 - JWT verification without an expected issuer (MEDIUM)
SmallRye JWT verification is configured without `mp.jwt.verify.issuer`, so tokens from any issuer signed
with a trusted key are accepted. Set `mp.jwt.verify.issuer` to the expected token issuer.

### QS-AUTH-005 - Proactive authentication disabled (INFO)
`quarkus.http.auth.proactive=false` defers authentication until a secured resource is hit. This is a valid
pattern, but unannotated endpoints then run anonymously unless explicitly secured — pair it with
deny-by-default (`quarkus.security.jaxrs.deny-unannotated-endpoints=true`).

### QS-AUTH-006 - JWT signature algorithm not pinned for a remote JWKS (MEDIUM)
`mp.jwt.verify.publickey.location` points at a remote (`http`/`https`) JWKS endpoint but
`mp.jwt.verify.publickey.algorithm` is left unset, relying on SmallRye JWT's implicit RS256-only default instead
of explicitly pinning the expected algorithm(s) for a key source that can rotate/change independently of this
application. Set `mp.jwt.verify.publickey.algorithm` explicitly to the algorithm(s) this application expects.

### QS-AUTH-007 - Embedded properties-file users enabled (MEDIUM)
`quarkus.security.users.embedded.enabled=true` authenticates against a static in-memory/properties-file
user list — a convenience meant for demos/tests, not a real identity store. Use
`quarkus-elytron-security-jdbc`/`quarkus-oidc` for real deployments; keep embedded users to `%dev`/`%test`.

### QS-AUTH-008 - JWT verification without audience validation (MEDIUM)
SmallRye JWT verification is configured without `mp.jwt.verify.audiences`, so a token minted for a different
client/service by the same trusted issuer is still accepted. Set `mp.jwt.verify.audiences` to this
service's expected audience(s).

### QS-AUTH-009 - JWT public key configured inline (LOW)
`mp.jwt.verify.publickey` holds a static inline key. Unlike a JWKS location, an inline key cannot be
rotated without a redeploy. Prefer `mp.jwt.verify.publickey.location` pointing at a JWKS endpoint that
supports rotation.

### QS-AUTH-010 - JDBC identity store using clear-text password mapper (HIGH)
A `quarkus-elytron-security-jdbc` `principal-query` uses the `clear-password-mapper`, meaning passwords are
compared/stored in plain text rather than hashed. Switch to `bcrypt-password-mapper` (or another hashing
mapper) and re-hash stored passwords.

> **Retired: QS-AUTH-011** (JDBC identity store bcrypt work-factor too low) was removed. The rule checked
> `principal-query.*.bcrypt-password-mapper.work-factor`, a property that does not exist:
> `BcryptPasswordKeyMapperConfig` (quarkus-elytron-security-jdbc) has no work-factor/cost-factor field at all
> (only `enabled`, `password-index`, `hash-encoding`, `salt-index`, `salt-encoding`, `iteration-count-index` — a
> column index, not a cost factor). Bcrypt's cost factor is embedded in the stored MCF-format hash string
> itself, not externally configurable via this extension, so the rule could never fire and its remediation
> ("raise the work factor") was nonsensical. The rule id is retired and will not be reused.

## Authorization

### QS-AUTHZ-001 - No path or role authorization (HIGH)
An auth mechanism exists but no `quarkus.http.auth.permission.*` policies and no authorization annotations
restrict any endpoint. Add `@RolesAllowed`/`@Authenticated` or path permissions with
`policy=authenticated`/roles.

### QS-AUTHZ-002 - Permission policy permits all paths (HIGH)
A permission policy applies `policy=permit` to a root path (`/` or `/*`) **with no method restriction**,
disabling authentication across the whole application. Scope the path, or use `policy=authenticated`/roles
instead of `permit`. (Paths are parsed as a comma-separated list and matched exactly, so a scoped path like
`/public/*` is not flagged. A permission carrying `quarkus.http.auth.permission.<name>.methods`, e.g. a
CORS-preflight `OPTIONS`-only permit, is scoped to that method and is not flagged either — only a permit with
no `methods` restriction applies to every HTTP method.)

### QS-AUTHZ-003 - Mostly unsecured endpoints (LOW)
Fewer than half of discovered endpoints carry an authorization annotation. Confirm the open endpoints are
intentionally public; add `@Authenticated`/`@RolesAllowed` otherwise.

### QS-AUTHZ-004 - No deny-by-default for unannotated endpoints (MEDIUM)
Authentication is configured but endpoints without an authorization annotation are reachable anonymously:
`quarkus.security.jaxrs.deny-unannotated-endpoints` is off and no broad permission policy covers them. Set
`deny-unannotated-endpoints=true` and mark public endpoints `@PermitAll`. (Suppressed only when a broad
non-permit permission policy on `/` or `/*` with **no method restriction** already protects everything; a
policy scoped to a single HTTP method, e.g. `methods=GET`, does not actually cover every unannotated endpoint
and so does not suppress this finding.)

## Transport

### QS-TLS-001 - Insecure requests enabled (LOW)
`quarkus.http.insecure-requests=enabled` serves plain HTTP. Acceptable in local dev or behind a
TLS-terminating proxy; risky if exposed directly. Prefer `redirect` once TLS is available, or document the
terminating proxy. (Suppressed when the app is configured behind a proxy.)

### QS-TLS-002 - No TLS configured (INFO)
No HTTPS keystore/TLS registry is configured — checked against `quarkus.http.ssl.*`, the default
`quarkus.tls.*` bucket, **and any named TLS registry bucket** (`quarkus.tls.<name>.key-store.*`, commonly used
to pin TLS independently for a REST client, gRPC, or OIDC connection). Acceptable behind a terminating proxy.
(Suppressed when the app is configured behind a proxy.)

### QS-TLS-003 - Outbound TLS certificate validation disabled (HIGH)
`trust-all=true` is set on the default TLS registry bucket (`quarkus.tls.trust-all`) **or any named bucket**
(`quarkus.tls.<name>.trust-all`), disabling certificate validation for outbound TLS (REST clients, OIDC,
datasources, gRPC) on that bucket, enabling man-in-the-middle attacks. Remove `trust-all`; import the peer's
CA into a trust-store instead.

## CORS

### QS-CORS-001 - CORS allows any origin (MEDIUM)
CORS is enabled with an **explicit** wildcard origin — `quarkus.http.cors.origins` is exactly `*` or the
bare regex `/.*/ ` — allowing any site to call the API. Set explicit origins. (Unset/absent origins are
**not** treated as a wildcard — see QS-CORS-005 below; Quarkus's own `CORSFilter.isOriginConfiguredWithWildcard`
only matches when the configured origin list has exactly one entry equal to `*`/`/.*/ `, so e.g.
`*,https://app.example` is a literal two-entry list, not a wildcard, and is not flagged here either.)

### QS-CORS-002 - CORS wildcard origin with credentials (CRITICAL)
`quarkus.http.cors.access-control-allow-credentials=true` combined with an **explicit** wildcard origin (as
defined above) allows credentialed cross-origin requests from any origin. Pin explicit origins; never
combine wildcard with credentials.

### QS-CORS-003 - Credentialed CORS with wildcard methods or headers (MEDIUM)
CORS allows credentials with a pinned (non-wildcard) origin but a wildcard `quarkus.http.cors.methods`/`headers`
list, widening the cross-origin surface. List the exact methods and headers the client needs instead of `*`.
Credentials are considered "allowed" here either when `access-control-allow-credentials=true` is set
explicitly, **or** when it is left unset and `origins` is configured as one or more precisely-pinned literal
values (no `*`, no `/regex/`) — mirroring Quarkus's real `CORSFilter` default,
`corsConfig.accessControlAllowCredentials().orElse(originMatches)`: when the property isn't set, Quarkus itself
allows credentials whenever the request's `Origin` matches a configured (non-wildcard) origin. An earlier
version of this rule modeled the unset case as "credentials disabled," missing this common
pinned-single-origin configuration.

### QS-CORS-005 - CORS enabled with no origins configured (INFO)
`quarkus.http.cors` is enabled but `quarkus.http.cors.origins` is unset. Quarkus's `CORSFilter` then only
permits same-origin requests — the most restrictive possible outcome, not "any origin" — so the filter is
effectively inert until origins are configured. If cross-origin access is intended, configure
`quarkus.http.cors.origins` explicitly; otherwise this has no practical effect.

> **Retired: QS-CORS-004** (CORS regex origin pattern not anchored) was removed. The rule claimed Quarkus's
> `CORSFilter` matched an unanchored `/regex/` origin pattern anywhere in the string (`.find()` semantics)
> rather than against the whole string, citing
> [quarkusio/quarkus#34718](https://github.com/quarkusio/quarkus/issues/34718). Direct inspection of the
> current `CORSFilter.isOriginAllowedByRegex` shows `pattern.matcher(origin).matches()` — Java's `.matches()`
> requires a full match of the entire input string, not `.find()` — and issue #34718 was fixed in Quarkus
> 3.3.0, long before this project's current Quarkus line. The bypass the rule warned about no longer applies
> to any Quarkus version this project supports, so the rule was removed rather than re-worded; preferring
> literal origins over regex (and anchoring any regex you do use) remains sound general advice, just not
> something this advisor asserts a specific exploitable mechanism for. The rule id is retired and will not be
> reused.

## Headers

### QS-HDR-001 - Weak Strict-Transport-Security policy (LOW)
The HSTS header has a `max-age` under one year or omits `includeSubDomains`, weakening HTTPS enforcement.
Use `max-age=31536000` (1 year) and add `includeSubDomains`.

### QS-HDR-002 - Weak Content-Security-Policy (MEDIUM)
The CSP allows `'unsafe-inline'`/`'unsafe-eval'` or a wildcard `default-src`/`script-src`, undermining its
XSS protection. Remove unsafe-inline/unsafe-eval and wildcard sources; use nonces/hashes for scripts.

### QS-HDR-003 - Missing Strict-Transport-Security header (LOW)
No `Strict-Transport-Security` response header is configured, so browsers fall back to trusting whatever
scheme a link/redirect uses instead of enforcing HTTPS. Add
`quarkus.http.header."Strict-Transport-Security".value=max-age=31536000; includeSubDomains`.

### QS-HDR-004 - Missing Content-Security-Policy header (LOW)
No `Content-Security-Policy` response header is configured, losing a defense-in-depth control against XSS
and data-injection attacks. Add a CSP tailored to the app's script/style/asset origins.

### QS-HDR-005 - Missing clickjacking protection (LOW)
Neither `X-Frame-Options` nor a CSP `frame-ancestors` directive is configured, so the app can be embedded in
a hidden/opaque iframe on an attacker's page (clickjacking). Add
`quarkus.http.header."X-Frame-Options".value=DENY` (or a CSP `frame-ancestors 'none'`).

### QS-HDR-006 - Missing X-Content-Type-Options header (LOW)
No `X-Content-Type-Options=nosniff` response header is configured, allowing browsers to MIME-sniff
responses and potentially execute content served with the wrong `Content-Type`. Add
`quarkus.http.header."X-Content-Type-Options".value=nosniff`.

### QS-HDR-007 - Missing Referrer-Policy header (INFO)
No `Referrer-Policy` response header is configured, so browsers may forward the full request URL (including
any sensitive query parameters) to third-party sites linked from the app. Add
`quarkus.http.header."Referrer-Policy".value=strict-origin-when-cross-origin` (or stricter).

### QS-HDR-008 - Missing Permissions-Policy header (INFO)
No `Permissions-Policy` response header is configured, leaving browser features (camera, microphone,
geolocation, …) at their default availability instead of explicitly disabled where unused. Add
`quarkus.http.header."Permissions-Policy".value` listing only the features the app uses.

## Dev exposure

### QS-DEV-001 - OIDC TLS verification disabled (MEDIUM)
`quarkus.oidc.tls.verification=none` disables provider certificate validation. Sometimes used against a
local dev provider, but must never reach production.

### QS-DEV-002 - OpenAPI/Swagger/GraphQL UI always included (MEDIUM)
`quarkus.swagger-ui.always-include=true`, `quarkus.smallrye-openapi.always-include=true`, or
`quarkus.smallrye-graphql.ui.always-include=true` exposes API docs and/or the GraphQL UI in all profiles,
including production. Restrict to dev, or remove `always-include`.

### QS-DEV-003 - SmallRye Health UI always included (LOW)
`quarkus.smallrye-health.ui.always-include=true` exposes the Health UI in every profile, including
production, revealing the app's health-check topology to anyone who can reach it. Same pattern as
QS-DEV-002, for the Health UI specifically. Remove the override so the Health UI is only available outside
production, or protect it via the management interface / a permission policy.

## OIDC

### QS-OIDC-001 - OIDC without token audience validation (MEDIUM)
OIDC is configured without `quarkus.oidc.token.audience`, so a token minted for a different client/audience
by the same provider is accepted. Set `quarkus.oidc.token.audience` to this service's expected audience.

### QS-OIDC-002 - OIDC web-app session cookie not forced secure (MEDIUM)
An OIDC `web-app`/`hybrid` app stores the session in a cookie but `cookie-force-secure` is off and the app
does not terminate TLS, so the session cookie can travel over plain HTTP. Set
`quarkus.oidc.authentication.cookie-force-secure=true` (required behind a TLS proxy).

### QS-OIDC-003 - Public OIDC client without PKCE (MEDIUM)
An OIDC `web-app`/`hybrid` client has no client secret configured (`quarkus.oidc.credentials.secret` /
`quarkus.oidc.credentials.client-secret.value` both absent — a public client, e.g. an SPA or mobile app) and
`quarkus.oidc.authentication.pkce-required` is not enabled (the Quarkus default is `false`), leaving the
authorization-code flow vulnerable to interception. Set `quarkus.oidc.authentication.pkce-required=true` for
public clients.

## Management

### QS-MGMT-001 - Management interface on a non-loopback host (LOW)
The separate management interface (`quarkus.management.enabled=true`, health/metrics) has a **literal**
`quarkus.management.host` (or `%prod.quarkus.management.host`) key pinned to a non-loopback value, exposing
it beyond the local machine. Bind the host to `127.0.0.1`, or protect the management endpoints. (Checked by
scanning for the literal, unresolved config key — not the profile-resolved value — via the same raw-key
technique QS-GRPC-001 uses for `%prod`-scoped reflection: Quarkus's own built-in default for
`quarkus.management.host` is profile-dependent, `localhost` in dev/test but `0.0.0.0` in prod, and this
advisor only ever runs under a dev/test `LaunchMode`, so a resolved-value read would always observe the safe
dev/test default and could never catch a real prod-facing `0.0.0.0`. See QS-MGMT-003 for the complementary
case where neither key is pinned at all.)

### QS-MGMT-002 - Non-application endpoints merged into the main application path (MEDIUM)
**Quarkus-specific — no Spring equivalent.** `quarkus.http.non-application-root-path=/` collapses
health/metrics/OpenAPI endpoints into the main application namespace instead of keeping them on the
separate `/q` root, widening the app's exposed surface and risking accidental path collisions
([quarkusio/quarkus#14800](https://github.com/quarkusio/quarkus/issues/14800)). Leave
`non-application-root-path` at its default (`/q`), or use the separate management interface
(`quarkus.management.enabled=true`) instead.

### QS-MGMT-003 - Management interface has no explicit prod-scoped host binding (INFO)
The separate management interface is enabled but **neither** a literal `quarkus.management.host` nor a
`%prod.quarkus.management.host` key is present at all, so Quarkus's own built-in profile-dependent default
silently applies: `localhost` in dev/test, but `0.0.0.0` (all interfaces) in a real production deployment.
This complements QS-MGMT-001 — MGMT-001 fires when a non-loopback host is pinned explicitly; MGMT-003 fires
when nothing is pinned and Quarkus's prod-mode default would take over unnoticed. Explicitly pin
`%prod.quarkus.management.host` to `127.0.0.1`, or to the intended bind address.

## Config hygiene

### QS-CFG-001 - Possible secret in configuration (CRITICAL)
A config key looks like a password/secret/token set to a literal value (not an externalized `${...}`
reference). Scans **all** configuration keys, including the `quarkus.*` namespace — e.g.
`quarkus.datasource.password`, `quarkus.oidc.credentials.secret`, `quarkus.mail.password` are all in scope,
alongside application-owned keys. Only BootUI's own internal `bootui.*` keys are excluded. Move secrets to a
vault/env var; never commit literals. (An earlier version of this rule excluded the entire `quarkus.*`
namespace from scanning, meaning the most common real-world Quarkus secret-bearing properties could never be
flagged regardless of their literal value; that blanket exclusion has been removed.)

## Session

### QS-SESSION-001 - Form-auth session cookie not HttpOnly (HIGH)
`quarkus.http.auth.form.http-only-cookie` defaults to `false` in Quarkus — unlike most frameworks — so the
form-auth session cookie is readable from JavaScript; a single XSS bug is enough to steal the session. Set
`quarkus.http.auth.form.http-only-cookie=true`.

### QS-SESSION-002 - Form-auth session cookie SameSite=None (MEDIUM)
`quarkus.http.auth.form.cookie-same-site` was weakened from the secure default (`strict`) to `none`, letting
the session cookie be sent on cross-site requests (CSRF exposure). Remove the override (default `strict`),
or use `lax` only if cross-site GET flows require it.

### QS-SESSION-003 - Excessive form-auth session timeout (LOW)
`quarkus.http.auth.form.timeout` is set to 8 hours or more, keeping an authenticated session alive long
after a user has stepped away. Lower the timeout (the Quarkus default is 30 minutes) and pair it with
`new-cookie-interval`.

## gRPC

### QS-GRPC-001 - gRPC server reflection enabled in the prod profile (MEDIUM)
**Quarkus-specific — no Spring equivalent** (Spring has no first-party gRPC server support).
`quarkus.grpc.server.enable-reflection-service` is enabled for the prod profile. Quarkus disables reflection
in prod by default specifically so the full service/method/message schema isn't discoverable; an explicit
override re-exposes it. Remove the `%prod` override; keep reflection enabled only in `%dev`/`%test`.

## GraphQL

### QS-GRAPHQL-001 - GraphQL schema introspection enabled (LOW)
**Quarkus-specific — no Spring equivalent** (Spring has no first-party GraphQL server support).
`quarkus.smallrye-graphql.field-visibility` does not include the `no-introspection` token (the Quarkus
default), so schema introspection is enabled in every profile, including production, letting any client
enumerate the full schema (types, fields, mutations). Often intentional for public APIs, but worth a
deliberate decision. Add `no-introspection` to `quarkus.smallrye-graphql.field-visibility` in `%prod` unless
the schema is meant to be publicly discoverable. (There is no
`quarkus.smallrye-graphql.introspection-enabled` property in real Quarkus — an earlier version of this rule
checked that non-existent key and could never fire; the real, current mechanism is the
`no-introspection` value in the comma-separated `field-visibility` list, confirmed against
`SmallRyeGraphQLRuntimeConfig` and Quarkus's own `FieldVisibilityNoIntrospectionTest`.)

## Messaging

### QS-MSG-001 - Messaging credentials configured without an encrypted protocol (HIGH)
**Quarkus-specific** (no Spring equivalent in the same idiomatic reactive-messaging form). A Kafka/SmallRye
Reactive Messaging channel configures SASL credentials (username/password or JAAS config) without a
corresponding `SASL_SSL`/`SSL` `security.protocol` (its own, or a global fallback), sending broker
credentials in clear text over the wire. Each channel prefix (e.g. `mp.messaging.incoming.orders`, or the
bare `kafka` global-default bucket) is evaluated **independently**, so one channel's secure protocol cannot
mask another channel's insecure one; the finding lists the specific violating channel name(s), not a single
aggregate boolean. Set `security.protocol=SASL_SSL` (or `SSL`) for each affected channel (or globally via
`kafka.security.protocol`).
