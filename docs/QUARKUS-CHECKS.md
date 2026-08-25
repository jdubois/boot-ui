# Quarkus security checks

The Security panel, on Quarkus, runs a fixed, on-demand **49-rule** ruleset against the host application's
**Quarkus security configuration** — not Spring Security. It reads the effective `quarkus.http.*`,
`quarkus.oidc.*`, `quarkus.smallrye-jwt.*`, `quarkus.tls.*`, `quarkus.management.*`,
`quarkus.security.users.embedded.*`, `quarkus.rest-csrf.*` (the CSRF extension), `quarkus.grpc.server.*`,
`quarkus.smallrye-graphql.*`, `quarkus-elytron-security-jdbc` principal-query settings, and Kafka/SmallRye
Reactive Messaging channel security settings, plus build-time counts of the standard authorization
annotations (`@RolesAllowed`, `@PermitAll`, `@DenyAll`, `@Authenticated`, `@PermissionsAllowed`, and
`@AuthorizationPolicy`) discovered in the application's own classes. It never intercepts live traffic,
exposes credentials or secrets, or modifies the
configuration. Findings are heuristic review prompts; the right remediation depends on the application's
threat model and deployment topology.

OIDC checks aggregate the active default tenant and active named tenants; a tenant with
`quarkus.oidc[.<tenant>].tenant-enabled=false` is excluded.

This is the Quarkus replacement for the Spring ruleset in [SECURITY-CHECKS.md](SECURITY-CHECKS.md):
the panel and DTO are shared, but the framework-specific registries are mutually exclusive
(Elytron/OIDC vs Spring Security). Equivalent authentication, authorization, transport, and CORS risks
intentionally have framework-native rules on both stacks; Spring-only concepts (filter chains,
`FilterChainProxy`, method-security proxies) are not evaluated here, and Quarkus-only concepts below
are not evaluated on Spring.

## Availability and bounds

The advisor is always available on Quarkus (no extension required) and reads config live. Annotation
counts are captured at build time; when an app has zero secured endpoints and no auth mechanism, that
is itself a finding, not an unavailable panel. Missing/invalid values fail safe (counted as absent).
A forwarding configuration (`quarkus.http.proxy.proxy-address-forwarding=true`) is not treated as proof
that a proxy actually terminates TLS, so listener-level transport findings remain visible and explain that
a verified terminating proxy can make them acceptable.
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

The advisor score applies the shared severity penalty to every concrete finding, not just once per violated rule.
Dismissed rules remove all of their findings from the score.

---

## Authentication

### QS-AUTH-001 - No authentication mechanism configured

- **Severity**: HIGH
- **Detects**: No OIDC, JWT, basic, form, or mTLS auth is configured and no protective permission policy or
  authorization annotation restricts the app, yet it exposes JAX-RS endpoints. `@PermitAll` and `policy=permit` do not
  suppress this finding. (Only raised when at least one endpoint is discovered.)
- **Recommendation**: Add an auth mechanism (`quarkus-oidc`, `quarkus-smallrye-jwt`, `quarkus.http.auth.basic`) or
  restrict endpoints with `@RolesAllowed`/`@PermissionsAllowed`/ `quarkus.http.auth.permission.*`.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTH-002 - Basic authentication without TLS

- **Severity**: HIGH
- **Detects**: `quarkus.http.auth.basic=true` while `quarkus.http.insecure-requests=enabled` sends credentials in clear
  text.
- **Recommendation**: Set `insecure-requests=redirect` (or `disabled`) and configure SSL.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTH-003 - Form authentication without CSRF protection

- **Severity**: HIGH
- **Detects**: `quarkus.http.auth.form.enabled=true` (cookie-based login) without the `io.quarkus:quarkus-rest-csrf`
  extension (feature name `rest-csrf`) leaves state-changing requests open to cross-site request forgery. (Also fires
  when the extension is present but explicitly disabled via `quarkus.rest-csrf.enabled=false`.)
- **Recommendation**: Add `quarkus-rest-csrf` and embed the CSRF token in forms.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTH-004 - JWT verification without an expected issuer

- **Severity**: MEDIUM
- **Detects**: SmallRye JWT verification is configured without `mp.jwt.verify.issuer`, so tokens from any issuer signed
  with a trusted key are accepted.
