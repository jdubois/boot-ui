# Spring Security Advisor checks

The Security panel runs a fixed, on-demand ruleset against the host application's Spring Security configuration.
It introspects the registered `SecurityFilterChain` beans and their filter lists, simulates a bounded set of anonymous
authorization decisions across common paths and HTTP methods, and inspects security-relevant beans (`PasswordEncoder`,
`CorsConfigurationSource`, `JwtDecoder`) and `Environment` properties. It never intercepts live traffic, exposes
credentials, keys, or session identifiers, or modifies the security configuration.

The checks are heuristic review prompts. They highlight common Spring Security hardening gaps, but the right remediation
still depends on the application's threat model and deployment topology.

Actuator exposure checks ignore BootUI's own low-priority local actuator defaults. Those defaults are merged into
Spring Boot's shared `defaultProperties` source (only for keys the host has not set) so local panels can read Actuator
data, and host `management.*` settings always win. If the host application explicitly configures actuator exposure
or health detail properties, the checks continue to evaluate those values.

## Availability and bounds

The panel is available only when Spring Security is on the classpath and at least one application
`SecurityFilterChain` (servlet) or `SecurityWebFilterChain` (WebFlux) bean exists.
If Spring Security is absent or no filter chains are registered, BootUI returns a stable empty report with an explanatory
status. Configuration that cannot be read (for example via reflection) is skipped gracefully and reported as a partial scan
rather than failing the panel.

## Severity scale

- **CRITICAL** - a configuration that directly exposes credentials, secrets, or critical security controls and needs immediate attention.
- **HIGH** - a configuration that commonly leaves the application exposed and usually needs attention before production.
- **MEDIUM** - a hardening gap that warrants review.
- **LOW** - lower-impact hygiene findings.
- **INFO** - informational prompts where the right fix depends heavily on project context.

The Rule results panel lists only checks that found findings, ordered by severity, finding count, and rule id. Each rule
includes up to a handful of sample details plus a remediation link.

---

## Authentication & passwords

### SEC-AUTH-001 - Password encoder must not store credentials in plain text

- **Severity**: CRITICAL
- **Detects**: Detects a NoOpPasswordEncoder bean, which keeps passwords in clear text.
- **Recommendation**: Use a delegating encoder (PasswordEncoderFactories.createDelegatingPasswordEncoder()) backed by bcrypt, Argon2, or PBKDF2.
- **Learn more**: <https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html>

### SEC-AUTH-002 - Password encoder should not use a weak or legacy algorithm

- **Severity**: HIGH
- **Detects**: Detects deprecated encoders based on MD5/SHA or the legacy StandardPasswordEncoder.
- **Recommendation**: Migrate to bcrypt, Argon2, or PBKDF2 via a DelegatingPasswordEncoder so hashes upgrade over time.
- **Learn more**: <https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html>

### SEC-AUTH-003 - Form or HTTP Basic login should define a PasswordEncoder

- **Severity**: MEDIUM
- **Detects**: Detects form-login or HTTP Basic chains with no PasswordEncoder bean exposed to the context.
- **Recommendation**: Declare a PasswordEncoder bean (a delegating encoder) so stored credentials are hashed and verified consistently.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/index.html>

### SEC-AUTH-006 - BCrypt password encoder should use an adequate work factor

- **Severity**: LOW
- **Detects**: Detects a BCryptPasswordEncoder bean configured with a strength below the recommended minimum of 10 (the framework default).
- **Recommendation**: Use a BCrypt strength of at least 10 (the default) so password hashing stays computationally expensive; raise it as hardware improves, or migrate to Argon2/PBKDF2.
- **Learn more**: <https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html>

### SEC-AUTH-004 - Do not rely on the generated spring.security.user account

- **Severity**: MEDIUM
- **Detects**: Detects credentials configured through spring.security.user.name / spring.security.user.password.
- **Recommendation**: Replace the single property-based user with a real UserDetailsService or identity provider for anything beyond local demos.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/spring-security.html>

### SEC-AUTH-005 - Avoid the auto-generated login page in production

- **Severity**: LOW
- **Detects**: Detects the framework's DefaultLoginPageGeneratingFilter while a production profile is active.
- **Recommendation**: Provide a custom login page via formLogin().loginPage(...) for production so the unstyled default page (which advertises the Spring Security stack) is not served.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html>

### SEC-AUTH-007 - HTTP Basic authentication should run only over HTTPS

- **Severity**: HIGH
- **Detects**: Detects an HTTP Basic authentication chain (BasicAuthenticationFilter) while a production profile is active and no server-side TLS, HTTPS redirect, or forwarded-header strategy is configured. Basic sends the username/password Base64-encoded, not encrypted, on every request.
- **Recommendation**: Enforce HTTPS via server.ssl.* (or a forwarded-headers strategy when TLS terminates upstream) for any chain using httpBasic(), or switch to a mechanism that does not repeat credentials on every request.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/basic.html>

### SEC-AUTH-008 - hideUserNotFoundExceptions should stay enabled

