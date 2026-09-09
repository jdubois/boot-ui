# Security checks

The Security panel runs a fixed, on-demand ruleset against the host application's Spring Security configuration:
**54 servlet rules** and **25 reactive rules**. It inspects recognized, already-created filter chains and supported
configuration metadata. It does not execute application authorization managers, custom matchers, decoders, credential
providers or CORS/header callbacks to infer security decisions, initialize lazy application beans, intercept live
traffic, or modify security configuration. Credentials, keys and session identifiers never belong in findings.

The checks are heuristic review prompts. They highlight common Spring Security hardening gaps, but the right remediation
still depends on the application's threat model and deployment topology.

Actuator exposure checks ignore BootUI's own low-priority local actuator defaults. Those defaults are merged into
Spring Boot's shared `defaultProperties` source (only for keys the host has not set) so local panels can read Actuator
data, and host `management.*` settings always win. If the host application explicitly configures actuator exposure
or health detail properties, the checks continue to evaluate those values.

## Availability and bounds

Servlet, reactive, and [Quarkus](QUARKUS-CHECKS.md) reports share the
[score eligibility policy](features/advisors.md#score-eligibility). Unsupported or unreadable observations limit
coverage without discarding independent known findings. A score of 100 is not a security endorsement.

`SCANNED` versus `PARTIAL` follows applicable coverage, not the number
of skipped rules. Absent OAuth2, attached CORS, remember-me, or authorization-ordering targets do not earn completion
credit or introduce missing-evidence limitations. Genuine applicable unknowns and evaluation failures still do.
An unrelated custom filter does not invalidate independently inspected configuration, provider, method-security,
or header-writer facts. Custom CSRF matching is recorded separately from the known filter inventory and limits
applicable CSRF checks, not unrelated header checks; no matcher is executed.

The panel is available only when Spring Security is on the classpath and at least one application
`SecurityFilterChain` (servlet) or `SecurityWebFilterChain` (WebFlux) bean exists.
If Spring Security is absent or no filter chains are registered, BootUI returns a stable empty report with an explanatory
status. Unsupported or unreadable observations remain unknown: dependent rules do not manufacture a passing result or
a missing-control finding. Incomplete collection is summarized as a partial scan; genuine collection/evaluation
failures use sanitized analysis errors. Known findings remain visible when unrelated evidence is incomplete.
Inventories and recursive inspection are bounded; reaching a bound also makes the scan incomplete.

Native configuration-map wrappers are inspected before any backing-map method is called. This bounded,
read-only lookup tries ordinary reflection and then optional reflective `sun.misc.Unsafe` access to a
validated JDK map field; it never writes memory or changes JVM options. If the JDK denies or does not
provide this access, affected configuration remains unknown rather than being treated as absent.
JDKs may emit deprecation warnings or diagnostic events for the optional internal access.
Native bootstrap and callback-safety regressions cover Java 17, 21 and 25.

Here, "native" framework objects means the framework's own implementations, not necessarily a GraalVM executable.
GraalVM additionally requires reflection metadata for the passive readers' private fields. BootUI supplies
classpath-conditional field hints for supported Spring Security chains, filters, headers, matchers, provider
metadata, Spring environment sources, Actuator descriptors and the embedded Tomcat context. The hints cover the
servlet and reactive collectors without making servlet or optional security dependencies mandatory. They do not
register arbitrary application callbacks or execute them. Without these hints, even a live `FilterChainProxy`
can lose its readable `filterChains` inventory, yielding zero observed chains and an unusable partial report.
Public-method hints alone cannot preserve that inventory.
Spring AOT also moves factory-method provenance into its generated `BeanInstanceSupplier`. BootUI reads that
framework metadata without invoking its generator, so BootUI's own console chain stays excluded from the application
assessment in AOT mode. A coincidental bean name or an application-supplied metadata callback is not trusted.

The container publication smoke test explicitly scans the real servlet sample on JVM, AOT, CRaC and GraalVM native
images and checks that all three chains produce usable evidence while retaining genuine incomplete coverage.
Runtime-hints tests independently pin private-field access and optional-dependency absence. Custom implementations,
unregistered application reflection, compiler-generated suppliers, and unsupported observations still remain
unknown; the hints do not guarantee full security coverage or JVM/native score equality. Quarkus uses its own
build-time/provider observations and is not affected by this Spring metadata fix; Quarkus native BootUI remains out
of scope.

For the standard embedded Tomcat servlet context, the passive reader inspects the exact native facade/context
init-parameter map rather than blocking all lower configuration sources. Custom contexts, subclasses, unsupported
maps, and callbacks remain opaque. The real Spring sample regression uses embedded Tomcat and the application's
actual three filter chains. It now retains completed evidence, but deliberately remains partial: the sample's custom
CSRF-cookie filter is not a supported framework filter, role-based authorization is not a constant grant/denial,
parameterized Actuator operations are not completely modeled, and credential-source classification remains
incomplete for native property-source barriers. This last limitation is not a finding of exposed credentials.
Missing OAuth2 or remember-me features are not reasons for that partial assessment. The unused demo
`NoOpPasswordEncoder` bean is not an active-provider finding.

The catalogs were audited against Spring Boot **4.1.1**, which manages Spring Security **7.1.1**.
Configuration declarations cannot prove ingress TLS, delivered response headers, custom authorization semantics or
arbitrary decoder-local validation. Public APIs, browser applications and externally managed security need different
interpretation; a review prompt is not a confirmed vulnerability.

## Severity scale

- **CRITICAL** - a configuration that directly exposes credentials, secrets, or critical security controls and needs immediate attention.
- **HIGH** - a configuration that commonly leaves the application exposed and usually needs attention before production.
- **MEDIUM** - a hardening gap that warrants review.
- **LOW** - lower-impact hygiene findings.
- **INFO** - informational prompts where the right fix depends heavily on project context.

The Rule results panel lists only checks that found findings, ordered by severity, finding count, and rule id. Each rule
includes up to a handful of sample details plus a remediation link.

The advisor score applies the shared severity penalty to every concrete finding, not just once per violated rule.
Dismissed rules remove all of their findings from the score.

---

## Authentication & passwords

### SEC-AUTH-001 - Password encoder must not store credentials in plain text

- **Severity**: CRITICAL
- **Detects**: A supported active DAO provider selects `NoOpPasswordEncoder` for new password encoding. Unused encoder beans are not evidence of effective storage; custom providers and unreadable suppliers remain unknown and are never invoked.
- **Recommendation**: Use a delegating encoder (PasswordEncoderFactories.createDelegatingPasswordEncoder()) backed by bcrypt, Argon2, or PBKDF2.
- **Learn more**: <https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html>

### SEC-AUTH-002 - Password encoder should not use a weak or legacy algorithm

- **Severity**: HIGH
- **Detects**: A supported active provider selects a legacy encoder for new hashes. The selected encoder inside a `DelegatingPasswordEncoder` is inspected; its normal legacy matching map does not cause a finding.
- **Recommendation**: Migrate to bcrypt, Argon2, or PBKDF2 via a DelegatingPasswordEncoder so hashes upgrade over time.
- **Learn more**: <https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html>

> **Retired: SEC-AUTH-003.** `DaoAuthenticationProvider` has a delegating password encoder by default.
> Inline provider configuration and external identity systems also do not require an exported `PasswordEncoder` bean.
> Absence of that bean was not evidence of weak password storage. The ID will not be reused.

### SEC-AUTH-006 - BCrypt password encoder should use an adequate work factor

- **Severity**: LOW
- **Detects**: A supported active provider selects bcrypt with a readable strength below the framework default of 10. The selected delegated encoder is included; unknown algorithms or unreadable costs remain incomplete rather than passing.
- **Recommendation**: Use a BCrypt strength of at least 10 (the default) so password hashing stays computationally expensive; raise it as hardware improves, or migrate to Argon2/PBKDF2.
- **Learn more**: <https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html>

### SEC-AUTH-004 - Review the Boot property-backed default account

- **Severity**: MEDIUM
- **Detects**: An active Boot-created account has an explicitly configured password. Native factory provenance and attachment to an active provider are required; user properties or a coincidental bean name are insufficient after auto-configuration backs off. A username-only configuration can still use a generated password and belongs to SEC-AUTH-009.
- **Recommendation**: Replace the single property-based user with a real UserDetailsService or identity provider for anything beyond local demos.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/spring-security.html>

> **Retired: SEC-AUTH-005.** Customizing Spring's supported generated login page is a product/design choice,
> not evidence of stronger authentication. Its appearance no longer adds an advisor finding; the ID remains reserved.

### SEC-AUTH-007 - HTTP Basic authentication should run only over HTTPS

- **Severity**: HIGH
- **Detects**: Reviews HTTP Basic in production without observed direct TLS or a supported unconditional redirect on that chain. Explicit `server.ssl.enabled=false` defeats inference from key-store/bundle properties. Forwarded-header configuration, another chain's redirect, and custom redirect behavior do not establish transport protection.
- **Recommendation**: Require HTTPS at the server or trusted edge for Basic authentication. Verify edge TLS separately; forwarding configuration alone is not transport protection.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/basic.html>

### SEC-AUTH-008 - hideUserNotFoundExceptions should stay enabled

- **Severity**: MEDIUM
- **Detects**: An active supported DAO provider has `hideUserNotFoundExceptions=false`, retaining distinct internal unknown-user exceptions. Failure handlers and externally visible responses are not observed; this does not prove an externally visible username-enumeration oracle.
- **Recommendation**: Prefer the default `hideUserNotFoundExceptions=true` and separately verify that response handlers do not disclose account existence.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/dao-authentication-provider.html>

### SEC-AUTH-009 - Do not run production on Spring Boot's auto-generated default user

- **Severity**: HIGH
- **Detects**: A production profile uses the active Boot-created development account without an explicit password. A configured username does not prevent password generation. Native factory provenance and active provider attachment are required; unrelated or application-owned in-memory users are not treated as Boot's default account. Explicit passwords are handled by SEC-AUTH-004 without a duplicate finding.
- **Recommendation**: Register a real UserDetailsService, AuthenticationProvider, or external identity provider before running in production; do not rely on the console-logged generated password.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/spring-security.html>

### SEC-AUTH-010 - Form login should run only over HTTPS

- **Severity**: HIGH
- **Detects**: Reviews production form login without observed direct TLS or a supported unconditional redirect on that chain. Upstream TLS and custom redirects remain outside the observation; forwarding configuration alone is not TLS evidence.
- **Recommendation**: Enforce HTTPS at the server or trusted edge; verify proxy TLS independently from forwarded-header handling.
- **Learn more**: <https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#transmit-passwords-only-over-tls-or-other-strong-transport>

## Authorization

### SEC-AUTHZ-001 - Every filter chain should enforce authorization

- **Severity**: HIGH
- **Detects**: A known chain installs no standard HTTP `AuthorizationFilter`. Custom filters, method controls, and external controls are not observed, so absence does not prove all matched requests are unguarded.
- **Recommendation**: Add authorizeHttpRequests(...) with at least anyRequest().authenticated() (or an explicit denyAll) to the chain.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html>

### SEC-AUTHZ-002 - Avoid blanket permitAll authorization

- **Severity**: HIGH
- **Detects**: A supported structural unconditional grant appears in a chain that also configures authentication. Typed matcher and constant-authorization facts are inspected in order; no finite request samples, application matchers, or authorization callbacks are executed. Unsupported mappings remain unknown.
- **Recommendation**: Restrict sensitive paths and finish with anyRequest().authenticated(); keep permitAll only for genuinely public endpoints.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html>

### SEC-AUTHZ-003 - Application security should not be effectively disabled

- **Severity**: HIGH
- **Detects**: The complete supported chain inventory grants requests unconditionally, includes an unconditional catch-all, and installs no real authentication mechanism. Failed or unsupported chains retain their position and prevent a global conclusion. This scope does not duplicate SEC-AUTHZ-002's authenticated-chain review.
- **Recommendation**: Define authorization rules that require authentication for non-public endpoints instead of leaving the app fully open.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html>

### SEC-AUTHZ-004 - Catch-all filter chains should be ordered last

- **Severity**: INFO
- **Detects**: A structurally unconditional, method-agnostic chain precedes later chains. A GET-only `/**` matcher is not a global catch-all. Typed composites retain unknown branches; rendered descriptions are never used to prove coverage.
- **Recommendation**: Give earlier chains an explicit securityMatcher and keep the catch-all (any request) chain last by @Order.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/configuration/java.html#_multiple_httpsecurity_instances>

### SEC-AUTHZ-005 - Broader authorizeHttpRequests matchers should not shadow narrower ones

- **Severity**: INFO (HIGH for a supported unconditional grant shadowing a later constant denial)
- **Detects**: An unconditional, method-agnostic matcher precedes later authorization mappings in the same chain. The default is an ordering advisory; grant, denial, and unknown authorization effects are distinguished. Only a known grant hiding a later constant denial raises severity to HIGH. Unsupported matcher/manager structure remains unknown.
- **Recommendation**: Register narrower matchers (e.g. requestMatchers("/admin/**").hasRole("ADMIN")) before the broader catch-all, or replace the catch-all with anyRequest() so later requestMatchers additions are rejected at startup instead of silently ignored.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html>

## CSRF

### SEC-CSRF-001 - CSRF protection should stay on for browser-automatic credentials

- **Severity**: HIGH
- **Detects**: Interactive form/OAuth2 login or remember-me credentials are configured without a `CsrfFilter`. Browser-credential relevance is independent of session persistence; `STATELESS` and simultaneous bearer authentication do not erase browser login or remember-me credentials. Custom CSRF matching remains unknown.
- **Recommendation**: Keep CSRF protection for automatically submitted browser credentials; header-bearer-only APIs have different applicability.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html>

### SEC-CSRF-002 - CSRF protection should stay on for HTTP Basic authentication

- **Severity**: MEDIUM
- **Detects**: HTTP Basic is configured without a `CsrfFilter`, independently of session state. Browsers can automatically resend Basic credentials. Mixed browser-login cases already covered by SEC-CSRF-001 do not receive this second penalty.
- **Recommendation**: Keep CSRF protection enabled for browser-reachable HTTP Basic endpoints, or use bearer credentials that browsers do not attach automatically.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html>

## Session management

### SEC-SESSION-001 - Session fixation protection should be enabled

- **Severity**: HIGH
- **Detects**: Recognized authentication-filter or session-management strategies explicitly disable session fixation protection. Modern form-login defaults use per-filter strategies without installing `SessionManagementFilter`; custom or unreadable strategies remain unknown.
- **Recommendation**: Use the default changeSessionId (or migrateSession) session-fixation strategy instead of none().
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html>

### SEC-SESSION-002 - Session cookie should set the Secure flag

- **Severity**: MEDIUM
- **Detects**: Reviews explicit `server.servlet.session.cookie.secure=false`, or absence of an explicit Secure override in production with observed session usage. An unset flag can derive Secure from the request; this is not proof that every emitted cookie is insecure. Custom cookie implementations and external transport behavior are not established by this property.
- **Recommendation**: Set server.servlet.session.cookie.secure=true so the session cookie is only sent over HTTPS.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SEC-SESSION-003 - Session cookie should set the HttpOnly flag

- **Severity**: MEDIUM
- **Detects**: Explicit `server.servlet.session.cookie.http-only=false` removes the configured HttpOnly safeguard for the supported servlet cookie setting. Actual cookies from custom session/cookie implementations are not observed.
- **Recommendation**: Keep server.servlet.session.cookie.http-only=true to mitigate cookie theft via XSS.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SEC-SESSION-004 - Session cookie should declare a SameSite policy

- **Severity**: LOW
- **Detects**: Reviews explicit servlet session-cookie SameSite configuration. An unset property in a session-using chain is **SKIPPED** because effective container and browser defaults are not observed; it is not a missing-policy violation.
- **Recommendation**: Set server.servlet.session.cookie.same-site=Lax (or Strict) to reduce cross-site request exposure.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

> **Retired: SEC-SESSION-005.** Leaving the timeout property unset still applies Boot's documented 30-minute
> default. Restating an effective default is not a security improvement. The ID remains reserved.

### SEC-SESSION-006 - Review bearer authentication saved in sessions

- **Severity**: LOW
- **Detects**: The actual bearer authentication filter saves its security context to a recognized HTTP-session repository. The holder filter's read repository and `SessionManagementFilter` presence do not establish bearer persistence. Custom save repositories remain unknown.
- **Recommendation**: Confirm persistence is intentional. For header-bearer-only APIs use a request-only save repository; mixed interactive login can intentionally retain sessions.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-stateless>

### SEC-SESSION-007 - Consider configuring concurrent session control

- **Severity**: INFO
- **Detects**: Detects an interactive form-login chain that maintains sessions but installs no ConcurrentSessionFilter (no maximumSessions limit).
- **Recommendation**: Consider `sessionManagement().maximumSessions(n)` if session concurrency limits fit the application. This optional INFO review does not make a maximum mandatory or prove account/session misuse.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html#ns-concurrent-sessions>

### SEC-SESSION-008 - Remember-me signing key should be sufficiently long

- **Severity**: MEDIUM
- **Detects**: A recognized token-based remember-me service uses a key shorter than 16 characters. The native mechanism is a digest signature, not the previously claimed HMAC. Length is not entropy; persistent-token services are outside this rule. Only length is retained, never the key value in findings.
- **Recommendation**: Configure a long, random remember-me key (16+ characters, generated from a secure source) via rememberMe().key(...), ideally sourced from an externalized secret rather than a literal in configuration.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authentication/rememberme.html>

> **Retired: SEC-SESSION-009.** Penalizing only customized names missed default cookies and did not establish
> whether a prefix's Secure/Domain/Path requirements were satisfied. Cookie prefixes remain useful contextual
> hardening, but a name alone is not proof of correct cookie protection. The ID remains reserved.

## Transport & security headers

### SEC-HEAD-001 - HTTP Strict Transport Security should be emitted

- **Severity**: MEDIUM
- **Detects**: Supported chain header configuration lacks a recognized HSTS writer. Default HSTS emission is conditional on a secure request; writer inventory does not prove delivery. Custom conditions, writers, and external HTTPS/header behavior remain unknown.
- **Recommendation**: Keep the default HstsHeaderWriter (served over HTTPS) so browsers pin TLS for the domain.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-hsts>

### SEC-HEAD-002 - X-Frame-Options (clickjacking protection) should stay enabled

- **Severity**: HIGH
- **Detects**: A browser-credential chain lacks an effective recognized framing restriction. An enforcing `frame-ancestors` directive overrides X-Frame-Options even when permissive (`*`); XFO is an alternative only when that enforcing directive is known to be absent. An empty ancestor source list blocks all framing, like `'none'`. Report-only CSP does not override XFO or provide enforcement. Unknown policies cannot establish safe XFO fallback. Arbitrary API chains are not assumed to serve documents; unsupported or multiple enforcing writers remain unknown.
- **Recommendation**: Use a restrictive enforcing `frame-ancestors` policy, or keep `XFrameOptionsHeaderWriter` (DENY/SAMEORIGIN) when that directive is absent. Verify the actual delivered policy separately.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-frame-options>

### SEC-HEAD-003 - A Content-Security-Policy should be defined

- **Severity**: LOW
- **Detects**: A browser-credential chain lacks a recognized enforcing CSP writer. Report-only is not enforcement. This reviews configured browser-document controls, not delivered headers or arbitrary API responses; custom and ambiguous writer composition remain unknown.
- **Recommendation**: Add a ContentSecurityPolicyHeaderWriter with a tailored policy to mitigate XSS and data injection.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-csp>

### SEC-HEAD-004 - X-Content-Type-Options should stay enabled

- **Severity**: LOW
- **Detects**: Supported chain header configuration omits a recognized `X-Content-Type-Options: nosniff` writer. Custom filters and external infrastructure may supply headers; actual delivery is not observed.
- **Recommendation**: Keep the default XContentTypeOptionsHeaderWriter so browsers do not MIME-sniff responses.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-content-type-options>

> **Retired: SEC-HEAD-005.** Modern browsers default to `strict-origin-when-cross-origin`; a missing explicit
> header does not establish the former cross-origin full-URL leakage claim. An application may still choose a
> stricter explicit policy. The ID remains reserved.

> **Retired: SEC-HEAD-006.** A missing Permissions-Policy header does not establish a universally unsafe
> browser-feature policy. Restrictions depend on document features, feature defaults, and embedding requirements.
> Applications can still configure a tailored policy; the ID remains reserved.

### SEC-HEAD-007 - Security response headers should not be globally disabled

- **Severity**: LOW
- **Detects**: A browser-credential chain installs no Spring `HeaderWriterFilter`. This does not prove all delivered security headers are absent: custom filters and external infrastructure can provide them. Individual missing-writer checks avoid repeating this same absence penalty.
- **Recommendation**: Remove headers().disable(); keep the default HeaderWriterFilter so security headers are emitted, and only tune individual writers you do not need.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html>

### SEC-HEAD-008 - Review HSTS max-age below the framework default

- **Severity**: LOW
- **Detects**: A supported HSTS writer has a max-age below Spring's one-year default. Zero removes a stored HSTS policy; shorter rollout periods can be deliberate. Omitting `includeSubDomains` is not an independent failure.
- **Recommendation**: Choose max-age for the deployment and rollout. Enable `includeSubDomains` only when all subdomains support HTTPS.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-hsts>

### SEC-HEAD-009 - Review permissive CSP script execution

- **Severity**: MEDIUM
- **Detects**: A supported single enforcing CSP permits unsafe effective script execution. The bounded parser observes `script-src-elem`/`script-src-attr` fallback, first duplicate directives, nonce/hash/`strict-dynamic` exceptions, and `unsafe-eval` under `script-src`/`default-src`. Arbitrary schemes/hosts differ from scoped wildcards. Missing unrelated hardening directives do not add penalties; framing is assessed separately by SEC-HEAD-002. Null, oversized, unsupported, comma-separated, or multiple enforcing policies remain unknown. Report-only policies do not change enforcing-policy conclusions.
- **Recommendation**: Restrict script execution with application-specific nonce/hash or trusted sources and remove unnecessary eval permissions.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-csp>

### SEC-HEAD-010 - Cross-origin isolation headers should be considered

- **Severity**: INFO
- **Detects**: A browser-credential chain's supported header configuration includes neither cross-origin opener nor embedder policy. Cross-origin isolation is capability-specific and optional, not a universal security requirement; arbitrary API chains and externally delivered headers are not inferred.
- **Recommendation**: Add CrossOriginOpenerPolicyHeaderWriter / CrossOriginEmbedderPolicyHeaderWriter via headers().crossOriginOpenerPolicy(...) / .crossOriginEmbedderPolicy(...) if the application needs cross-origin isolation (e.g. for SharedArrayBuffer) or Spectre-style side-channel hardening.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html>

## CORS

CORS evidence comes from supported sources attached to the observed security filters, not unused configuration
beans. Dynamic/custom sources and MVC-managed handling remain unknown and are never called. Dependent checks are
**SKIPPED** when no independently observed finding can be established; unknown is not a verified-safe configuration.
Raw origins and origin patterns are not copied into findings.

### SEC-CORS-001 - CORS should not allow all origins

- **Severity**: LOW
- **Detects**: A supported attached CORS policy permits wildcard origins without credentials. This can be intentional for public data and does not itself establish credential disclosure. Credentialed wildcard cases belong to SEC-CORS-002 without a duplicate penalty.
- **Recommendation**: Enumerate the exact trusted origins instead of "*"; use allowedOriginPatterns only for tightly-scoped patterns.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html>

### SEC-CORS-002 - CORS must not combine wildcard origins with credentials

- **Severity**: HIGH
- **Detects**: A supported attached policy combines credentials with wildcard trust. Literal `allowedOrigins="*"` plus credentials is rejected by Spring, not a functioning credential-exposure configuration; accepted `allowedOriginPatterns="*"` reflects matching origins and broadly permits credentialed cross-origin access. The finding distinguishes these cases.
- **Scope**: The advisor preserves source mapping order and the owning security chain. Provably shadowed mappings and mappings outside that chain are excluded; unsupported pattern overlap or custom resolution remains incomplete without invoking a matcher or CORS source.
- **Recommendation**: Correct rejected literal-wildcard configuration and restrict credentialed origin patterns to explicitly trusted origins. Whether a browser sends credentials also depends on its cookie/authentication rules.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html>

### SEC-CORS-003 - CORS should be wired through the security filter chain

- **Severity**: INFO
- **Detects**: Reviews attached CORS handling rather than unrelated source beans. Dynamic or MVC-managed handling, or differing attachments across chains, remains **SKIPPED** because intended origin scope and external handling are unknown; absence is not automatically a violation.
- **Recommendation**: Enable .cors(...) on the HttpSecurity so preflight handling is consistent with the security chain rather than MVC-only.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html>

> **Retired: SEC-CORS-004.** Spring supports reflecting requested methods/headers for an allowed origin.
> This does not independently bypass the origin trust boundary. A narrow allowlist can be useful design guidance,
> but it is not a separate credential exposure finding. The ID remains reserved.

### SEC-CORS-006 - CORS should not allow broad origin patterns

- **Severity**: MEDIUM (HIGH when any broad pattern has allowCredentials=true)
- **Detects**: Supported attached origin patterns have broad host scope beyond wildcard cases covered by SEC-CORS-001/002. A scheme wildcard alone does not broaden the trusted host set. The check does not guess public-suffix ownership or penalize every scoped subdomain wildcard. Credentialed broad-host cases raise severity to HIGH; unsupported policies remain unknown.
- **Recommendation**: Replace broad patterns with the exact origins (or tightly-scoped subdomain wildcards such as https://*.example.com) the application trusts; broad patterns combined with credentials let untrusted sites make authenticated cross-site calls.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html>

## Method security

### SEC-METHOD-001 - Method security annotations require method security to be enabled

- **Severity**: HIGH
- **Detects**: Bounded class, interface, superclass, and meta-annotation metadata uses a method-security family without matching recognized activation. Pre/post, `@Secured`, and JSR-250 families are tracked separately; default `@EnableMethodSecurity` does not activate all three. Custom infrastructure and unreadable metadata remain unknown.
- **Recommendation**: Enable the matching `@EnableMethodSecurity` family using `prePostEnabled`, `securedEnabled`, or `jsr250Enabled`. Verify proxy boundaries and invocation behavior separately.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html>

### SEC-METHOD-002 - Replace @EnableGlobalMethodSecurity with @EnableMethodSecurity

- **Severity**: LOW
- **Detects**: Detects the legacy @EnableGlobalMethodSecurity configuration, deprecated since Spring Security 6 and still present in Spring Security 7.
- **Recommendation**: Migrate to @EnableMethodSecurity, which enables @PreAuthorize/@PostAuthorize by default and uses the modern AuthorizationManager API.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html>

## Actuator & endpoint exposure

These checks combine **host** selection/access intent with already-instantiated native MVC operation metadata.
BootUI-contributed Actuator defaults are excluded. Scalar and indexed/list settings respect source precedence;
the default web include is `health`, and exclusions (including `*`) win. Both `heapdump` and `shutdown` default to
access `NONE`; endpoint/default access and `max-permitted` caps determine which reads and writes are available.
Invalid settings or native access/enabled conflicts produce analysis errors; unsupported or bounded observations
remain incomplete. Selection does not establish endpoint existence, network reachability, or anonymous access.
Exact native operation methods and paths are used where supported; custom mappings, variable paths, and separate
management-context authorization remain unknown. No endpoint discoverer, operation, matcher, or handler is called.

### SEC-ACT-001 - Actuator endpoints should not all be web-exposed

- **Severity**: HIGH
- **Detects**: Wildcard host web selection includes observed sensitive Actuator operations after exclusions and access limits. An excluded, unavailable, or absent operation does not establish exposure. Authentication and network restrictions are separate from selection.
- **Recommendation**: Expose only the endpoints you need (e.g. health, info) and secure the rest behind authentication.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing>

### SEC-ACT-002 - Sensitive actuator endpoints should not be exposed

- **Severity**: HIGH
- **Detects**: Explicit host selection includes observed sensitive operations after exclusions and access limits. Wildcard selection belongs to SEC-ACT-001, avoiding a second penalty for the same selection. Uninstantiated or unsupported operation inventories remain unknown rather than inferred from endpoint IDs.
- **Recommendation**: Remove sensitive endpoints from management.endpoints.web.exposure.include (or add them to management.endpoints.web.exposure.exclude) so they are not reachable, or protect them with authentication.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing>

### SEC-ACT-003 - Exposed actuator endpoints should be protected by a security chain

- **Severity**: MEDIUM
- **Detects**: An exact observed, selected operation beyond health/info has a supported unconditional grant in its first matching chain. A protected `/env` or base-path sample cannot stand in for other operations such as `/prometheus`; operation HTTP methods matter. Earlier unsupported chains or mappings block conclusions. No matching chain, custom authorization, and separate management contexts remain unknown rather than proving anonymous access.
- **Recommendation**: Require authentication/authorization for the actuator base path -- either inside the chain that matches it (e.g. requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole("ADMIN")) or through a dedicated SecurityFilterChain with a securityMatcher for that path.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.security>

### SEC-ACT-004 - Actuator health details/components should not be exposed unconditionally

- **Severity**: LOW
- **Detects**: A selected, observed health operation has host `show-details=always` or `show-components=always`. This includes details for callers allowed to reach the operation; neither setting bypasses authorization or proves anonymous disclosure. `show-components` can inherit `show-details`; default detail disclosure is `never`.
- **Recommendation**: Leave show-details/show-components at 'never' (the default), or set them to 'when-authorized' and require authentication for the health endpoint.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health.show-details>

### SEC-ACT-005 - The actuator shutdown endpoint should not be enabled

- **Severity**: HIGH
- **Detects**: An observed shutdown write operation is selected by effective host web exposure and unrestricted access. The default is `NONE`, and a read-only cap removes writes. A property alone does not establish an available or anonymously reachable shutdown operation.
- **Recommendation**: Keep the shutdown endpoint disabled (the default); if you truly need it, restrict it to a secured management port behind strict authentication.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.enabling>

### SEC-ACT-006 - Sensitive actuator endpoints should use an isolated management port

- **Severity**: INFO
- **Detects**: Selected observed management operations beyond health/info share the application listener. An unset or equal management port is shared; `-1` disables management HTTP; an explicit random port (`0`) is separate. Separate port configuration alone does not establish a private interface, network policy, or authorization.
- **Recommendation**: Consider a separate listener together with explicit network restrictions and authorization; a different port alone is not an isolation guarantee.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/monitoring.html#actuator.monitoring.customizing-management-server-port>

### SEC-ACT-007 - Actuator env/configprops values must stay sanitized

- **Severity**: HIGH
- **Detects**: A selected observed `env` or `configprops` operation has host `show-values=always`. This may disclose values to callers allowed to reach it; it does not bypass authorization or establish what custom sanitizers return. Disabled, unavailable, and unobserved operations do not produce a disclosure claim.
- **Recommendation**: The default is `never`. Use `when-authorized` only with appropriate authorized roles, and review endpoint access independently.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.sanitization>

## OAuth2 / JWT resource server

### SEC-OAUTH-001 - Resource server must validate tokens via JWT issuer/JWK or opaque-token introspection

- **Severity**: HIGH
- **Detects**: Recognizes JWT decoders and opaque-token introspectors attached to supported active providers, including inline configuration. Missing global beans or properties do not establish missing validation. Custom resolvers, unsupported managers, and unreadable attachment remain **SKIPPED**, without executing token validation or network callbacks.
- **Recommendation**: Configure spring.security.oauth2.resourceserver.jwt.issuer-uri (or jwk-set-uri / a JwtDecoder bean) for JWT resource servers, or spring.security.oauth2.resourceserver.opaquetoken.introspection-uri (or a custom OpaqueTokenIntrospector bean) for opaque-token resource servers, so incoming bearer tokens are actually verified.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html>

### SEC-OAUTH-002 - Validate the JWT audience claim

- **Severity**: INFO
- **Detects**: An observed Boot-managed JWT decoder lacks explicit audience configuration. Boot 4 supports `spring.security.oauth2.resourceserver.jwt.audiences`, including indexed/list binding. Active native factory provenance is required. Custom decoders and unrelated validator beans neither prove nor disprove audience validation; unsupported provenance remains unknown.
- **Recommendation**: For Boot-managed validation configure `spring.security.oauth2.resourceserver.jwt.audiences`. For a custom decoder attach the audience validator directly and verify its behavior separately.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-validation>

### SEC-OAUTH-003 - Review static verification-key rotation

- **Severity**: INFO
- **Detects**: A public-key location is configured without issuer/JWK metadata. This is an operational rotation reminder, not proof of weak validation or unrotatable keys; custom decoder behavior is not inferred.
- **Recommendation**: Document replacement and rollover for the configured trust anchor. Static keys can rotate out of band; remote JWKS is optional.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html>

### SEC-OAUTH-004 - JWT issuer and JWK endpoints should use HTTPS

- **Severity**: HIGH
- **Detects**: Detects `spring.security.oauth2.resourceserver.jwt.issuer-uri` or `jwk-set-uri` using plain HTTP. Discovery metadata or signing keys fetched without transport authentication can be modified by an active network attacker.
- **Recommendation**: Use HTTPS issuer and JWK endpoints with certificate validation enabled; reserve HTTP endpoints for isolated test environments.
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc8414.html#section-3.3>

## Configuration hygiene

### SEC-CONFIG-001 - Spring Security debug mode should be off

- **Severity**: MEDIUM
- **Detects**: Detects Spring Security's top-level `DebugFilter`, which is installed by `@EnableWebSecurity(debug = true)` and logs filter chains and request details. `spring.security.debug` is not a Spring Boot property and is not treated as a signal.
- **Recommendation**: Disable security debug mode outside local development; it leaks configuration and request information.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/configuration/java.html>

### SEC-CONFIG-002 - H2 console should not be enabled in production

- **Severity**: HIGH
- **Detects**: Reviews explicit `spring.h2.console.enabled=true` in production. This is host configuration intent, not proof that an H2 console endpoint exists or is anonymously reachable; runtime console registration is not inspected.
- **Recommendation**: Disable the H2 console in production (keep it to dev profiles) so frame-options are not loosened and the database UI is not reachable.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.h2-web-console>

### SEC-CONFIG-005 - Error responses should not leak stack traces or internal messages

- **Severity**: MEDIUM
- **Detects**: Boot 4 `spring.web.error.include-stacktrace`, `include-message`, or `include-binding-errors` is `always` or caller-enabled `on-param`/`on_param`. The obsolete `server.error.*` namespace is not used. Custom error handlers and actual response bodies are outside the observation.
- **Configuration evidence**: MVC security checks copy bounded native property-source maps and supported value types without invoking custom sources or value conversion. An opaque higher-priority source blocks lower fallback; unsupported backing maps and placeholder expansion remain unknown. Incomplete configuration does not discard independent known filter/provider findings.
- **Recommendation**: Use `never` for sensitive error details. `on-param` is caller-controlled, not a confidentiality boundary.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.error-handling>

### SEC-CONFIG-006 - Application should enforce HTTPS in production

- **Severity**: LOW
- **Detects**: Reviews production deployments without complete direct TLS or supported chain-local redirect evidence. Another chain's redirect and forwarding settings do not prove global protection. Basic/form transport findings suppress this duplicate generic review; external TLS remains unverified.
- **Recommendation**: Enforce HTTPS at the server or trusted edge. Verify edge policy separately and configure trusted forwarding only after establishing that policy.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.embedded-container.configure-ssl>

### SEC-CONFIG-007 - Configuration should not hold literal secret values

- **Severity**: HIGH
- **Detects**: A bounded, known packaged classpath configuration source contains a literal string under a credential-shaped terminal key such as password, secret, token, api-key, client-secret, or private-key. Lifetime/shape suffixes are excluded. Higher-priority known sources shadow lower literals; unknown provenance cannot establish hardcoding. Arbitrary external, Vault/config-server, remote, dynamic, and config-tree sources are not enumerated to retrieve values. Unresolved references are not findings. Only the key name is reported, never its value; incomplete source coverage remains unknown rather than a complete pass.
- **Recommendation**: Move the literal value out of the configuration file into an environment variable, a secrets manager, or a mounted config-tree secret, and reference it with ${ENV_VAR_NAME} instead of a hardcoded literal.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/external-config.html>

### SEC-CONFIG-008 - StrictHttpFirewall should not relax its default URL protections

- **Severity**: HIGH
- **Detects**: The actual `FilterChainProxy` uses a supported `StrictHttpFirewall` whose blocklist has been relaxed for recognized default URL tokens. An unused firewall bean does not establish active policy. The finding requests compatibility review rather than proving a downstream matcher bypass.
- **Recommendation**: Keep the StrictHttpFirewall defaults; only relax a specific token (e.g. setAllowUrlEncodedSlash(true)) after verifying every downstream matcher and handler safely tolerates it.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/firewall.html>

### SEC-CONFIG-009 - Spring Security framework logging should not run at DEBUG/TRACE in production

- **Severity**: MEDIUM
- **Detects**: Configured security logger levels are DEBUG/TRACE in production, including more-specific child overrides and root fallback. An INFO parent does not hide a DEBUG child, and a specific quiet override is respected. Logger names are discovered only from bounded known configuration maps; programmatic logging changes are not observed. Dedicated `DebugFilter` behavior remains SEC-CONFIG-001.
- **Recommendation**: Keep org.springframework.security logging at INFO or WARN in production; reserve DEBUG/TRACE for local troubleshooting.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.log-levels>

---

## WebFlux (reactive) rules

The Security advisor also supports Spring WebFlux (reactive) applications. On the reactive stack the Spring adapter
reads already-instantiated application `SecurityWebFilterChain` singletons, excluding BootUI's own permit-all chain,
and inspects supported native filter, header-writer, attached CORS, and configuration metadata. It does not create
lazy, prototype, or factory beans, enumerate unused decoder/client beans as proof of effective authentication, or
subscribe to a custom chain's filter publisher. It maps those observations into framework-neutral records before
the shared engine evaluates the rules. Availability is gated on at least one application
`SecurityWebFilterChain` bean.

The reactive checks share the same severity scale as the servlet checks. Rule IDs start with
`SEC-RXF-` to distinguish them from the servlet rules. They are passive review prompts, not authorization verdicts:
Spring Security exposes installed `WebFilter`s but not the decisions inside an `AuthorizationWebFilter`, decoder-local
JWT validators, custom management-path policy, reverse-proxy TLS policy, handler-level CORS, or custom filter behavior.
Known framework types are recognized by their actual classes, not coincidental simple names. Inventories and policy
text are bounded; unsupported custom implementations and reached limits remain incomplete. No custom matcher,
authorization manager, converter, decoder, repository, header writer, CORS source, or external property-source callback
is invoked to infer behavior. Dependent rules skip instead of treating unknown evidence as absence, while independently
known findings survive. Incomplete checks make the report `PARTIAL`; only genuine failures enter `analysisErrors`,
not skipped checks.
FactoryBean product types are read only from declarative metadata; even an already-created factory's `getObjectType()`
is not called.

Configuration is captured afresh for each scan using the shared passive Spring snapshot. Native Boot application-info,
command-line, origin-aware system-environment, config-data, and BootUI override sources retain precedence without
invoking application source or value callbacks. Native read-only map wrappers are accepted only after their backing
map is verified; opaque backing maps remain barriers. Random-value sources block only the `random.` namespace and
are never sampled. Unsupported individual values block their own keys rather than unrelated configuration; unknown
source inventories still make affected checks incomplete.

The current 25-rule catalog removes two duplicate authorization-filter absence checks and adds a structurally
supported chain-ordering advisory. Previously retired `SEC-RXF-OAUTH2-001` (unrelated validator-bean inference) and
`SEC-RXF-CONFIG-001` (unsupported `spring.security.debug` property) remain reserved; neither is revived.
The advisor distinguishes actual reactive form/OIDC login from OAuth client grant machinery, Basic credentials
from header-only bearer credentials, and observed framework defaults from custom/unknown behavior.

### SEC-RXF-AUTHZ-001 - Every reactive filter chain should enforce authorization

- **Severity**: HIGH
- **Detects**: A chain whose filters were successfully observed installs no `AuthorizationWebFilter`.
- **Recommendation**: Configure `authorizeExchange(...)` on every chain that handles application traffic; verify custom filters separately.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/authorization/authorize-http-requests.html>

> **Retired: SEC-RXF-AUTHZ-002.** Its missing `AuthorizationWebFilter` evidence is already covered by
> `SEC-RXF-AUTHZ-001`. A catch-all qualifier did not establish an independent issue; the ID remains reserved.

`anyExchange().permitAll()` still installs an `AuthorizationWebFilter`; runtime filter inspection therefore does not
claim to distinguish `permitAll`, `authenticated`, role-based, or custom authorization decisions.

> **Retired: SEC-RXF-AUTHZ-003.** A second application-wide penalty duplicated the missing-authorization
> findings already emitted per chain. Anonymous authentication filters also do not establish real login.
> The ID remains reserved.

### SEC-RXF-AUTHZ-004 - Place unconditional reactive chains after scoped chains

- **Severity**: INFO
- **Detects**: A structurally known unconditional chain precedes another application chain in known first-match order.
- **Recommendation**: Put scoped chains before the unconditional fallback and review their declared order. An unconditional matcher does not imply permissive authorization.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/authorization/authorize-http-requests.html>

The supported proof is Spring's `anyExchange()` matcher or a method-unconstrained `/**` path matcher. A method-qualified
`GET /**`, rendered matcher text, custom matcher, or composite/negated matcher does not establish unconditional scope.
Custom dynamic ordering remains inconclusive; no matcher or `Ordered.getOrder()` callback is executed.

### SEC-RXF-CSRF-001 - Reactive OAuth2/OIDC or formLogin() chains should enable CSRF protection

- **Severity**: HIGH
- **Detects**: A fully observed chain with an actual OAuth2 login filter (including Spring's OIDC session-registry variant) or Spring's form-login authentication converter has no `CsrfWebFilter`. OAuth2 authorization-code client grants alone are not login.
- **Recommendation**: Keep CSRF enabled for browser login chains; configure the appropriate reactive token repository.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/csrf.html>

### SEC-RXF-CSRF-002 - Review CSRF protection for reactive HTTP Basic chains

- **Severity**: MEDIUM
- **Detects**: A fully observed HTTP Basic chain has no `CsrfWebFilter`, excluding browser-login chains already covered by `SEC-RXF-CSRF-001`. A different chain's CSRF filter does not protect this chain.
- **Recommendation**: Keep CSRF protection for browser-accessible Basic authentication, or establish that clients cannot automatically attach credentials. Statelessness alone is not a defense; bearer-only APIs are not flagged.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/csrf.html>

### SEC-RXF-CORS-001 - CORS should not allow wildcard origins in reactive applications

- **Severity**: LOW
- **Detects**: An inspected source attached to an installed `CorsWebFilter` uses exact `*` origins or origin patterns. Legal credentialed wildcard reflection is reported only by `SEC-RXF-CORS-002`; a rejected literal wildcard-plus-credentials configuration receives a configuration review here instead.
- **Recommendation**: Public, noncredentialed resources may intentionally permit all origins. Otherwise enumerate trusted origins; do not interpret Spring's rejection of literal wildcard credentials as working credentialed sharing.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webflux-cors.html>

### SEC-RXF-CORS-002 - Credentialed reactive CORS must not trust every origin pattern

- **Severity**: HIGH
- **Detects**: An inspected installed CORS source uses legal `allowedOriginPatterns="*"` plus `allowCredentials=true`, without a literal wildcard origin that Spring would reject first. This reflects arbitrary origins while allowing credentials.
- **Recommendation**: Specify explicit trusted origins when using `allowCredentials(true)`.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webflux-cors.html>

Spring separately rejects `allowedOrigins="*"` with credentials; that invalid combination remains covered by
`SEC-RXF-CORS-001`, not misreported as a live credentialed wildcard policy.

### SEC-RXF-CORS-003 - Reactive CORS should not allow broad origin patterns

- **Severity**: LOW (HIGH when any broad pattern has allowCredentials=true)
- **Detects**: An installed source has broad host patterns such as `https://*`, `*://*`, or a single-label wildcard suffix. Configurations with exact wildcard origins/patterns are left to `SEC-RXF-CORS-001`/`SEC-RXF-CORS-002` to avoid duplicate findings.
- **Recommendation**: Review intentional public sharing without credentials; restrict credentialed sharing to trusted origins. A wildcard scheme with an exact host (`*://app.example.com`) is not arbitrary-host trust. Scoped subdomains are not classified using a guessed public-suffix or ownership list.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webflux-cors.html>

### SEC-RXF-HEAD-001 - HSTS header should be configured for reactive applications over TLS

- **Severity**: MEDIUM
- **Detects**: Direct server TLS or an unconditional HTTPS redirect in this chain is configured, and its fully inspected header mechanism has no HSTS writer. Forwarded-header handling and a redirect in another chain do not establish this chain's TLS posture.
- **Recommendation**: Keep Spring Security's `StrictTransportSecurityServerHttpHeadersWriter` defaults. Static configuration is not proof of headers delivered by a proxy or of browser receipt.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-hsts>

### SEC-RXF-HEAD-002 - X-Frame-Options header should be set in reactive chains

- **Severity**: MEDIUM
- **Detects**: A fully inspected header configuration lacks effective framing protection. A known enforcing `frame-ancestors` directive overrides X-Frame-Options, so `frame-ancestors *` is flagged even alongside `X-Frame-Options: DENY`. X-Frame-Options remains an alternative only when the enforcing directive is known absent; report-only framing does not override it.
- **Recommendation**: For browser documents, keep `XFrameOptionsServerHttpHeadersWriter` or enforce a restrictive framing policy. Supported single-header static equivalents are recognized; custom/conditional writers or ambiguous same-header composition remain inconclusive.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-frame-options>

### SEC-RXF-HEAD-003 - X-Content-Type-Options should be set in reactive chains

- **Severity**: LOW
- **Detects**: A chain has a fully inspected Spring Security header mechanism without a content-type-options writer or supported static `X-Content-Type-Options: nosniff` equivalent.
- **Recommendation**: Keep `ContentTypeOptionsServerHttpHeadersWriter`. Unknown/custom writer effects do not prove that delivered responses lack `nosniff`.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-content-type-options>

### SEC-RXF-HEAD-004 - Review Content-Security-Policy enforcement for reactive browser chains

- **Severity**: LOW
- **Detects**: A fully inspected Spring Security header configuration has no CSP or only a report-only policy. Spring's unconfigured native CSP writer emits no header and does not establish a policy. An enforcing policy alongside report-only monitoring is valid in either writer order.
- **Recommendation**: For browser-facing responses, define a tailored enforcing CSP; keep report-only mode bounded to rollout/monitoring.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-csp>

Spring Security intentionally supplies no CSP default because a reasonable policy depends on application context, so
this is low-severity browser hardening advice rather than a framework-misconfiguration verdict.
The shared parser analyzes one bounded policy only. Callers independently establish enforcement and scope; multiple
same-header writers, comma-separated policies, oversized policies, unreadable fields, and custom writers remain
incomplete rather than becoming a scalar last-writer policy or a guessed union.

### SEC-RXF-HEAD-005 - Security headers should not be disabled in reactive chains

- **Severity**: MEDIUM
- **Detects**: A fully observed chain contains recognized credential-authentication or authorization filters but no `HttpHeaderWriterWebFilter`. Anonymous identity alone does not count as credential authentication.
- **Recommendation**: Keep Spring Security's default header mechanism or verify equivalent custom/proxy protections. This is a framework-configuration observation, not proof that delivered responses have no security headers.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html>

### SEC-RXF-HEAD-006 - Review HSTS max-age values below Spring Security's one-year default

- **Severity**: LOW
- **Detects**: A recognized HSTS writer has an observed max-age below Spring Security's one-year default (31,536,000 seconds). An unreadable max-age is inconclusive, not a passing default.
- **Recommendation**: Distinguish `max-age=0`, which removes the browser's policy, from a shorter intentional rollout. RFC 6797 mandates no universal one-year minimum; `includeSubDomains` is a separate deployment decision.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-hsts>

### SEC-RXF-ACT-001 - Actuator endpoints should not be exposed with a wildcard

- **Severity**: HIGH
- **Detects**: Wildcard web selection leaves at least one sensitive endpoint configuration permitted after host exclusions, endpoint access, default access, and maximum-permitted caps.
- **Recommendation**: Select only needed endpoints and verify their actual availability, operations, and authorization. Configuration selection is not proof of callable or anonymous endpoints.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing>

All five reactive Actuator rules consume the focused shared Spring security observation. It binds bounded scalar/list
and indexed selection, defaults to health, gives `exclude=*` precedence, and accounts for disabled management HTTP and
access caps. Both heapdump and shutdown default to access `NONE`; shutdown requires write access, so a read-only cap
does not expose its operation. BootUI's contributed defaults are not host choices. Invalid/conflicting configuration
is reported as a failure; unsupported evidence remains incomplete.

### SEC-RXF-ACT-002 - Sensitive Actuator endpoints should be explicitly reviewed

- **Severity**: MEDIUM
- **Detects**: Effective selection permits sensitive endpoint configuration after exclusions and access rules, excluding wildcard selection already reported by `SEC-RXF-ACT-001`.
- **Recommendation**: Verify actual endpoint availability and protect management access through authorization and network restrictions.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.security>

### SEC-RXF-ACT-003 - Review authorization for broad reactive Actuator exposure

- **Severity**: MEDIUM
- **Detects**: Effective Actuator selection goes beyond health/info while every fully observed application chain omits `AuthorizationWebFilter`, without a separate management listener.
- **Recommendation**: Verify the actual management path/port has explicit authorization or a restricted network path.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.security>

This rule does not establish exact management-path authorization. A separate management context is explicitly
inconclusive rather than assumed to inherit application-chain policy.

### SEC-RXF-ACT-004 - Consider isolating Actuator endpoints on a separate management port

- **Severity**: INFO
- **Detects**: Effective selection goes beyond health/info without a separate enabled management listener. An explicitly equal fixed application/management port is still shared; `-1` disables management HTTP rather than isolating it.
- **Recommendation**: Consider a separate listener with explicit network binding and access restrictions. A different or dynamically allocated port alone is not a firewall.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/monitoring.html#actuator.monitoring.customizing-management-server-port>

### SEC-RXF-ACT-005 - Reactive Actuator env/configprops values must stay sanitized

- **Severity**: HIGH
- **Detects**: Effective configuration permits `env` or `configprops` web selection and the host explicitly sets the corresponding `management.endpoint.<id>.show-values=always`. This permits unsanitized values for endpoint callers; it does not establish anonymous reachability.
- **Recommendation**: Use `never` unless callers should see values. For authorized disclosure use `when-authorized` with appropriate roles, and verify endpoint authorization separately.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.sanitization>

### SEC-RXF-OAUTH2-002 - Review rotation for reactive JWT static public keys

- **Severity**: INFO
- **Detects**: A supported `spring.security.oauth2.resourceserver.jwt.public-key-location` declaration without an issuer or JWKS declaration taking precedence. Configuration does not prove a custom decoder's effective settings.
- **Recommendation**: Document out-of-band rotation for this valid static trust anchor. Remote JWKS is optional, not a requirement for secure verification.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html>

### SEC-RXF-OAUTH2-003 - JWT issuer URI and JWKS URI should use HTTPS in reactive applications

- **Severity**: HIGH
- **Detects**: A production profile declares a plain-HTTP JWT issuer or JWKS URI. Leading/trailing whitespace is ignored; only property names are reported, never URLs, and custom decoder behavior is not inferred.
- **Recommendation**: Use HTTPS to prevent token metadata from being intercepted or tampered.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html>

### SEC-RXF-OAUTH2-004 - Opaque-token introspection must use HTTPS in reactive production applications

- **Severity**: HIGH
- **Detects**: A production profile declares a plain-HTTP opaque-token introspection URI. No introspection occurs; evidence contains the property name, not its value, and does not establish custom introspector settings.
- **Recommendation**: Use HTTPS and validate the authorization server certificate.
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc7662.html#section-4>

### SEC-RXF-CONFIG-002 - Review HTTPS enforcement for reactive production applications

- **Severity**: MEDIUM
- **Detects**: Observed production chains with known absence of direct server TLS and no supported unconditional chain-local HTTPS redirect. Explicit `server.ssl.enabled=false` wins over remaining key material; unresolved TLS placeholders remain incomplete rather than becoming `false`.
- **Recommendation**: Confirm external ingress enforcement or configure direct TLS/appropriate chain redirects. Forwarded-header processing is not TLS enforcement, and another chain's redirect does not protect this chain.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/https.html>

### SEC-RXF-CONFIG-003 - Credentials or secrets should not be hardcoded in application properties

- **Severity**: HIGH
- **Detects**: Bounded credential-shaped keys with literal string values in supported local application-configuration sources. Metadata URLs, booleans/timeouts, placeholders, environment/system values, and opaque external providers are not evidence of hardcoding. Higher-precedence supported sources shadow local declarations; unknown provenance stays incomplete.
- **Recommendation**: Move secrets to environment variables, a secrets manager, Spring Cloud Vault, or another externalization mechanism.
- **Learn more**: <https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html>

### SEC-RXF-CONFIG-004 - Spring Security DEBUG or TRACE logging should not run in production

- **Severity**: MEDIUM
- **Detects**: A configured Spring Security logger or its inherited level is `DEBUG`/`TRACE` in a production profile. Explicit child overrides are evaluated: parent `INFO` does not hide child `TRACE`, and child `INFO` does not silence other descendants of a `TRACE` parent.
- **Recommendation**: Keep `org.springframework.security` logging at INFO or WARN in production.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/index.html>

### SEC-RXF-SESSION-001 - Review reactive chains that mix bearer-token and browser login filters

- **Severity**: LOW
- **Detects**: One chain combines Spring Security's actual bearer-token converter with an observed OAuth2 login or form-login filter. OAuth2 client-grant machinery alone is not browser login; unknown converters remain incomplete.
- **Recommendation**: Prefer separate ordered chains. For a pure bearer chain, use
  `securityContextRepository(NoOpServerSecurityContextRepository.getInstance())`; WebFlux has no
  `SessionCreationPolicy` API.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/authentication/index.html>

This is a topology review, not proof that authentication is persisted in a `WebSession`.

## Audit sources and limits

The accuracy review uses the project's exact framework baseline, not generic assumptions about a major version:

- [Spring Boot 4.1.1 dependency definition](https://github.com/spring-projects/spring-boot/blob/v4.1.1/platform/spring-boot-dependencies/build.gradle)
  selects Spring Security 7.1.1.
- [Boot JWT decoder configuration](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-security-oauth2-resource-server/src/main/java/org/springframework/boot/security/oauth2/server/resource/autoconfigure/JwtDecoderConfiguration.java)
  composes the supported audiences property with validators and backs off for custom decoders.
- [DAO provider defaults](https://github.com/spring-projects/spring-security/blob/7.1.1/core/src/main/java/org/springframework/security/authentication/dao/DaoAuthenticationProvider.java)
  and [session management](https://github.com/spring-projects/spring-security/blob/7.1.1/docs/modules/ROOT/pages/servlet/authentication/session-management.adoc)
  define effective password/session defaults independently of incidental bean/filter presence.
- [Reactive HTTP security](https://github.com/spring-projects/spring-security/blob/7.1.1/config/src/main/java/org/springframework/security/config/web/server/ServerHttpSecurity.java)
  defines Basic, form/OIDC login, context repositories and filter installation.
- [Actuator access resolution](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/endpoint/PropertiesEndpointAccessResolver.java)
  and [include/exclude defaults](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/endpoint/expose/IncludeExcludeEndpointFilter.java)
  distinguish endpoint availability, allowed operations and HTTP exposure.
- [Fetch CORS protocol](https://fetch.spec.whatwg.org/#http-cors-protocol) distinguishes literal wildcard response
  headers from credentialed exact-origin reflection. [CSP Level 3](https://www.w3.org/TR/CSP3/) defines first
  duplicate directive, fallback, nonce/hash, strict-dynamic and multiple-policy semantics.
- [OWASP CSRF](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html),
  [HTTP headers](https://cheatsheetseries.owasp.org/cheatsheets/HTTP_Headers_Cheat_Sheet.html) and
  [secrets management](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html)
  supply context-specific guidance rather than requirements to restate framework/browser defaults.
- [RFC 6797](https://www.rfc-editor.org/rfc/rfc6797), [RFC 8725](https://www.rfc-editor.org/rfc/rfc8725),
  [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700) and [RFC 7662](https://www.rfc-editor.org/rfc/rfc7662)
  cover HSTS, JWT audience restrictions, OAuth security and introspection transport.

These are metadata advisors, not policy interpreters or browser probes. Unsupported custom behavior, dynamic
configuration and ambiguous writer composition stay inconclusive. Retired IDs are permanently reserved.