- **Recommendation**: Set `mp.jwt.verify.issuer` to the expected token issuer.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTH-005 - Proactive authentication disabled

- **Severity**: INFO
- **Detects**: `quarkus.http.auth.proactive=false` defers authentication until a secured resource is hit.
- **Recommendation**: This is a valid pattern, but unannotated endpoints then run anonymously unless explicitly secured
  — pair it with deny-by-default (`quarkus.security.jaxrs.deny-unannotated-endpoints=true`).
- **Learn more**: <https://quarkus.io/guides/security-overview>

> **Retired: QS-AUTH-006** (JWT signature algorithm not pinned for a remote JWKS) was removed. MicroProfile
> JWT 2.1 defines `mp.jwt.verify.publickey.algorithm` with an `RS256` default and explicitly describes the
> property as the algorithm whitelist. Leaving it unset therefore does not accept an unbounded algorithm set;
> the old rule reported a missing explicit preference even though the effective verifier remained pinned.
> The rule id is retired and will not be reused.

### QS-AUTH-007 - Embedded properties-file users enabled

- **Severity**: MEDIUM
- **Detects**: `quarkus.security.users.embedded.enabled=true` authenticates against a static in-memory/properties-file
  user list — a convenience meant for demos/tests, not a real identity store.
- **Recommendation**: Use `quarkus-elytron-security-jdbc`/`quarkus-oidc` for real deployments; keep embedded users to
  `%dev`/`%test`.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTH-008 - JWT verification without audience validation

- **Severity**: MEDIUM
- **Detects**: SmallRye JWT verification is configured without `mp.jwt.verify.audiences`, so a token minted for a
  different client/service by the same trusted issuer is still accepted.
- **Recommendation**: Set `mp.jwt.verify.audiences` to this service's expected audience(s).
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTH-009 - JWT public key configured inline

- **Severity**: LOW
- **Detects**: `mp.jwt.verify.publickey` holds a static inline key.
- **Why it matters**: Unlike a JWKS location, an inline key cannot be rotated without a redeploy.
- **Recommendation**: Prefer `mp.jwt.verify.publickey.location` pointing at a JWKS endpoint that supports rotation.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTH-010 - JDBC identity store using clear-text password mapper

- **Severity**: HIGH
- **Detects**: A `quarkus-elytron-security-jdbc` `principal-query` uses the `clear-password-mapper`, meaning passwords
  are compared/stored in plain text rather than hashed.
- **Recommendation**: Switch to `bcrypt-password-mapper` (or another hashing mapper) and re-hash stored passwords.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTH-012 - Form authentication without TLS

- **Severity**: HIGH
- **Detects**: `quarkus.http.auth.form.enabled=true` while `quarkus.http.insecure-requests=enabled` accepts passwords
  over plain HTTP, exposing them to passive network observers and active intermediaries. Forwarded-header trust does not
  suppress the finding because it does not prove that a proxy terminates TLS.
- **Recommendation**: Set `quarkus.http.insecure-requests=redirect` (or `disabled`) and configure TLS.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTH-013 - Embedded users stored with plain-text passwords

- **Severity**: HIGH
- **Detects**: `quarkus.security.users.embedded.enabled=true` and `quarkus.security.users.embedded.plain-text=true` make
  the embedded identity store accept literal passwords. Quarkus defaults `plain-text` to `false` and otherwise expects a
  digest derived from `username:realm:password`.
- **Recommendation**: Remove the override and store digest hashes, or use a production identity provider.
- **Learn more**: <https://quarkus.io/guides/security-overview>

> **Retired: QS-AUTH-011** (JDBC identity store bcrypt work-factor too low) was removed. The rule checked
> `principal-query.*.bcrypt-password-mapper.work-factor`, a property that does not exist:
> `BcryptPasswordKeyMapperConfig` (quarkus-elytron-security-jdbc) has no work-factor/cost-factor field at all
> (only `enabled`, `password-index`, `hash-encoding`, `salt-index`, `salt-encoding`, `iteration-count-index` — a
> column index, not a cost factor). Bcrypt's cost factor is embedded in the stored MCF-format hash string
> itself, not externally configurable via this extension, so the rule could never fire and its remediation
> ("raise the work factor") was nonsensical. The rule id is retired and will not be reused.