- **Severity**: MEDIUM
- **Detects**: Detects an AbstractUserDetailsAuthenticationProvider (e.g. DaoAuthenticationProvider) with hideUserNotFoundExceptions explicitly set to false, which lets a login failure distinguish an unknown username from a wrong password -- a username-enumeration oracle.
- **Recommendation**: Leave hideUserNotFoundExceptions at its default (true) so a failed login always reports the same generic BadCredentialsException regardless of whether the username exists.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/dao-authentication-provider.html>

### SEC-AUTH-009 - Do not run production on Spring Boot's auto-generated default user

- **Severity**: HIGH
- **Detects**: Detects Spring Boot's auto-configured InMemoryUserDetailsManager (created only when no UserDetailsService/AuthenticationManager/AuthenticationProvider bean and no spring.security.user.* property are present) while a production profile is active. Unlike SEC-AUTH-004 (an explicitly-configured static user), this is the fully-default case: Spring Boot generates a random password for a single 'user' account and logs it to the console at startup, which Spring Boot's own documentation warns is for development use only.
- **Recommendation**: Register a real UserDetailsService, AuthenticationProvider, or external identity provider before running in production; do not rely on the console-logged generated password.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/spring-security.html>

### SEC-AUTH-010 - Form login should run only over HTTPS

- **Severity**: HIGH
- **Detects**: Detects an interactive form-login chain while a production-like profile is active and no server-side TLS, HTTPS redirect, or forwarded-header strategy is configured. Passwords submitted over HTTP are visible to passive network observers and active intermediaries.
- **Recommendation**: Enforce HTTPS via server.ssl.* (or a forwarded-headers strategy when TLS terminates upstream) for every production formLogin() chain.
- **Learn more**: <https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#transmit-passwords-only-over-tls-or-other-strong-transport>

## Authorization

### SEC-AUTHZ-001 - Every filter chain should enforce authorization

- **Severity**: HIGH
- **Detects**: Detects a SecurityFilterChain that installs no AuthorizationFilter, so matched requests are unguarded.
- **Recommendation**: Add authorizeHttpRequests(...) with at least anyRequest().authenticated() (or an explicit denyAll) to the chain.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html>

### SEC-AUTHZ-002 - Avoid blanket permitAll authorization

- **Severity**: HIGH
- **Detects**: Detects a catch-all chain whose authorization grants every request in a bounded anonymous probe set spanning common application paths, an unmatched sentinel path, and common HTTP methods. This is a heuristic, not a proof over the application's entire request space.
- **Recommendation**: Restrict sensitive paths and finish with anyRequest().authenticated(); keep permitAll only for genuinely public endpoints.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html>

### SEC-AUTHZ-003 - Application security should not be effectively disabled

- **Severity**: HIGH
- **Detects**: Detects when every determinable filter chain grants the bounded anonymous probe set and no chain installs a real authentication mechanism. Chains whose authorization manager cannot be evaluated remain indeterminate.
- **Recommendation**: Define authorization rules that require authentication for non-public endpoints instead of leaving the app fully open.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html>

### SEC-AUTHZ-004 - Catch-all filter chains should be ordered last

- **Severity**: INFO
- **Detects**: Detects a chain that matches any request placed before more specific chains, which then never run.
- **Recommendation**: Give earlier chains an explicit securityMatcher and keep the catch-all (any request) chain last by @Order.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/configuration/java.html#_multiple_httpsecurity_instances>

### SEC-AUTHZ-005 - Broader authorizeHttpRequests matchers should not shadow narrower ones

- **Severity**: HIGH
- **Detects**: Detects an unconditional, method-agnostic catch-all matcher (e.g. requestMatchers("/**")) registered before a narrower matcher in the same chain's authorizeHttpRequests rules. Requests are matched in declaration order and Spring Security does not guard against this (unlike anyRequest(), a plain requestMatchers("/**") does not block further rules from being added after it), so the narrower, later rule can never take effect. The rule renders **SKIPPED** when BootUI cannot unwrap or introspect the authorization manager.
- **Recommendation**: Register narrower matchers (e.g. requestMatchers("/admin/**").hasRole("ADMIN")) before the broader catch-all, or replace the catch-all with anyRequest() so later requestMatchers additions are rejected at startup instead of silently ignored.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html>

## CSRF

### SEC-CSRF-001 - CSRF protection should stay on for session-based chains

- **Severity**: HIGH
- **Detects**: Detects a stateful (session/remember-me) chain with no CsrfFilter installed.
- **Recommendation**: Keep CSRF enabled for browser, cookie, or session authenticated chains; only disable it for stateless token APIs.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html>

### SEC-CSRF-002 - CSRF protection should stay on for HTTP Basic authentication

- **Severity**: MEDIUM
- **Detects**: Detects a stateless HTTP Basic chain with no CsrfFilter. Statelessness alone does not remove CSRF risk when a browser automatically resends the username and password on cross-site requests.
- **Recommendation**: Keep CSRF protection enabled for browser-reachable HTTP Basic endpoints, or use bearer credentials that browsers do not attach automatically.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html>

## Session management

### SEC-SESSION-001 - Session fixation protection should be enabled

