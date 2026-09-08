# Quarkus security checks

The Security panel, on Quarkus, runs a fixed, on-demand **42-rule** ruleset against the host application's
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

`assessmentEvidence` records known evaluated conditions or confirmed findings, not the 42-rule registry size.
Wholly unknown evidence stays unscored; usable partial scores follow the [shared policy](features/advisors.md#score-eligibility).

OIDC checks aggregate the active default tenant and active named tenants; a tenant with
`quarkus.oidc[.<tenant>].tenant-enabled=false` is excluded.

This is the Quarkus replacement for the Spring ruleset in [SECURITY-CHECKS.md](SECURITY-CHECKS.md):
the panel and DTO are shared, but the framework-specific registries are mutually exclusive
(Elytron/OIDC vs Spring Security). Equivalent authentication, authorization, transport, and CORS risks
intentionally have framework-native rules on both stacks; Spring-only concepts (filter chains,
`FilterChainProxy`, method-security proxies) are not evaluated here, and Quarkus-only concepts below
are not evaluated on Spring.

## Availability and bounds

The advisor is available on Quarkus without a security extension. Supported configuration facts are collected on
explicit scans; endpoint declarations are captured at build time. Missing, inactive, unsupported and invalid
observations are distinct: invalid or unreadable evidence is not silently treated as an absent control.
Known findings survive unrelated incomplete collection. Unsupported analysis produces a partial scan;
genuine failures use sanitized analysis errors.

This catalog was audited against **Quarkus 3.33.3.1**. Configuration metadata cannot establish arbitrary
custom policies, dynamic tenants, delivered headers or network reachability. Collection never invokes tenant
resolvers, credential providers, custom policies or HTTP-security initializers to resolve these gaps.
Secret-hygiene classification is limited to recognized local application configuration sources; arbitrary
external/dynamic sources are not read to label their values as committed secrets.
Bounded metadata discovery also recognizes native SmallRye environment and system-property sources, without
classifying their values as local-file secrets. For example, OIDC configured through a system property is not
mistaken for an absent authentication mechanism.
Native runtime-registry and test-URL bridges are not mistaken for arbitrary configuration inventories.
Non-default `@ApplicationPath`, `quarkus.rest.path`, and `quarkus.http.root-path` prefixes make endpoint coverage
incomplete rather than comparing unprefixed declarations with listener paths. After Arc initialization, the advisor
compares raw security annotations with already-materialized interceptor bindings. A discrepancy, or a native
additional-secured-method declaration, makes the affected endpoint's annotation evidence unknown. Unrelated
transformations do not invalidate all endpoints. Binding agreement is not proof of an actual `SecurityCheck` or
request authorization, and this is not a universal inventory of transformed routes and security checks.
The native annotation stores are lazy, so even an annotation query can execute application transformation code;
the advisor does not query those stores or replay transformers. Independent configuration findings are retained.
A forwarding configuration (`quarkus.http.proxy.proxy-address-forwarding=true`) is not treated as proof
that a proxy actually terminates TLS, so listener-level transport findings remain visible and explain that
a verified terminating proxy can make them acceptable.
A handful of rules — marked **Quarkus-specific** below — have no Spring Security equivalent at all: they
cover Quarkus-only capabilities (gRPC, GraphQL, SmallRye Reactive Messaging). The rest address the same concerns the Spring Security advisor
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
- **Detects**: Supported observations find no active OIDC, JWT, basic, form, or mTLS mechanism, protective permission
  policy, restrictive endpoint annotation, default roles, or deny-unannotated default, while at least one REST-server
  endpoint is declared. `@PermitAll` and `policy=permit` are not restrictive controls. REST clients and unrelated secured
  beans are not endpoint evidence; unsupported custom authorization or incomplete endpoint metadata prevents an
  absence conclusion.
- **Recommendation**: Add an auth mechanism (`quarkus-oidc`, `quarkus-smallrye-jwt`, `quarkus.http.auth.basic`) or
  restrict endpoints with `@RolesAllowed`/`@PermissionsAllowed`/ `quarkus.http.auth.permission.*`.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTH-002 - Basic authentication without TLS

- **Severity**: HIGH
- **Detects**: Basic authentication is active while `quarkus.http.insecure-requests=enabled` accepts plain HTTP.
  Credentials submitted through that listener would lack transport encryption; the scan does not observe submitted
  credentials or external ingress policy.
- **Recommendation**: Set `insecure-requests=redirect` (or `disabled`) and configure SSL.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTH-003 - Review form authentication CSRF defenses

- **Severity**: LOW
- **Detects**: `quarkus.http.auth.form.enabled=true` (cookie-based login) without the `io.quarkus:quarkus-rest-csrf`
  extension, or with `quarkus.rest-csrf.enabled=false` or `quarkus.rest-csrf.verify-token=false`. This is a review of the
  standard defense, not proof that custom CSRF defenses are absent. Extension presence alone does not establish
  verification or coverage of every path, method, or media type.
- **Recommendation**: Verify coverage of state-changing browser requests; use `quarkus-rest-csrf` and embed its token
  in forms where appropriate.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTH-004 - JWT verification without an expected issuer

- **Severity**: MEDIUM
- **Detects**: The JWT capability and a supported verification key source are active, but `mp.jwt.verify.issuer` is
  absent. Supported sources include the inline MicroProfile key, `mp.jwt.verify.publickey.location`, and the overriding
  `smallrye.jwt.verify.key.location`. Custom verifiers remain unknown; no verifier is executed to establish acceptance.
- **Recommendation**: Set `mp.jwt.verify.issuer` to the expected token issuer.
- **Learn more**: <https://quarkus.io/guides/security-overview>

> **Retired: QS-AUTH-005.** Proactive authentication controls when credentials are processed, not whether
> authorization is enforced. The supported deferred mode is not an independent security deficiency.
> Authorization coverage is reviewed by the applicable authorization rules; this ID remains reserved.

> **Retired: QS-AUTH-006** (JWT signature algorithm not pinned for a remote JWKS) was removed. MicroProfile
> JWT 2.1 defines `mp.jwt.verify.publickey.algorithm` with an `RS256` default and explicitly describes the
> property as the algorithm whitelist. Leaving it unset therefore does not accept an unbounded algorithm set;
> the old rule reported a missing explicit preference even though the effective verifier remained pinned.
> The rule id is retired and will not be reused.

### QS-AUTH-007 - Embedded identity store enabled in the current runtime

- **Severity**: MEDIUM
- **Detects**: The properties identity-store capability is present and the observed runtime enables
  `quarkus.security.users.embedded.enabled`. The embedded store is distinct from the file store. Its use in the current
  runtime does not establish that a production deployment enables it.
- **Recommendation**: Review the identity-store choice for each deployment; keep demonstration users local and use an
  appropriate production identity provider or password-hashing store.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTH-008 - JWT verification without audience validation

- **Severity**: MEDIUM
- **Detects**: The JWT capability and a supported verification key source are active, but no
  `mp.jwt.verify.audiences` declaration is observed. Scalar and supported indexed lists are recognized. This reviews
  the expected audience configuration rather than executing tokens or inferring custom validation from unrelated beans.
- **Recommendation**: Set `mp.jwt.verify.audiences` to this service's expected audience(s).
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTH-009 - Review static JWT trust-anchor rotation

- **Severity**: INFO
- **Detects**: Supported JWT configuration selects an inline `mp.jwt.verify.publickey` without an overriding key
  location.
- **Why it matters**: Static trust anchors are supported and can rotate out of band. This is an operational reminder,
  not an inherent weakness or a requirement to use remote JWKS.
- **Recommendation**: Document and test the trust-anchor replacement process.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTH-010 - JDBC identity store using clear-text password mapper

- **Severity**: HIGH
- **Detects**: The JDBC security capability and `quarkus.security.jdbc.enabled=true` are active, and a supported
  `quarkus.security.jdbc.principal-query[.<name>].clear-password-mapper.enabled=true` declaration selects clear-text
  password comparison. Unrelated keys containing `principal-query`, disabled stores, and unsupported named-query
  syntax do not establish this finding.
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
- **Recommendation**: Use a production identity provider or a supported adaptive password-hashing store. The embedded
  store's legacy digest default is not a recommendation for modern production password storage.
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
- **Detects**: An active auth mechanism and declared REST-server endpoints are observed, but no supported restrictive
  permission policy, endpoint annotation, default roles, or deny-unannotated default is found. Disabled policies and
  `policy=permit` do not count as protection. Unsupported custom/global policies or endpoint metadata remain unknown.
- **Recommendation**: Add `@RolesAllowed`/`@PermissionsAllowed`/`@Authenticated` or path permissions with
  `policy=authenticated`/roles.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-AUTHZ-002 - Permission policy permits all paths

- **Severity**: INFO
- **Detects**: A non-shared, `applies-to=all` permission declares `policy=permit` for `/*` without a method restriction.
  Supported scope names are case-normalized, including the native `ALL` default; unsupported values remain unknown.
  `/` is an exact path, not an application-wide wildcard. This is a public-default declaration, not proof that
  authentication is disabled: more-specific mappings, same-path restrictions, shared policies, endpoint annotations,
  and REST defaults can still restrict requests.
- **Recommendation**: Confirm the public default is intentional; narrow it or select a restrictive policy where needed.
- **Learn more**: <https://quarkus.io/guides/security-overview>

> **Retired: QS-AUTHZ-003.** An arbitrary annotation ratio is not effective authorization coverage.
> Path policies, defaults, public declarations and custom policies cannot be reduced to a percentage of annotated
> methods. The ID remains reserved.

### QS-AUTHZ-004 - No deny-by-default for unannotated endpoints

- **Severity**: MEDIUM
- **Detects**: Authentication is active, deny-unannotated and default roles are absent in the current runtime, and a
  directly declared unannotated REST endpoint has no supported matching restriction. The bounded analysis distinguishes
  exact `/` from `/*`, longest-path and exact-path precedence, method-specific mappings, same-path restrictions, and
  shared policies. HTTP (`all`) and REST (`jaxrs`) scopes choose their matching policies independently, then intersect
  restrictions: a more-specific permit in one phase cannot override denial in the other.
  A matched path with no matching method mapping **denies** that method; a GET-only mapping does not
  establish that POST is public. Method annotations override class annotations, and explicit `@PermitAll` declarations
  are not counted as accidentally unannotated. Custom policies, unresolved route templates, inherited/transformed
  metadata, non-default listener prefixes, and unsupported scopes remain unknown rather than being executed or
  treated as public.
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
- **Detects**: No supported main-listener TLS material is declared. Recognized forms include a legacy keystore,
  paired certificate/key lists, TLS-registry JKS/P12 paths, and paired PEM certificate/key entries. A named registry
  bucket only counts when selected by `quarkus.http.tls-configuration-name`; an unrelated client bucket does not.
  Partial material and programmatic providers remain incomplete, and a declaration is not proof of usable HTTPS.
  Password, alias, ordering, and other option defaults are not certificate material and do not, by themselves, make
  TLS observation incomplete.
- **Recommendation**: Acceptable behind a verified terminating proxy.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-TLS-003 - TLS certificate validation disabled

- **Severity**: HIGH
- **Detects**: `trust-all=true` is set on the default TLS registry bucket (`quarkus.tls.trust-all`) **or any named
  bucket** (`quarkus.tls.<name>.trust-all`), disabling peer certificate validation wherever that bucket is used and
  creating a transport-validation risk. The scan does not establish whether every named bucket is consumed.
- **Recommendation**: Remove `trust-all`; import the peer's CA into a trust-store instead.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-TLS-004 - Identity-provider and JWK endpoints should use HTTPS

- **Severity**: HIGH
- **Detects**: An active OIDC tenant's auth-server URL or an active JWT verifier's effective remote key location uses
  plain HTTP. `smallrye.jwt.verify.key.location` overrides `mp.jwt.verify.publickey.location`; stale configuration for
  absent capabilities or disabled tenants does not establish a finding. No discovery, key retrieval, or other network
  request is made, and endpoint values are not retained in report samples.
- **Recommendation**: Use HTTPS endpoints with certificate validation enabled.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-TLS-005 - TLS hostname verification disabled

- **Severity**: HIGH
- **Detects**: `quarkus.tls.hostname-verification-algorithm=NONE`, the equivalent setting on a named TLS registry
  bucket, or legacy OIDC `tls.verification=certificate-validation` validates certificate chains without checking that
  the certificate belongs to the requested host. A named OIDC TLS configuration supersedes the legacy OIDC setting and
  avoids a duplicate/obsolete finding.
- **Recommendation**: Enable hostname verification for each applicable consumer. TLS-registry defaults depend on the
  consumer; the presence of a bucket alone does not establish a connection using it.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## CORS

### QS-CORS-001 - CORS allows any origin

- **Severity**: LOW
- **Detects**: Enabled CORS permits universal **noncredentialed** response sharing. A literal `*` is universal only
  as the sole origin; recognized universal regexes (`/.*/` or `/^.*$/`) also match universally inside a list.
  `*,https://app.example` is not a universal literal wildcard, while `/.*/,https://app.example` is universal.
  Empty origins remain restrictive. Explicit `quarkus.http.cors.enabled=false` wins over the legacy enable setting.
  Arbitrary regexes are not executed or classified as safe.
- **Recommendation**: Confirm public response sharing is intentional, or configure trusted origins. CORS is not an
  authentication or authorization boundary.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-CORS-002 - CORS wildcard origin with credentials

- **Severity**: HIGH
- **Detects**: A supported universal origin configuration allows credentials. Explicit
  `quarkus.http.cors.access-control-allow-credentials` wins; otherwise both exact and regex origin matches default
  credentials to `true`. A sole literal `*` defaults credentials to `false`, unlike a matching universal regex.
  Quarkus reflects the request Origin, so this is not the browser-rejected literal `Access-Control-Allow-Origin: *`
  plus credentials combination. QS-CORS-001 is not also emitted for the same credentialed policy.
- **Recommendation**: Pin explicit origins; never combine wildcard with credentials.
- **Learn more**: <https://quarkus.io/guides/security-overview>

> **Retired: QS-CORS-003.** Reflecting requested methods/headers for an allowed origin is supported
> Quarkus/Fetch behavior, not an independent bypass of the origin trust boundary. Least-privilege API design
> can still use narrower lists. The ID remains reserved.

> **Retired: QS-CORS-005.** Unset origins are restrictive, not a security gap. The filter is not inert:
> it rejects disallowed cross-origin requests. The ID remains reserved.

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
- **Detects**: A supported global HSTS declaration has an invalid lifetime, disables HSTS with `max-age=0`, or uses a
  lifetime shorter than one year. Zero disables the policy; a short nonzero lifetime may be an intentional rollout.
  Multiple or scoped declarations are not combined into an effective delivered policy.
- **Recommendation**: Review the lifetime and rollout plan. One year is a review baseline, not a protocol minimum.
  `includeSubDomains` is optional and should be enabled only when every subdomain is HTTPS-ready.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-HDR-002 - Weak Content-Security-Policy

- **Severity**: MEDIUM
- **Detects**: One supported global enforcing CSP permits unsafe inline scripts, unsafe evaluation, or an unrestricted
  script source. The bounded parser observes `script-src`/`default-src` fallback, `script-src-elem` and
  `script-src-attr`, first-duplicate-directive semantics, nonce/hash/`strict-dynamic` exceptions, and restrictive
  script overrides. Style-only inline permissions and scoped host wildcards are not arbitrary-script-source proof.
  Report-only policy does not establish enforcement; enforcing plus report-only is valid. Multiple policies,
  unsupported syntax, unknown custom-writer ordering, and uncertain path/method scope remain incomplete.
- **Recommendation**: Remove unsafe-inline/unsafe-eval and wildcard sources; use nonces/hashes for scripts.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-HDR-003 - Missing Strict-Transport-Security header

- **Severity**: LOW
- **Detects**: Declared document endpoints and supported listener TLS configuration are present, but no global HSTS
  declaration is observed. This is configuration review, not proof of missing delivered headers. Custom filters,
  proxies, and uncertain header scope prevent an absence conclusion.
- **Recommendation**: Add `quarkus.http.header."Strict-Transport-Security".value=max-age=31536000`; add
  `includeSubDomains` only after every subdomain is HTTPS-ready.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-HDR-004 - Missing Content-Security-Policy header

- **Severity**: LOW
- **Detects**: Declared document endpoints have no observed global enforcing CSP declaration. A report-only policy
  alone is not enforcing, but an additional report-only policy does not invalidate an enforcing one. API-only or
  uncertain document applicability, custom filters, and scoped/multiple header declarations remain incomplete.
- **Recommendation**: Add a CSP tailored to the app's script/style/asset origins.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-HDR-005 - Missing clickjacking protection

- **Severity**: LOW
- **Detects**: Declared document endpoints have no supported restrictive framing declaration after applying precedence.
  An enforcing CSP `frame-ancestors` directive overrides X-Frame-Options even when permissive (`*`); an empty ancestor
  list blocks all framing. Valid global `X-Frame-Options: DENY`/`SAMEORIGIN` is an alternative only when the enforcing
  ancestor directive is known absent. Invalid XFO values do not establish protection. Report-only directives do not
  override XFO; unknown CSP composition or writer/path/method scope cannot be assumed safe because XFO exists.
- **Recommendation**: Use a restrictive enforcing CSP `frame-ancestors`, such as `'none'`. XFO `DENY` is an alternative
  only without an overriding enforcing ancestor directive.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-HDR-006 - Missing X-Content-Type-Options header

- **Severity**: LOW
- **Detects**: No valid global `X-Content-Type-Options=nosniff` declaration is observed. Header names and the
  recognized value are compared case-insensitively; an arbitrary nonblank value does not count. Scoped headers and
  custom response filters remain unknown, and the scan does not observe proxy-delivered or runtime-written headers.
- **Recommendation**: Add `quarkus.http.header."X-Content-Type-Options".value=nosniff`.
- **Learn more**: <https://quarkus.io/guides/security-overview>

> **Retired: QS-HDR-007.** Modern browsers default to `strict-origin-when-cross-origin`; the absence of an explicit
> Referrer-Policy does not establish the full-URL disclosure asserted by the former rule. Applications can still select
> an explicit policy for their needs. The ID remains reserved.

> **Retired: QS-HDR-008.** Missing Permissions-Policy alone does not establish a meaningful defect without application
> feature and embedding context. Feature-specific browser policy remains a design choice. The ID remains reserved.

## Dev exposure

### QS-DEV-001 - OIDC TLS verification disabled

- **Severity**: HIGH
- **Detects**: `quarkus.oidc.tls.verification=none` disables provider certificate validation. This legacy setting is
  deprecated in Quarkus 3.33 in favor of a TLS registry configuration. Only active tenants are considered; a selected
  named TLS configuration overrides the legacy setting.
- **Recommendation**: Sometimes used against a local dev provider, but must never reach production.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-DEV-002 - Swagger/GraphQL UI always included

- **Severity**: MEDIUM
- **Detects**: The corresponding capability is present and supported local prod declarations enable
  `quarkus.swagger-ui.always-include` or `quarkus.smallrye-graphql.ui.always-include`. An explicit `%prod=false` overrides
  base `true`; a dev-only declaration is not production evidence. Inclusion does not prove an enabled route,
  anonymous access, or a running production deployment. `quarkus.smallrye-openapi.always-include` does not exist and
  is not evaluated.
- **Recommendation**: Restrict it to dev, or remove `always-include`.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-DEV-003 - SmallRye Health UI always included

- **Severity**: LOW
- **Detects**: The Health capability is present and supported local prod declarations enable
  `quarkus.smallrye-health.ui.always-include`, honoring explicit prod overrides. Inclusion is distinct from route
  availability, access policy, and production exposure; none is inferred from inclusion alone.
- **Recommendation**: Remove the override so the Health UI is only available outside production, or protect it via the
  management interface / a permission policy.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## OIDC

### QS-OIDC-001 - OIDC without token audience validation

- **Severity**: HIGH
- **Detects**: OIDC is configured for a default or named `service`/`hybrid` token-consuming tenant without that tenant's
  expected `quarkus.oidc[.<tenant>].token.audience`, or with the exact singleton `any`, which disables audience
  validation. Scalar and supported indexed lists are recognized; `any,service` is not the singleton sentinel.
  Absent capabilities, disabled tenants, and unknown custom validation are not missing-audience proof.
  Pure `web-app` authorization-code clients are excluded because their primary authentication
  artifact is an OIDC ID token whose audience is validated against the client id by the protocol implementation;
  applying this resource-server rule to them would be a false positive.
- **Why it matters**: The severity is HIGH for service/M2M flows because RFC 8725 requires each JWT application to
  validate that the token was issued for it.
- **Recommendation**: Set the tenant's token audience to this resource server's expected audience.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-OIDC-002 - OIDC web-app session cookie not forced secure

- **Severity**: MEDIUM
- **Detects**: An active OIDC `web-app`/`hybrid` tenant does not force cookie Secure while the listener accepts HTTP.
  Without the override, Secure is request-dependent; HTTPS key material alongside an accepted HTTP listener does not
  make HTTP session cookies secure. Disabled or redirected HTTP does not trigger this finding.
- **Recommendation**: Disable or redirect HTTP and review `quarkus.oidc.authentication.cookie-force-secure`, including
  trusted proxy handling. The scan does not establish external TLS termination.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-OIDC-003 - Public OIDC client without PKCE

- **Severity**: MEDIUM
- **Detects**: Supported client-authentication metadata establishes a public `web-app`/`hybrid` client without PKCE.
  Missing literal `credentials.secret` alone is insufficient: client-secret providers, JWT client authentication,
  supported key/secret-provider settings, and provider presets are considered. Spotify and Twitter/X presets enable
  PKCE by default; an explicit `authentication.pkce-required=false` overrides that default. Dynamic tenants, custom
  providers, unsupported source/profile combinations, and incomplete credential metadata remain unknown.
- **Recommendation**: Set `quarkus.oidc.authentication.pkce-required=true` for public clients.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-OIDC-004 - OIDC token issuer validation is bypassed

- **Severity**: HIGH
- **Detects**: The exact lowercase `quarkus.oidc[.<tenant>].token.issuer=any` disables issuer matching for an active
  default or named tenant. `ANY` is not that sentinel. Supported provider defaults also count: the Microsoft preset
  supplies `any` unless explicitly overridden. Disabled tenants are excluded, and no token or tenant resolver is invoked.
- **Recommendation**: Remove `token.issuer=any` and pin the exact trusted issuer; use explicit tenant resolution when
  multiple issuers are intentional.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## Management

### QS-MGMT-001 - Management interface on a non-loopback host

- **Severity**: LOW
- **Detects**: The separate management interface is enabled in the observed runtime and its resolved
  `quarkus.management.host` is non-loopback. Binding is not proof of remote reachability or anonymous access.
  Current runtime evidence is separate from the bounded prod declarations reviewed by QS-MGMT-003. Launch mode must
  not be inferred from a profile name: `RUN` and `NORMAL` can both default to `prod`, while BootUI remains excluded
  from `NORMAL`.
- **Recommendation**: Bind the host to `127.0.0.1`, or protect the management endpoints.
- **Learn more**: <https://quarkus.io/guides/security-overview>

> **Retired: QS-MGMT-002.** Sharing an application namespace does not itself create endpoints, remove
> authorization or prove wider exposure. Paths and protection need endpoint-specific evidence.
> The ID remains reserved.

### QS-MGMT-003 - Management interface has no explicit prod-scoped host binding

- **Severity**: INFO
- **Detects**: Supported local prod declarations enable management without either a base
  `quarkus.management.host` or `%prod.quarkus.management.host` declaration. Explicit prod `enabled=false` wins over
  base `true`; an inactive production declaration does not trigger the rule. Unresolved or unsupported source/profile
  evidence remains incomplete.
- **Why it matters**: The prod-profile default is all interfaces, unlike the dev/test loopback default. This reviews
  a possible deployment configuration, not an observed production listener. It can differ from the runtime state
  reviewed by QS-MGMT-001 and does not establish launch mode, firewall policy, or endpoint authorization.
- **Recommendation**: Explicitly pin `%prod.quarkus.management.host` to `127.0.0.1`, or to the intended bind address.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## Config hygiene

### QS-CFG-001 - Possible secret in configuration

- **Severity**: MEDIUM
- **Detects**: A config key's terminal segment identifies a password, secret, API key, private key, token, or
  access/refresh token set to a literal value (not an externalized `${...}` reference). Scans application and `%prod`
  configuration, including the `quarkus.*` namespace — e.g. `quarkus.datasource.password`,
  `quarkus.oidc.credentials.secret`, `quarkus.mail.password` are all in scope, alongside application-owned keys.
- **Scope**: Only recognized local application properties/YAML source provenance is inspected for literal
  credentials. Environment-variable, system-property, config-tree, remote, and custom sources are not enumerated or
  read to infer committed secrets. `${...}` expressions, `%dev`/`%test` values, BootUI internals, and metadata keys such
  as `quarkus.oidc.token.issuer` are excluded. Bounded safe labels enter report samples; values and application exception
  text never do. This is local source hygiene, not proof of credential exposure, current use, or production deployment.
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

- **Severity**: LOW
- **Detects**: Active form authentication selects `quarkus.http.auth.form.cookie-same-site=none`, allowing cross-site
  cookie use. This can be an intentional compatibility choice, not an independent proof of missing CSRF defenses.
- **Recommendation**: Use Secure with SameSite=None and verify independent CSRF defenses. Prefer Strict/Lax when
  compatible with the required browser flows.
- **Learn more**: <https://quarkus.io/guides/security-overview>

### QS-SESSION-003 - Long form-auth idle timeout

- **Severity**: LOW
- **Detects**: Active form authentication has a supported parsed `quarkus.http.auth.form.timeout` of at least eight
  hours. This is an **idle** timeout, not an absolute session lifespan: active sessions can renew. Eight hours is a
  heuristic review threshold; invalid or unsupported durations never become a passing default.
- **Recommendation**: Lower the timeout (the Quarkus default is 30 minutes) and pair it with `new-cookie-interval`.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## gRPC

### QS-GRPC-001 - gRPC server reflection enabled in the prod profile

- **Severity**: MEDIUM
- **Detects**: **Quarkus-specific.** The gRPC capability and a declared server service are present, and supported
  local prod declarations enable `quarkus.grpc.server.enable-reflection-service`. Explicit `%prod=false` overrides
  base `true`; dev-only settings and client-only capability do not establish this finding.
- **Why it matters**: Reflection makes service/schema metadata discoverable to permitted callers. This is a production
  configuration review, not proof of public access or a running production server.
- **Recommendation**: Remove the `%prod` override; keep reflection enabled only in `%dev`/`%test`.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## GraphQL

### QS-GRAPHQL-001 - GraphQL schema introspection enabled

- **Severity**: LOW
- **Detects**: **Quarkus-specific.** The GraphQL capability is present and supported local prod declarations leave
  `quarkus.smallrye-graphql.field-visibility` without `no-introspection`, honoring the prod override before the base
  declaration. This reviews schema-disclosure configuration, not the access policy of a running deployment.
  `quarkus.smallrye-graphql.introspection-enabled` does not exist and is not evaluated.
- **Why it matters**: Often intentional for public APIs, but worth a deliberate decision.
- **Recommendation**: Add `no-introspection` to `quarkus.smallrye-graphql.field-visibility` in `%prod` unless the schema
  is meant to be publicly discoverable.
- **Learn more**: <https://quarkus.io/guides/security-overview>

## Messaging

### QS-MSG-001 - Messaging credentials configured without an encrypted protocol

- **Severity**: HIGH
- **Detects**: **Quarkus-specific.** An enabled channel explicitly using the Kafka connector has declared SASL password
  or JAAS credentials with effective `PLAINTEXT` or `SASL_PLAINTEXT`. Protocol resolution follows channel, connector
  (`mp.messaging.connector.smallrye-kafka`), then global (`kafka`) settings. Global credentials can apply to a channel
  with an insecure override; a global bucket is not independently treated as a running channel. Disabled/non-Kafka
  channels are excluded, and implicit connectors or custom CDI configuration maps remain unknown without invoking
  producers. Report samples use bounded, value-free channel labels.
- **Why it matters**: `SASL_PLAINTEXT` lacks transport encryption for the selected authentication exchange.
  `PLAINTEXT` does **not** send SASL credentials; alongside declared credentials it indicates an inconsistent,
  unencrypted setup. The rule does not claim that every SASL mechanism transmits a raw password.
- **Recommendation**: Set `security.protocol=SASL_SSL` (or `SSL`) for each affected channel (or globally via
  `kafka.security.protocol`).
- **Learn more**: <https://quarkus.io/guides/security-overview>

## Audit sources and limits

The implementation review is pinned to **3.33.3.1**:

- [Permission mapping configuration](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/vertx-http/runtime/src/main/java/io/quarkus/vertx/http/runtime/PolicyMappingConfig.java)
  and [path matching policy](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/vertx-http/runtime/src/main/java/io/quarkus/vertx/http/runtime/security/AbstractPathMatchingHttpSecurityPolicy.java)
  establish exact paths, longest match, method mismatch denial and shared policy behavior.
- [CORSFilter](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/vertx-http/runtime/src/main/java/io/quarkus/vertx/http/runtime/cors/CORSFilter.java)
  establishes reflected origins, regex matching and the conditional credentials default.
- [OidcProvider](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/oidc/runtime/src/main/java/io/quarkus/oidc/runtime/OidcProvider.java),
  [tenant configuration](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/oidc/runtime/src/main/java/io/quarkus/oidc/runtime/OidcTenantConfig.java)
  and [known provider presets](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/oidc/runtime/src/main/java/io/quarkus/oidc/runtime/providers/KnownOidcProviders.java)
  define validation sentinels and provider-specific defaults.
- [Client credential configuration](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/oidc-common/runtime/src/main/java/io/quarkus/oidc/common/runtime/config/OidcClientCommonConfig.java)
  supports providers and JWT client authentication; missing a literal secret does not establish a public client.
- [Header configuration](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/vertx-http/runtime/src/main/java/io/quarkus/vertx/http/runtime/HeaderConfig.java)
  and [Kafka configuration reference](https://github.com/quarkusio/quarkus/blob/3.33.3.1/docs/src/main/asciidoc/kafka.adoc)
  define scope and inheritance that raw property-presence checks cannot replace.
- [LaunchMode](https://github.com/quarkusio/quarkus/blob/3.33.3.1/core/runtime/src/main/java/io/quarkus/runtime/LaunchMode.java)
  distinguishes `RUN` from `NORMAL`, although both default to the `prod` profile. Profile names are not proof of
  launch mode or network exposure; BootUI's existing `NORMAL` exclusion remains unchanged.
- [Fetch](https://fetch.spec.whatwg.org/#http-cors-protocol), [CSP3](https://www.w3.org/TR/CSP3/),
  [Referrer Policy](https://www.w3.org/TR/referrer-policy/),
  [OWASP headers](https://cheatsheetseries.owasp.org/cheatsheets/HTTP_Headers_Cheat_Sheet.html),
  [JWT BCP](https://www.rfc-editor.org/rfc/rfc8725) and
  [OAuth BCP](https://www.rfc-editor.org/rfc/rfc9700) provide the browser/token context.

Custom policies, dynamic tenants, credentials providers, external sources and delivered headers are not evaluated
by executing application code. Unsupported evidence remains incomplete; no active testing or endpoint mutation is
performed. Retired IDs are permanently reserved.