## Authorization

### QS-AUTHZ-001 - No path or role authorization

- **Severity**: HIGH
- **Detects**: An auth mechanism exists but no protective `quarkus.http.auth.permission.*` policy, default roles, or
  authorization annotations restrict any endpoint. Disabled policies and broad `policy=permit` blocks do not count as
  protection.
- **Recommendation**: Add `@RolesAllowed`/`@PermissionsAllowed`/`@Authenticated` or path permissions with
  `policy=authenticated`/roles.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTHZ-002 - Permission policy permits all paths

- **Severity**: HIGH
- **Detects**: A permission policy applies `policy=permit` to a root path (`/` or `/*`) **with no method restriction**,
  disabling authentication across the whole application. (Paths are parsed as a comma-separated list and matched
  exactly, so a scoped path like `/public/*` is not flagged. A permission carrying
  `quarkus.http.auth.permission.<name>.methods`, e.g. a CORS-preflight `OPTIONS`-only permit, is scoped to that method
  and is not flagged either — only a permit with no `methods` restriction applies to every HTTP method.)
- **Recommendation**: Scope the path, or use `policy=authenticated`/roles instead of `permit`.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTHZ-003 - Most endpoints lack authorization annotations

- **Severity**: LOW
- **Detects**: Fewer than half of discovered endpoints carry an authorization annotation. Path policies can still
  protect them, so this is an annotation-coverage review rather than proof that the endpoints are public.
- **Recommendation**: Confirm the open endpoints are intentional; add
  `@Authenticated`/`@RolesAllowed`/`@PermissionsAllowed` otherwise.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTHZ-004 - No deny-by-default for unannotated endpoints