- **Severity**: HIGH
- **Detects**: Detects a session-management strategy configured to skip changing the session id on authentication.
- **Recommendation**: Use the default changeSessionId (or migrateSession) session-fixation strategy instead of none().
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html>

### SEC-SESSION-002 - Session cookie should set the Secure flag

- **Severity**: MEDIUM
- **Detects**: Detects server.servlet.session.cookie.secure=false, or unset while a production profile is active and at least one filter chain actually uses HTTP sessions. An explicit false remains a finding even when current chains are stateless because it is an unsafe host-level setting.
- **Recommendation**: Set server.servlet.session.cookie.secure=true so the session cookie is only sent over HTTPS.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SEC-SESSION-003 - Session cookie should set the HttpOnly flag

- **Severity**: MEDIUM
- **Detects**: Detects server.servlet.session.cookie.http-only=false, exposing the session cookie to JavaScript.
- **Recommendation**: Keep server.servlet.session.cookie.http-only=true to mitigate cookie theft via XSS.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SEC-SESSION-004 - Session cookie should declare a SameSite policy

- **Severity**: LOW
- **Detects**: Detects that server.servlet.session.cookie.same-site is unset, leaving the policy to the container default.
- **Recommendation**: Set server.servlet.session.cookie.same-site=Lax (or Strict) to reduce cross-site request exposure.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SEC-SESSION-005 - An explicit session timeout should be configured

- **Severity**: INFO
- **Detects**: Detects that server.servlet.session.timeout is unset, which uses Spring Boot's documented 30-minute default.
- **Recommendation**: Confirm that 30 minutes is a suitable bound for the application's risk profile, or set server.servlet.session.timeout explicitly.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SEC-SESSION-006 - Bearer-token authentication chains should be stateless

- **Severity**: HIGH
- **Detects**: Detects a chain with both a bearer-token filter (stateless) and session management filters (stateful).
- **Recommendation**: Configure sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS) to avoid creating HTTP sessions for REST API calls.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-stateless>

### SEC-SESSION-007 - Consider configuring concurrent session control

- **Severity**: INFO
- **Detects**: Detects an interactive form-login chain that maintains sessions but installs no ConcurrentSessionFilter (no maximumSessions limit).
- **Recommendation**: Set sessionManagement().maximumSessions(n) so a stolen or shared credential cannot open unlimited concurrent sessions.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html#ns-concurrent-sessions>

### SEC-SESSION-008 - Remember-me signing key should be sufficiently long

- **Severity**: MEDIUM
- **Detects**: Detects a RememberMeAuthenticationFilter whose signing key is shorter than 16 characters, which makes the remember-me token's HMAC signature easier to brute-force and forge. Only the key's length is inspected -- the key value itself is never read into a finding.
- **Recommendation**: Configure a long, random remember-me key (16+ characters, generated from a secure source) via rememberMe().key(...), ideally sourced from an externalized secret rather than a literal in configuration.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authentication/rememberme.html>

### SEC-SESSION-009 - Custom session cookie names should use a __Host-/__Secure- prefix

- **Severity**: LOW
- **Detects**: Detects server.servlet.session.cookie.name configured to a custom value that does not start with the __Host- or __Secure- cookie-name prefix (exact case -- browsers only honor these prefixes verbatim). The unmodified default name, JSESSIONID, is not flagged; this rule only fires once an application has already chosen to customize the cookie name.
- **Recommendation**: Name the session cookie with the __Host- prefix, e.g. __Host-SESSION (requires Secure, no Domain attribute, and Path=/) or, at minimum, the __Secure- prefix, e.g. __Secure-SESSION, so the browser rejects the cookie unless it was set over HTTPS -- hardening against cookie-tossing from a sibling or subdomain.
- **Learn more**: <https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html#cookie-name-prefixes>

## Transport & security headers

### SEC-HEAD-001 - HTTP Strict Transport Security should be emitted

- **Severity**: MEDIUM
- **Detects**: Detects chains whose header writers do not include an HSTS writer.
- **Recommendation**: Keep the default HstsHeaderWriter (served over HTTPS) so browsers pin TLS for the domain.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-hsts>

### SEC-HEAD-002 - X-Frame-Options (clickjacking protection) should stay enabled

- **Severity**: HIGH
- **Detects**: Detects chains whose header writers omit X-Frame-Options and whose enforcing Content-Security-Policy does not contain a frame-ancestors directive. An unrelated CSP writer, or a report-only CSP, does not provide clickjacking protection.
- **Recommendation**: Keep the default XFrameOptionsHeaderWriter (DENY/SAMEORIGIN) or a frame-ancestors CSP instead of disabling frame options globally.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-frame-options>

### SEC-HEAD-003 - A Content-Security-Policy should be defined

- **Severity**: LOW
- **Detects**: Detects chains that serve no Content-Security-Policy header.
- **Recommendation**: Add a ContentSecurityPolicyHeaderWriter with a tailored policy to mitigate XSS and data injection.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-csp>

### SEC-HEAD-004 - X-Content-Type-Options should stay enabled

- **Severity**: LOW
- **Detects**: Detects chains whose header writers omit X-Content-Type-Options: nosniff.
- **Recommendation**: Keep the default XContentTypeOptionsHeaderWriter so browsers do not MIME-sniff responses.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-content-type-options>

### SEC-HEAD-005 - A Referrer-Policy header should be emitted

- **Severity**: LOW
- **Detects**: Detects chains whose header writers do not emit a Referrer-Policy header (not sent by default).
- **Recommendation**: Add a ReferrerPolicyHeaderWriter via headers().referrerPolicy(...) with a policy such as strict-origin-when-cross-origin to limit referrer leakage.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-referrer>

### SEC-HEAD-006 - A Permissions-Policy header should be considered

- **Severity**: INFO
- **Detects**: Detects chains whose header writers do not emit a Permissions-Policy header (not sent by default).
- **Recommendation**: Add a PermissionsPolicyHeaderWriter via headers().permissionsPolicyHeader(...) to restrict powerful browser features the application does not use.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-permissions-policy>

### SEC-HEAD-007 - Security response headers should not be globally disabled

- **Severity**: HIGH
- **Detects**: Detects a browser-facing (authenticated or session) chain that installs no HeaderWriterFilter, which means headers().disable() removed every security header (HSTS, X-Frame-Options, X-Content-Type-Options, ...).
- **Recommendation**: Remove headers().disable(); keep the default HeaderWriterFilter so security headers are emitted, and only tune individual writers you do not need.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html>

### SEC-HEAD-008 - HSTS should use a strong max-age and includeSubDomains

- **Severity**: LOW
- **Detects**: Detects an HstsHeaderWriter configured with a max-age under one year or without includeSubDomains, which weakens the protocol-downgrade and cookie-hijacking protection HSTS is meant to provide.
- **Recommendation**: Keep the default HstsHeaderWriter settings (max-age=31536000, includeSubDomains=true), or configure headers().httpStrictTransportSecurity() explicitly with those values.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-hsts>

### SEC-HEAD-009 - Content-Security-Policy should not allow unsafe-inline/unsafe-eval, unscoped wildcards, or omit key hardening directives

- **Severity**: MEDIUM
- **Detects**: Detects a ContentSecurityPolicyHeaderWriter policy that includes 'unsafe-inline' or 'unsafe-eval', an unscoped wildcard source (bare `*` or a scheme-only wildcard such as `https://*`, but not a scoped pattern like `https://*.example.com`), or that omits the base-uri/frame-ancestors directives or both object-src and default-src -- all of which weaken the XSS/clickjacking mitigation a CSP is meant to provide.
- **Recommendation**: Remove 'unsafe-inline'/'unsafe-eval' and unscoped wildcard sources (scope wildcards to a trusted domain, e.g. `https://*.example.com`); add `base-uri 'self'`, `frame-ancestors 'none'` (or an explicit allow-list), and an object-src (or default-src) directive.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-csp>

### SEC-HEAD-010 - Cross-origin isolation headers should be considered

- **Severity**: INFO
- **Detects**: Detects chains whose header writers emit neither a Cross-Origin-Opener-Policy nor a Cross-Origin-Embedder-Policy header (neither is sent by default).
- **Recommendation**: Add CrossOriginOpenerPolicyHeaderWriter / CrossOriginEmbedderPolicyHeaderWriter via headers().crossOriginOpenerPolicy(...) / .crossOriginEmbedderPolicy(...) if the application needs cross-origin isolation (e.g. for SharedArrayBuffer) or Spectre-style side-channel hardening.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html>

## CORS

A custom `CorsConfigurationSource` bean (one BootUI cannot introspect for `CorsConfiguration` objects) is indeterminate,
not automatically hardened: SEC-CORS-001, SEC-CORS-002, SEC-CORS-004, and SEC-CORS-006 render **SKIPPED** rather than a
silent pass whenever such a bean is present and no introspectable `CorsConfiguration` already produced a finding, so a
custom source is never misreported as verified-safe. Spring MVC's built-in `mvcHandlerMappingIntrospector` is framework
infrastructure rather than an application CORS policy and is excluded from this bean inventory.

### SEC-CORS-001 - CORS should not allow all origins

- **Severity**: HIGH
- **Detects**: Detects a CorsConfiguration that allows the * wildcard origin. Renders SKIPPED instead of a pass when a non-introspectable custom CorsConfigurationSource is present and no introspectable configuration already produced a finding.
- **Recommendation**: Enumerate the exact trusted origins instead of "*"; use allowedOriginPatterns only for tightly-scoped patterns.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html>

### SEC-CORS-002 - CORS must not combine wildcard origins with credentials

- **Severity**: HIGH
- **Detects**: Detects a CorsConfiguration that allows the * origin together with allowCredentials=true. Renders SKIPPED instead of a pass when a non-introspectable custom CorsConfigurationSource is present and no introspectable configuration already produced a finding.
- **Recommendation**: Never pair allowCredentials(true) with a wildcard origin; list explicit origins so cookies and auth headers are not leaked cross-site.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html>

### SEC-CORS-003 - CORS should be wired through the security filter chain