- **Severity**: MEDIUM
- **Detects**: Authentication is configured but endpoints without an authorization annotation are reachable anonymously:
  `quarkus.security.jaxrs.deny-unannotated-endpoints` is off, no `quarkus.security.jaxrs.default-roles-allowed` value
  exists, and no broad permission policy covers them. (Suppressed only when a broad non-permit permission policy on `/`
  or `/*` with **no method restriction** already protects everything; a policy scoped to a single HTTP method, e.g.
  `methods=GET`, does not actually cover every unannotated endpoint and so does not suppress this finding. Policies with
  `enabled=false` or no `paths` are ignored, matching Quarkus's runtime mapping.)
- **Recommendation**: Set `deny-unannotated-endpoints=true` (or default roles) and mark public endpoints `@PermitAll`.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## Transport

### QS-TLS-001 - Insecure requests enabled

- **Severity**: LOW
- **Detects**: `quarkus.http.insecure-requests=enabled` serves plain HTTP. The rule uses Quarkus's effective default:
  absent means `disabled` when `quarkus.http.ssl.client-auth=required`, and `enabled` otherwise. Forwarding
  configuration does not suppress it.
- **Why it matters**: Acceptable in local dev or behind a TLS-terminating proxy; risky if exposed directly.
- **Recommendation**: Prefer `redirect` once TLS is available, or document the terminating proxy.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-TLS-002 - No TLS configured for the main HTTP listener

- **Severity**: INFO
- **Detects**: The main HTTP listener has no HTTPS keystore. The check recognizes legacy `quarkus.http.ssl.*`, every
  supported default TLS-registry keystore shape (`quarkus.tls.key-store.pem|p12|jks.*`), and a named bucket only when
  `quarkus.http.tls-configuration-name` selects that bucket. A client-only named bucket no longer hides this finding.
- **Recommendation**: Acceptable behind a verified terminating proxy.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-TLS-003 - TLS certificate validation disabled

- **Severity**: HIGH
- **Detects**: `trust-all=true` is set on the default TLS registry bucket (`quarkus.tls.trust-all`) **or any named
  bucket** (`quarkus.tls.<name>.trust-all`), disabling peer certificate validation wherever that bucket is used and
  enabling man-in-the-middle attacks.
- **Recommendation**: Remove `trust-all`; import the peer's CA into a trust-store instead.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-TLS-004 - Identity-provider and JWK endpoints should use HTTPS

- **Severity**: HIGH
- **Detects**: `quarkus.oidc.auth-server-url` (for the default or any named tenant) or
  `mp.jwt.verify.publickey.location` uses plain HTTP, allowing OIDC discovery metadata or JWT signing keys to be
  modified by an active network attacker.
- **Recommendation**: Use HTTPS endpoints with certificate validation enabled.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-TLS-005 - TLS hostname verification disabled

- **Severity**: HIGH
- **Detects**: `quarkus.tls.hostname-verification-algorithm=NONE`, the equivalent setting on a named TLS registry
  bucket, or legacy OIDC `tls.verification=certificate-validation` validates certificate chains without checking that
  the certificate belongs to the requested host. A named OIDC TLS configuration supersedes the legacy OIDC setting and
  avoids a duplicate/obsolete finding.
- **Recommendation**: Use the default HTTPS hostname verification.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## CORS

### QS-CORS-001 - CORS allows any origin

- **Severity**: MEDIUM
- **Detects**: CORS is enabled with an **explicit** wildcard origin — `quarkus.http.cors.origins` is exactly `*` or the
  bare regex `/.*/` — allowing any site to call the API. (Unset/absent origins are **not** treated as a wildcard — see
  QS-CORS-005 below; Quarkus's own `CORSFilter.isOriginConfiguredWithWildcard` only matches when the configured origin
  list has exactly one entry equal to `*`/`/.*/`, so e.g. `*,https://app.example` is a literal two-entry list, not a
  wildcard, and is not flagged here either.)
- **Recommendation**: Set explicit origins.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-CORS-002 - CORS wildcard origin with credentials

- **Severity**: CRITICAL
- **Detects**: `quarkus.http.cors.access-control-allow-credentials=true` combined with an **explicit** wildcard origin
  (as defined above) allows credentialed cross-origin requests from any origin.
- **Recommendation**: Pin explicit origins; never combine wildcard with credentials.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-CORS-003 - Credentialed CORS with wildcard methods or headers

- **Severity**: MEDIUM
- **Detects**: CORS allows credentials with a pinned (non-wildcard) origin but a wildcard
  `quarkus.http.cors.methods`/`headers` list, widening the cross-origin surface. Quarkus treats an **unset or empty**
  methods/headers list, or a single `*`, as wildcard; a multi-entry list merely containing `*` is not modeled as
  wildcard. Credentials are considered "allowed" here either when `access-control-allow-credentials=true` is set
  explicitly, **or** when it is left unset and `origins` is configured as one or more precisely-pinned literal values
  (no `*`, no `/regex/`) — mirroring Quarkus's real `CORSFilter` default,
  `corsConfig.accessControlAllowCredentials().orElse(originMatches)`: when the property isn't set, Quarkus itself allows
  credentials whenever the request's `Origin` matches a configured (non-wildcard) origin. An earlier version of this
  rule modeled the unset case as "credentials disabled," missing this common pinned-single-origin configuration.
- **Recommendation**: List the exact methods and headers the client needs instead.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-CORS-005 - CORS enabled with no origins configured

- **Severity**: INFO
- **Detects**: `quarkus.http.cors` is enabled but `quarkus.http.cors.origins` is unset. Quarkus's `CORSFilter` then only
  permits same-origin requests — the most restrictive possible outcome, not "any origin" — so the filter is effectively
  inert until origins are configured.
- **Recommendation**: If cross-origin access is intended, configure `quarkus.http.cors.origins` explicitly; otherwise
  this has no practical effect.
- **Learn more**: <https://quarkus.io/guides/security-overview>

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

### QS-HDR-001 - Weak Strict-Transport-Security policy

- **Severity**: LOW
- **Detects**: The HSTS header has a `max-age` under one year, weakening HTTPS enforcement.
- **Recommendation**: Use `max-age=31536000` (1 year). `includeSubDomains` is intentionally not required: RFC 6797 makes
  it optional, and enabling it without securing every subdomain can break legitimate deployments.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-HDR-002 - Weak Content-Security-Policy

- **Severity**: MEDIUM
- **Detects**: The CSP allows `'unsafe-inline'`/`'unsafe-eval'` or a wildcard `default-src`/`script-src`, undermining
  its XSS protection.
- **Recommendation**: Remove unsafe-inline/unsafe-eval and wildcard sources; use nonces/hashes for scripts.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-HDR-003 - Missing Strict-Transport-Security header

- **Severity**: LOW
- **Detects**: No `Strict-Transport-Security` response header is configured, so browsers fall back to trusting whatever
  scheme a link/redirect uses instead of enforcing HTTPS.
- **Recommendation**: Add `quarkus.http.header."Strict-Transport-Security".value=max-age=31536000`; add
  `includeSubDomains` only after every subdomain is HTTPS-ready.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-HDR-004 - Missing Content-Security-Policy header

- **Severity**: LOW
- **Detects**: No `Content-Security-Policy` response header is configured, losing a defense-in-depth control against XSS
  and data-injection attacks.
- **Recommendation**: Add a CSP tailored to the app's script/style/asset origins.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-HDR-005 - Missing clickjacking protection

- **Severity**: LOW
- **Detects**: Neither `X-Frame-Options` nor a CSP `frame-ancestors` directive is configured, so the app can be embedded
  in a hidden/opaque iframe on an attacker's page (clickjacking).
- **Recommendation**: Add `quarkus.http.header."X-Frame-Options".value=DENY` (or a CSP `frame-ancestors 'none'`).
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-HDR-006 - Missing X-Content-Type-Options header

- **Severity**: LOW
- **Detects**: No `X-Content-Type-Options=nosniff` response header is configured, allowing browsers to MIME-sniff
  responses and potentially execute content served with the wrong `Content-Type`.
- **Recommendation**: Add `quarkus.http.header."X-Content-Type-Options".value=nosniff`.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-HDR-007 - Missing Referrer-Policy header

- **Severity**: INFO
- **Detects**: No `Referrer-Policy` response header is configured, so browsers may forward the full request URL
  (including any sensitive query parameters) to third-party sites linked from the app.
- **Recommendation**: Add `quarkus.http.header."Referrer-Policy".value=strict-origin-when-cross-origin` (or stricter).
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-HDR-008 - Missing Permissions-Policy header

- **Severity**: INFO
- **Detects**: No `Permissions-Policy` response header is configured, leaving browser features (camera, microphone,
  geolocation, …) at their default availability instead of explicitly disabled where unused.
- **Recommendation**: Add `quarkus.http.header."Permissions-Policy".value` listing only the features the app uses.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## Dev exposure

### QS-DEV-001 - OIDC TLS verification disabled

- **Severity**: HIGH
- **Detects**: `quarkus.oidc.tls.verification=none` disables provider certificate validation. This legacy setting is
  deprecated in Quarkus 3.33 in favor of a TLS registry configuration.
- **Recommendation**: Sometimes used against a local dev provider, but must never reach production.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-DEV-002 - Swagger/GraphQL UI always included

- **Severity**: MEDIUM
- **Detects**: `quarkus.swagger-ui.always-include=true` or `quarkus.smallrye-graphql.ui.always-include=true` exposes the
  Swagger or GraphQL UI in all profiles, including production. There is no `quarkus.smallrye-openapi.always-include`
  property in Quarkus 3.33, so that former dead predicate is no longer evaluated.
- **Recommendation**: Restrict it to dev, or remove `always-include`.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-DEV-003 - SmallRye Health UI always included

- **Severity**: LOW
- **Detects**: `quarkus.smallrye-health.ui.always-include=true` exposes the Health UI in every profile, including
  production, revealing the app's health-check topology to anyone who can reach it. Same pattern as QS-DEV-002, for the
  Health UI specifically.
- **Recommendation**: Remove the override so the Health UI is only available outside production, or protect it via the
  management interface / a permission policy.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## OIDC

### QS-OIDC-001 - OIDC without token audience validation

- **Severity**: HIGH
- **Detects**: OIDC is configured for a default or named `service`/`hybrid` token-consuming tenant without that tenant's
  `quarkus.oidc[.<tenant>].token.audience`, so an access token minted for a different service by the same trusted
  provider can be accepted. Pure `web-app` authorization-code clients are excluded because their primary authentication
  artifact is an OIDC ID token whose audience is validated against the client id by the protocol implementation;
  applying this resource-server rule to them would be a false positive.
- **Why it matters**: The severity is HIGH for service/M2M flows because RFC 8725 requires each JWT application to
  validate that the token was issued for it.
- **Recommendation**: Set the tenant's token audience to this resource server's expected audience.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-OIDC-002 - OIDC web-app session cookie not forced secure

- **Severity**: MEDIUM
- **Detects**: An OIDC `web-app`/`hybrid` app stores the session in a cookie but `cookie-force-secure` is off and the
  app does not terminate TLS, so the session cookie can travel over plain HTTP.
- **Recommendation**: Set `quarkus.oidc.authentication.cookie-force-secure=true` (required behind a TLS proxy).
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-OIDC-003 - Public OIDC client without PKCE

- **Severity**: MEDIUM
- **Detects**: An OIDC `web-app`/`hybrid` client has no client secret configured (`quarkus.oidc.credentials.secret` /
  `quarkus.oidc.credentials.client-secret.value` both absent — a public client, e.g. an SPA or mobile app) and
  `quarkus.oidc.authentication.pkce-required` is not enabled (the Quarkus default is `false`), leaving the
  authorization-code flow vulnerable to interception.
- **Recommendation**: Set `quarkus.oidc.authentication.pkce-required=true` for public clients.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-OIDC-004 - OIDC token issuer validation is bypassed

- **Severity**: HIGH
- **Detects**: `quarkus.oidc[.<tenant>].token.issuer=any` disables issuer matching for the default or a named tenant. A
  token with a valid signature but an unintended issuer can then be accepted, especially when keys are shared or
  federated.
- **Recommendation**: Remove `token.issuer=any` and pin the exact trusted issuer; use explicit tenant resolution when
  multiple issuers are intentional.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## Management

### QS-MGMT-001 - Management interface on a non-loopback host

- **Severity**: LOW
- **Detects**: The separate management interface (`quarkus.management.enabled=true`, health/metrics) has a **literal**
  `quarkus.management.host` (or `%prod.quarkus.management.host`) key pinned to a non-loopback value, exposing it beyond
  the local machine. (Checked by scanning for the literal, unresolved config key — not the profile-resolved value — via
  the same raw-key technique QS-GRPC-001 uses for `%prod`-scoped reflection: Quarkus's own built-in default for
  `quarkus.management.host` is profile-dependent, `localhost` in dev/test but `0.0.0.0` in prod, and this advisor only
  ever runs under a dev/test `LaunchMode`, so a resolved-value read would always observe the safe dev/test default and
  could never catch a real prod-facing `0.0.0.0`. See QS-MGMT-003 for the complementary case where neither key is pinned
  at all.)
- **Recommendation**: Bind the host to `127.0.0.1`, or protect the management endpoints.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-MGMT-002 - Non-application endpoints merged into the main application path

- **Severity**: MEDIUM
- **Detects**: **Quarkus-specific — no Spring equivalent.** A `quarkus.http.non-application-root-path` that resolves to
  `quarkus.http.root-path` (including the documented `${quarkus.http.root-path}` form) collapses health/metrics/OpenAPI
  endpoints into the main application namespace instead of keeping them under the default relative `q` root, widening
  the app's exposed surface and risking accidental path collisions.
- **Recommendation**: Leave `non-application-root-path` at its default (`q`), or use the separate management interface
  (`quarkus.management.enabled=true`) instead.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-MGMT-003 - Management interface has no explicit prod-scoped host binding

- **Severity**: INFO
- **Detects**: The separate management interface is enabled but **neither** a literal `quarkus.management.host` nor a
  `%prod.quarkus.management.host` key is present at all, so Quarkus's own built-in profile-dependent default silently
  applies: `localhost` in dev/test, but `0.0.0.0` (all interfaces) in a real production deployment.
- **Why it matters**: This complements QS-MGMT-001 — MGMT-001 fires when a non-loopback host is pinned explicitly;
  MGMT-003 fires when nothing is pinned and Quarkus's prod-mode default would take over unnoticed.
- **Recommendation**: Explicitly pin `%prod.quarkus.management.host` to `127.0.0.1`, or to the intended bind address.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## Config hygiene

### QS-CFG-001 - Possible secret in configuration

- **Severity**: CRITICAL
- **Detects**: A config key's terminal segment identifies a password, secret, API key, private key, token, or
  access/refresh token set to a literal value (not an externalized `${...}` reference). Scans application and `%prod`
  configuration, including the `quarkus.*` namespace — e.g. `quarkus.datasource.password`,
  `quarkus.oidc.credentials.secret`, `quarkus.mail.password` are all in scope, alongside application-owned keys.
  Environment-variable and system-property sources, `${...}` expressions, `%dev`/`%test` values, BootUI internals, and
  non-secret keys that merely contain words such as `quarkus.oidc.token.issuer` are excluded. Only key names enter the
  report; values never do.
- **Recommendation**: Move committed literals to a vault/env var.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## Session

### QS-SESSION-001 - Form-auth session cookie not HttpOnly

- **Severity**: HIGH
- **Detects**: `quarkus.http.auth.form.http-only-cookie` defaults to `false` in Quarkus — unlike most frameworks — so
  the form-auth session cookie is readable from JavaScript; a single XSS bug is enough to steal the session.
- **Recommendation**: Set `quarkus.http.auth.form.http-only-cookie=true`.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-SESSION-002 - Form-auth session cookie SameSite=None

- **Severity**: MEDIUM
- **Detects**: `quarkus.http.auth.form.cookie-same-site` was weakened from the secure default (`strict`) to `none`,
  letting the session cookie be sent on cross-site requests (CSRF exposure).
- **Recommendation**: Remove the override (default `strict`), or use `lax` only if cross-site GET flows require it.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-SESSION-003 - Excessive form-auth session timeout

- **Severity**: LOW
- **Detects**: `quarkus.http.auth.form.timeout` is set to 8 hours or more, keeping an authenticated session alive long
  after a user has stepped away.
- **Recommendation**: Lower the timeout (the Quarkus default is 30 minutes) and pair it with `new-cookie-interval`.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## gRPC

### QS-GRPC-001 - gRPC server reflection enabled in the prod profile

- **Severity**: MEDIUM
- **Detects**: **Quarkus-specific — no Spring equivalent** (Spring has no first-party gRPC server support).
  `quarkus.grpc.server.enable-reflection-service` is enabled for the prod profile.
- **Why it matters**: Quarkus disables reflection in prod by default specifically so the full service/method/message
  schema isn't discoverable; an explicit override re-exposes it.
- **Recommendation**: Remove the `%prod` override; keep reflection enabled only in `%dev`/`%test`.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## GraphQL

### QS-GRAPHQL-001 - GraphQL schema introspection enabled

- **Severity**: LOW
- **Detects**: **Quarkus-specific — no Spring equivalent** (Spring has no first-party GraphQL server support).
  `quarkus.smallrye-graphql.field-visibility` does not include the `no-introspection` token (the Quarkus default), so
  schema introspection is enabled in every profile, including production, letting any client enumerate the full schema
  (types, fields, mutations). (There is no `quarkus.smallrye-graphql.introspection-enabled` property in real Quarkus —
  an earlier version of this rule checked that non-existent key and could never fire; the real, current mechanism is the
  `no-introspection` value in the comma-separated `field-visibility` list, confirmed against
  `SmallRyeGraphQLRuntimeConfig` and Quarkus's own `FieldVisibilityNoIntrospectionTest`.)
- **Why it matters**: Often intentional for public APIs, but worth a deliberate decision.
- **Recommendation**: Add `no-introspection` to `quarkus.smallrye-graphql.field-visibility` in `%prod` unless the schema
  is meant to be publicly discoverable.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## Messaging

### QS-MSG-001 - Messaging credentials configured without an encrypted protocol

- **Severity**: HIGH
- **Detects**: **Quarkus-specific** (no Spring equivalent in the same idiomatic reactive-messaging form). A
  Kafka/SmallRye Reactive Messaging channel configures SASL credentials (username/password or JAAS config) without a
  corresponding `SASL_SSL`/`SSL` `security.protocol` (its own, or a global fallback), sending broker credentials in
  clear text over the wire. Each channel prefix (e.g. `mp.messaging.incoming.orders`, or the bare `kafka` global-default
  bucket) is evaluated **independently**, so one channel's secure protocol cannot mask another channel's insecure one;
  the finding lists the specific violating channel name(s), not a single aggregate boolean.
- **Recommendation**: Set `security.protocol=SASL_SSL` (or `SSL`) for each affected channel (or globally via
  `kafka.security.protocol`).
- **Learn more**: <https://quarkus.io/guides/security-overview>