- **Severity**: INFO
- **Detects**: Detects an application CorsConfigurationSource bean while no filter chain installs Spring's `CorsFilter` or `PreFlightRequestFilter`.
- **Recommendation**: Enable .cors(...) on the HttpSecurity so preflight handling is consistent with the security chain rather than MVC-only.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html>

### SEC-CORS-004 - CORS should not allow all methods or headers with credentials

- **Severity**: MEDIUM
- **Detects**: Detects a CorsConfiguration that allows the * wildcard for methods or headers together with allowCredentials=true. Renders SKIPPED instead of a pass when a non-introspectable custom CorsConfigurationSource is present and no introspectable configuration already produced a finding.
- **Recommendation**: Enumerate the exact methods and headers the API needs instead of "*" when credentials are allowed, so cross-site callers cannot send arbitrary authenticated requests.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html>

### SEC-CORS-006 - CORS should not allow broad origin patterns

- **Severity**: MEDIUM (HIGH when any broad pattern has allowCredentials=true)
- **Detects**: Detects allowedOriginPatterns that match a dangerously broad set of origins (wildcard scheme or host, e.g. https://*, *://*, *.com) beyond the exact "*" already covered by SEC-CORS-001/002. Renders SKIPPED instead of a pass when a non-introspectable custom CorsConfigurationSource is present and no introspectable configuration already produced a finding.
- **Recommendation**: Replace broad patterns with the exact origins (or tightly-scoped subdomain wildcards such as https://*.example.com) the application trusts; broad patterns combined with credentials let untrusted sites make authenticated cross-site calls.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html>

## Method security

### SEC-METHOD-001 - Method security annotations require method security to be enabled

- **Severity**: HIGH
- **Detects**: Detects @PreAuthorize/@Secured usage while no method-security interceptors are registered, so the annotations are ignored.
- **Recommendation**: Add @EnableMethodSecurity to a configuration class so the security annotations are actually enforced.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html>

### SEC-METHOD-002 - Replace @EnableGlobalMethodSecurity with @EnableMethodSecurity

- **Severity**: LOW
- **Detects**: Detects the legacy @EnableGlobalMethodSecurity configuration, deprecated since Spring Security 6 and still present in Spring Security 7.
- **Recommendation**: Migrate to @EnableMethodSecurity, which enables @PreAuthorize/@PostAuthorize by default and uses the modern AuthorizationManager API.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html>

## Actuator & endpoint exposure

### SEC-ACT-001 - Actuator endpoints should not all be web-exposed

- **Severity**: HIGH
- **Detects**: Detects management.endpoints.web.exposure.include=* exposing actuator endpoints over HTTP beyond health/info, after subtracting management.endpoints.web.exposure.exclude. A wildcard include fully hardened by excluding every sensitive endpoint is not flagged.
- **Recommendation**: Expose only the endpoints you need (e.g. health, info) and secure the rest behind authentication.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing>

### SEC-ACT-002 - Sensitive actuator endpoints should not be exposed

- **Severity**: HIGH
- **Detects**: Detects high-value actuator endpoints (env, beans, configprops, heapdump, threaddump, shutdown, loggers, mappings) that remain web-exposed once management.endpoints.web.exposure.exclude is subtracted from the include list.
- **Recommendation**: Remove sensitive endpoints from management.endpoints.web.exposure.include (or add them to management.endpoints.web.exposure.exclude) so they are not reachable, or protect them with authentication.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing>

### SEC-ACT-003 - Exposed actuator endpoints should be protected by a security chain

- **Severity**: MEDIUM
- **Detects**: Detects web-exposed actuator endpoints (beyond health/info, after subtracting management.endpoints.web.exposure.exclude) when no filter chain references /actuator.
- **Recommendation**: Add a SecurityFilterChain with a securityMatcher for the actuator base path that requires authentication/authorization.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.security>

### SEC-ACT-004 - Actuator health details/components should not be exposed unconditionally

- **Severity**: HIGH
- **Detects**: Detects management.endpoint.health.show-details=always or show-components=always, either of which leaks infrastructure/component details (disk space, database, custom health indicators, dependency versions) to anonymous callers. Spring Boot's default for both properties is 'never'.
- **Recommendation**: Leave show-details/show-components at 'never' (the default), or set them to 'when-authorized' and require authentication for the health endpoint.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health.show-details>

### SEC-ACT-005 - The actuator shutdown endpoint should not be enabled

- **Severity**: HIGH
- **Detects**: Detects management.endpoint.shutdown.enabled=true, which lets a caller stop the application (denial of service) if reachable.
- **Recommendation**: Keep the shutdown endpoint disabled (the default); if you truly need it, restrict it to a secured management port behind strict authentication.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.enabling>

### SEC-ACT-006 - Sensitive actuator endpoints should use an isolated management port

- **Severity**: INFO
- **Detects**: Notes that sensitive actuator endpoints (beyond health/info, after subtracting management.endpoints.web.exposure.exclude) are exposed on the main application port because management.server.port is unset.
- **Recommendation**: Set management.server.port to a separate, network-restricted port so actuator endpoints are not reachable on the public application port.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/monitoring.html#actuator.monitoring.customizing-management-server-port>

### SEC-ACT-007 - Actuator env/configprops values must stay sanitized

- **Severity**: HIGH
- **Detects**: Detects management.endpoint.env.show-values=always or management.endpoint.configprops.show-values=always, which reveals unsanitized property values (including secrets) to callers of /env and /configprops.
- **Recommendation**: Leave show-values at 'never' or 'when-authorized' (the defaults) so the actuator sanitizer masks sensitive values; only relax it behind strict authorization.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.sanitization>

## OAuth2 / JWT resource server

### SEC-OAUTH-001 - Resource server must validate tokens via JWT issuer/JWK or opaque-token introspection

- **Severity**: HIGH
- **Detects**: Detects a bearer-token resource server with neither JWT validation (issuer-uri, jwk-set-uri, public key, or a JwtDecoder bean) nor opaque-token validation (introspection-uri or an OpaqueTokenIntrospector bean) configured. BearerTokenAuthenticationFilter is installed identically for oauth2ResourceServer().jwt(...) and .opaqueToken(...), so both validation styles are accepted.
- **Recommendation**: Configure spring.security.oauth2.resourceserver.jwt.issuer-uri (or jwk-set-uri / a JwtDecoder bean) for JWT resource servers, or spring.security.oauth2.resourceserver.opaquetoken.introspection-uri (or a custom OpaqueTokenIntrospector bean) for opaque-token resource servers, so incoming bearer tokens are actually verified.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html>

### SEC-OAUTH-002 - Validate the JWT audience claim

- **Severity**: MEDIUM (INFO when a custom OAuth2TokenValidator or a custom JwtDecoder is present)
- **Detects**: Notes that issuer-based resource servers do not validate the aud claim unless a custom OAuth2TokenValidator bean (including one composed via DelegatingOAuth2TokenValidator) or a custom JwtDecoder is registered; either is reported as an INFO advisory because it may already validate the claim. The removed `spring.security.oauth2.resourceserver.jwt.audiences` property is deliberately ignored on Spring Security 7 and cannot suppress this finding.
- **Recommendation**: Add an audience OAuth2TokenValidator to the JwtDecoder so tokens minted for other resource servers are rejected.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-validation>

### SEC-OAUTH-003 - Prefer JWK rotation over a static signing key

- **Severity**: MEDIUM
- **Detects**: Detects a resource server pinned to a static public key (public-key-location) with no issuer or JWK set URI.
- **Recommendation**: Use a jwk-set-uri / issuer-uri so signing keys can rotate, rather than a single embedded public key.
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
- **Detects**: Detects spring.h2.console.enabled=true while a production profile is active; the console needs frame options relaxed and exposes the database.
- **Recommendation**: Disable the H2 console in production (keep it to dev profiles) so frame-options are not loosened and the database UI is not reachable.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.h2-web-console>

### SEC-CONFIG-005 - Error responses should not leak stack traces or internal messages

- **Severity**: MEDIUM
- **Detects**: Detects server.error.include-stacktrace / include-message / include-binding-errors set to 'always', which exposes internal details in error responses.
- **Recommendation**: Use 'never' (or 'on_param') for include-stacktrace and keep include-message / include-binding-errors at 'never' in production to avoid information disclosure.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.error-handling>

### SEC-CONFIG-006 - Application should enforce HTTPS in production

- **Severity**: LOW
- **Detects**: Notes that, while a production profile is active, the application configures no server-side TLS, HTTPS redirect (requiresChannel/ChannelProcessingFilter), or forwarded-header strategy indicating TLS is terminated upstream.
- **Recommendation**: Enforce HTTPS via server.ssl.* (or requiresChannel().requiresSecure()), or set server.forward-headers-strategy=framework when TLS is terminated by a proxy so secure cookies and redirects behave correctly.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.embedded-container.configure-ssl>

### SEC-CONFIG-007 - Configuration should not hold literal secret values

- **Severity**: HIGH
- **Detects**: Detects configuration property names that look like they hold a credential (password, secret, token, api-key, client-secret, private-key) whose value is a literal, unresolved string rather than an externalized reference. Keys ending in a lifetime/shape suffix (-expiration, -expiry, -expires, -ttl, -timeout, -duration, -validity, -max-age, -refresh-interval) are excluded because they configure how long a token lives, not its value (e.g. jwt.token.expiration=3600). System properties, the OS environment, the random-value source, and mounted config-tree secrets are not scanned because they are already externalized. Property values are never read into the finding; only the offending property name is reported. This remains a name-based heuristic, not a secret-shape check, so review each finding -- it may still name a non-secret value (e.g. oauth.token.type=Bearer).
- **Recommendation**: Move the literal value out of the configuration file into an environment variable, a secrets manager, or a mounted config-tree secret, and reference it with ${ENV_VAR_NAME} instead of a hardcoded literal.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/external-config.html>

### SEC-CONFIG-008 - StrictHttpFirewall should not relax its default URL protections

- **Severity**: HIGH
- **Detects**: Detects a custom StrictHttpFirewall bean that has re-allowed one or more normally-blocked encoded/raw URL tokens (URL-encoded slash, backslash, semicolon, or double slash), which can enable authorization-matcher bypass or path-traversal style attacks.
- **Recommendation**: Keep the StrictHttpFirewall defaults; only relax a specific token (e.g. setAllowUrlEncodedSlash(true)) after verifying every downstream matcher and handler safely tolerates it.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/firewall.html>

### SEC-CONFIG-009 - Spring Security framework logging should not run at DEBUG/TRACE in production

- **Severity**: MEDIUM
- **Detects**: Detects logging.level.org.springframework.security=DEBUG (or TRACE) while a production profile is active, which logs filter chain decisions, header values, and request/response details. Distinct from `@EnableWebSecurity(debug = true)`, which SEC-CONFIG-001 detects through Spring Security's dedicated debug filter.
- **Recommendation**: Keep org.springframework.security logging at INFO or WARN in production; reserve DEBUG/TRACE for local troubleshooting.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.log-levels>

---

## WebFlux (reactive) rules

The Security advisor also supports Spring WebFlux (reactive) applications. On the reactive stack the Spring adapter
reads the application's `SecurityWebFilterChain` beans directly, excluding BootUI's own permit-all chain, and inspects
security-relevant
reactive beans (`ReactiveJwtDecoder`, `ReactiveOpaqueTokenIntrospector`, `CorsConfigurationSource`)
and `Environment` properties. It maps those observations into framework-neutral records before the shared engine
evaluates the rules. Availability is gated on at least one application `SecurityWebFilterChain` bean.

The reactive checks share the same severity scale as the servlet checks. Rule IDs start with
`SEC-RXF-` to distinguish them from the servlet rules.

### SEC-RXF-AUTHZ-001 - Reactive chain has no AuthorizationWebFilter

- **Severity**: HIGH
- **Detects**: A `SecurityWebFilterChain` installs no `AuthorizationWebFilter`, meaning all requests pass through without authorization.
- **Recommendation**: Configure `authorizeExchange(...)` on every chain that handles application traffic.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/authorization/authorize-http-requests.html>

### SEC-RXF-AUTHZ-002 - Reactive permitAll catch-all while authentication is active

- **Severity**: HIGH
- **Detects**: A reactive chain grants all requests to anonymous callers (permitAll catch-all) while also configuring authentication filters.
- **Recommendation**: Restrict sensitive paths and finish with `anyExchange().authenticated()`.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/authorization/authorize-http-requests.html>

### SEC-RXF-AUTHZ-003 - Reactive security chain effectively disables authorization

- **Severity**: HIGH
- **Detects**: The chain appears to offer no effective authorization (no `AuthorizationWebFilter` and no matcher-scoped protection).
- **Recommendation**: Define authorization rules requiring authentication for non-public endpoints.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/authorization/authorize-http-requests.html>

### SEC-RXF-CSRF-001 - Stateful reactive chain missing CsrfWebFilter

- **Severity**: HIGH
- **Detects**: A chain with OAuth2 login or session-based authentication has no `CsrfWebFilter`.
- **Recommendation**: Add `.csrf(Customizer.withDefaults())` or a `CookieServerCsrfTokenRepository`.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/csrf.html>

### SEC-RXF-CSRF-002 - No reactive chain installs CsrfWebFilter

- **Severity**: MEDIUM
- **Detects**: No registered `SecurityWebFilterChain` installs a `CsrfWebFilter`.
- **Recommendation**: For non-stateless applications add `.csrf(Customizer.withDefaults())`.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/csrf.html>

### SEC-RXF-CORS-001 - Reactive CORS allows wildcard origin

- **Severity**: MEDIUM
- **Detects**: A reactive `CorsConfigurationSource` allows `*` as an origin.
- **Recommendation**: Restrict `allowedOrigins` to explicit, trusted domains.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/integrations/cors.html>

### SEC-RXF-CORS-002 - Reactive CORS allows wildcard origin with credentials

- **Severity**: HIGH
- **Detects**: `allowedOrigins=*` combined with `allowCredentials=true` — browsers refuse this combination, and allowing credentials with a broad origin is a CORS misconfiguration.
- **Recommendation**: Specify explicit origins when using `allowCredentials(true)`.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/integrations/cors.html>

### SEC-RXF-HEAD-001 - Reactive chain missing HSTS header writer

- **Severity**: MEDIUM
- **Detects**: A chain has an `HttpHeaderWriterWebFilter` but no HSTS writer.
- **Recommendation**: Add `HstsServerHttpHeadersWriter` via `.headers(h -> h.hsts(Customizer.withDefaults()))`.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html>

### SEC-RXF-HEAD-002 - Reactive chain missing X-Frame-Options header writer

- **Severity**: MEDIUM
- **Detects**: No frame-options writer is installed, leaving clickjacking protection absent.
- **Recommendation**: Add a `XFrameOptionsServerHttpHeadersWriter`.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html>

### SEC-RXF-HEAD-003 - Reactive chain missing X-Content-Type-Options header writer

- **Severity**: LOW
- **Detects**: No content-type-options writer is installed.
- **Recommendation**: Add a `ContentTypeOptionsServerHttpHeadersWriter`.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-content-type-options>

### SEC-RXF-HEAD-004 - Reactive chain has no Content-Security-Policy header writer

- **Severity**: MEDIUM
- **Detects**: No CSP writer is installed on any chain.
- **Recommendation**: Add a `ContentSecurityPolicyServerHttpHeadersWriter` appropriate to your app.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html>

### SEC-RXF-HEAD-005 - Reactive security headers entirely disabled

- **Severity**: HIGH
- **Detects**: Authentication/authorization filters are active but no `HttpHeaderWriterWebFilter` is present.
- **Recommendation**: Enable default headers via `.headers(Customizer.withDefaults())`.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html>

### SEC-RXF-HEAD-006 - Reactive HSTS max-age is too short

- **Severity**: LOW
- **Detects**: The HSTS `maxAgeInSeconds` is configured but below the recommended one-year minimum.
- **Recommendation**: Use at least 31536000 seconds (one year).
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html>

### SEC-RXF-ACT-001 - Actuator exposes all endpoints with wildcard include

- **Severity**: HIGH
- **Detects**: `management.endpoints.web.exposure.include=*` without any exclude, exposing all Actuator endpoints.
- **Recommendation**: List only needed endpoints or add excludes.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html>

### SEC-RXF-ACT-002 - Actuator exposes sensitive endpoints

- **Severity**: MEDIUM
- **Detects**: One or more sensitive endpoints (`env`, `beans`, `heapdump`, etc.) are explicitly included in `management.endpoints.web.exposure.include`.
- **Recommendation**: Restrict sensitive endpoints to a separate management port.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html>

### SEC-RXF-ACT-003 - Actuator endpoints exposed without protection

- **Severity**: HIGH
- **Detects**: Actuator exposes more than health/info AND every reactive chain is fully open.
- **Recommendation**: Protect Actuator endpoints with authentication or network policies.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html>

### SEC-RXF-ACT-004 - Sensitive Actuator endpoints on main server port

- **Severity**: INFO
- **Detects**: Sensitive Actuator endpoints are on the application's main port without a separate management port.
- **Recommendation**: Set `management.server.port` to isolate management traffic.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/monitoring.html>

### SEC-RXF-OAUTH2-001 - Reactive JWT decoder missing audience validation

- **Severity**: MEDIUM
- **Detects**: A `ReactiveJwtDecoder` bean exists but no `OAuth2TokenValidator` for the audience claim is registered.
- **Recommendation**: Configure a `JwtClaimValidator<List<String>>("aud", ...)` or `spring.security.oauth2.resourceserver.jwt.audiences`.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html>

### SEC-RXF-OAUTH2-002 - Reactive JWT decoder uses a static public key

- **Severity**: MEDIUM
- **Detects**: `spring.security.oauth2.resourceserver.jwt.public-key-location` pins JWT verification to a static public key, making signing-key rotation operationally fragile.
- **Recommendation**: Prefer `issuer-uri` or `jwk-set-uri` so signing-key rotation is handled through the authorization server's JWKS endpoint.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html>

### SEC-RXF-OAUTH2-003 - Reactive JWT metadata URL is plain HTTP

- **Severity**: HIGH
- **Detects**: The `spring.security.oauth2.resourceserver.jwt.issuer-uri` or JWK-set URI is `http://` rather than `https://`.
- **Recommendation**: Use HTTPS to prevent token metadata from being intercepted or tampered.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html>

### SEC-RXF-CONFIG-001 - spring.security.debug is enabled

- **Severity**: HIGH
- **Detects**: `spring.security.debug=true` is set, printing Spring Security decisions to stdout.
- **Recommendation**: Disable `spring.security.debug` in production.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/index.html>

### SEC-RXF-CONFIG-002 - HTTPS not enforced in production

- **Severity**: HIGH
- **Detects**: A production profile is active but no TLS configuration or `HttpsRedirectWebFilter` was found.
- **Recommendation**: Configure TLS (`server.ssl.*`) or `HttpsRedirectWebFilter` for production traffic.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/exploits/https.html>

### SEC-RXF-CONFIG-003 - Hardcoded credentials in application properties

- **Severity**: HIGH
- **Detects**: Property keys whose names suggest credentials or secrets hold literal string values rather than placeholder references.
- **Recommendation**: Move secrets to environment variables, a secrets manager, Spring Cloud Vault, or another externalization mechanism.
- **Learn more**: <https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html>

### SEC-RXF-CONFIG-004 - Spring Security DEBUG logging in production

- **Severity**: MEDIUM
- **Detects**: `logging.level.org.springframework.security=DEBUG` while a production profile is active.
- **Recommendation**: Keep `org.springframework.security` logging at INFO or WARN in production.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/index.html>

### SEC-RXF-SESSION-001 - Bearer-token resource server using stateful sessions

- **Severity**: LOW
- **Detects**: A chain whose `AuthenticationWebFilter` uses Spring Security's reactive bearer-token converter also configures OAuth2 login or session-based authentication (stateful), which is redundant for pure resource servers.
- **Recommendation**: Configure `.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))` for pure bearer-token resource server chains.
- **Learn more**: <https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html>
