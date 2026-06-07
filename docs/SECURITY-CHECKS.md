# Spring Security Advisor checks

The Security panel runs a fixed, on-demand ruleset against the host application's Spring Security configuration.
It introspects the registered `SecurityFilterChain` beans and their filter lists, simulates an anonymous authorization
decision, and inspects security-relevant beans (`PasswordEncoder`, `CorsConfigurationSource`, `JwtDecoder`) and
`Environment` properties. It never intercepts live traffic, exposes credentials, keys, or session identifiers, or modifies
the security configuration.

The checks are heuristic review prompts. They highlight common Spring Security hardening gaps, but the right remediation
still depends on the application's threat model and deployment topology.

Actuator exposure checks ignore BootUI's own low-priority local actuator defaults. Those defaults are merged into
Spring Boot's shared `defaultProperties` source (only for keys the host has not set) so local panels can read Actuator
data, and host `management.*` settings always win. If the host application explicitly configures actuator exposure
or health detail properties, the checks continue to evaluate those values.

## Availability and bounds

The panel is available only when Spring Security is on the classpath and at least one `SecurityFilterChain` bean exists.
If Spring Security is absent or no filter chains are registered, BootUI returns a stable empty report with an explanatory
status. Configuration that cannot be read (for example via reflection) is skipped gracefully and reported as a partial scan
rather than failing the panel.

## Severity scale

- **HIGH** - a configuration that commonly leaves the application exposed and usually needs attention before production.
- **MEDIUM** - a hardening gap that warrants review.
- **LOW** - lower-impact hygiene findings.
- **INFO** - informational prompts where the right fix depends heavily on project context.

The Rule results panel lists only checks that found findings, ordered by severity, finding count, and rule id. Each rule
includes up to a handful of sample details plus a remediation link.

---

## Authentication & passwords

### SEC-AUTH-001 - Password encoder must not store credentials in plain text

- **Severity**: HIGH
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

### SEC-AUTH-006 - BCrypt password encoder should use an adequate work factor

- **Severity**: LOW
- **Detects**: Detects a BCryptPasswordEncoder bean configured with a strength below the recommended minimum of 10 (the framework default).
- **Recommendation**: Use a BCrypt strength of at least 10 (the default) so password hashing stays computationally expensive; raise it as hardware improves, or migrate to Argon2/PBKDF2.
- **Learn more**: <https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html>

## Authorization

### SEC-AUTHZ-001 - Every filter chain should enforce authorization

- **Severity**: HIGH
- **Detects**: Detects a SecurityFilterChain that installs no AuthorizationFilter, so matched requests are unguarded.
- **Recommendation**: Add authorizeHttpRequests(...) with at least anyRequest().authenticated() (or an explicit denyAll) to the chain.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html>

### SEC-AUTHZ-002 - Avoid blanket permitAll authorization

- **Severity**: HIGH
- **Detects**: Detects a chain whose authorization grants every request to anonymous callers (permitAll catch-all).
- **Recommendation**: Restrict sensitive paths and finish with anyRequest().authenticated(); keep permitAll only for genuinely public endpoints.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html>

### SEC-AUTHZ-003 - Application security should not be effectively disabled

- **Severity**: HIGH
- **Detects**: Detects when every filter chain permits all requests anonymously and no chain requires authentication.
- **Recommendation**: Define authorization rules that require authentication for non-public endpoints instead of leaving the app fully open.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html>

### SEC-AUTHZ-004 - Catch-all filter chains should be ordered last

- **Severity**: INFO
- **Detects**: Detects a chain that matches any request placed before more specific chains, which then never run.
- **Recommendation**: Give earlier chains an explicit securityMatcher and keep the catch-all (any request) chain last by @Order.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/configuration/java.html#_multiple_httpsecurity_instances>

## CSRF

### SEC-CSRF-001 - CSRF protection should stay on for session-based chains

- **Severity**: HIGH
- **Detects**: Detects a stateful (session/remember-me) chain with no CsrfFilter installed.
- **Recommendation**: Keep CSRF enabled for browser, cookie, or session authenticated chains; only disable it for stateless token APIs.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html>

### SEC-CSRF-002 - CSRF should not be disabled without stateless authentication

- **Severity**: MEDIUM
- **Detects**: Detects chains with no CsrfFilter and no bearer-token (stateless) authentication to justify the removal.
- **Recommendation**: Disable CSRF only when the chain is stateless (e.g. bearer tokens); otherwise keep the CsrfFilter.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html>

## Session management

### SEC-SESSION-001 - Session fixation protection should be enabled

- **Severity**: HIGH
- **Detects**: Detects a session-management strategy configured to skip changing the session id on authentication.
- **Recommendation**: Use the default changeSessionId (or migrateSession) session-fixation strategy instead of none().
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html>

### SEC-SESSION-002 - Session cookie should set the Secure flag

- **Severity**: MEDIUM
- **Detects**: Detects server.servlet.session.cookie.secure=false, or unset while a production profile is active.
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
- **Detects**: Detects that server.servlet.session.timeout is unset, leaving the container's default timeout.
- **Recommendation**: Set server.servlet.session.timeout to a bounded value appropriate for the application's risk profile.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SEC-SESSION-006 - Bearer token authentication chains should be stateless

- **Severity**: HIGH
- **Detects**: Detects a chain with both a Bearer token filter (stateless) and session management filters (stateful).
- **Recommendation**: Configure sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS) to avoid creating HTTP sessions for REST API calls.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-stateless>

### SEC-SESSION-007 - Consider configuring concurrent session control

- **Severity**: INFO
- **Detects**: Detects an interactive form-login chain that maintains sessions but installs no ConcurrentSessionFilter (no maximumSessions limit).
- **Recommendation**: Set sessionManagement().maximumSessions(n) so a stolen or shared credential cannot open unlimited concurrent sessions.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html#ns-concurrent-sessions>

## Transport & security headers

### SEC-HEAD-001 - HTTP Strict Transport Security should be emitted

- **Severity**: MEDIUM
- **Detects**: Detects chains whose header writers do not include an HSTS writer.
- **Recommendation**: Keep the default HstsHeaderWriter (served over HTTPS) so browsers pin TLS for the domain.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-hsts>

### SEC-HEAD-002 - X-Frame-Options (clickjacking protection) should stay enabled

- **Severity**: HIGH
- **Detects**: Detects chains whose header writers omit X-Frame-Options / frame-ancestors protection.
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

## CORS

### SEC-CORS-001 - CORS should not allow all origins

- **Severity**: HIGH
- **Detects**: Detects a CorsConfiguration that allows the * wildcard origin.
- **Recommendation**: Enumerate the exact trusted origins instead of "*"; use allowedOriginPatterns only for tightly-scoped patterns.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html>

### SEC-CORS-002 - CORS must not combine wildcard origins with credentials

- **Severity**: HIGH
- **Detects**: Detects a CorsConfiguration that allows the * origin together with allowCredentials=true.
- **Recommendation**: Never pair allowCredentials(true) with a wildcard origin; list explicit origins so cookies and auth headers are not leaked cross-site.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html>

### SEC-CORS-003 - CORS should be wired through the security filter chain

- **Severity**: INFO
- **Detects**: Detects a CorsConfigurationSource bean while no filter chain installs a CorsFilter.
- **Recommendation**: Enable .cors(...) on the HttpSecurity so preflight handling is consistent with the security chain rather than MVC-only.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html>

### SEC-CORS-004 - CORS should not allow all methods or headers with credentials

- **Severity**: MEDIUM
- **Detects**: Detects a CorsConfiguration that allows the * wildcard for methods or headers together with allowCredentials=true.
- **Recommendation**: Enumerate the exact methods and headers the API needs instead of "*" when credentials are allowed, so cross-site callers cannot send arbitrary authenticated requests.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html>

## Method security

### SEC-METHOD-001 - Method security annotations require method security to be enabled

- **Severity**: HIGH
- **Detects**: Detects @PreAuthorize/@Secured usage while no method-security interceptors are registered, so the annotations are ignored.
- **Recommendation**: Add @EnableMethodSecurity to a configuration class so the security annotations are actually enforced.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html>

### SEC-METHOD-002 - Replace @EnableGlobalMethodSecurity with @EnableMethodSecurity

- **Severity**: LOW
- **Detects**: Detects the legacy @EnableGlobalMethodSecurity configuration removed/deprecated in Spring Security 6+.
- **Recommendation**: Migrate to @EnableMethodSecurity, which enables @PreAuthorize/@PostAuthorize by default and uses the modern AuthorizationManager API.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html>

## Actuator & endpoint exposure

### SEC-ACT-001 - Actuator endpoints should not all be web-exposed

- **Severity**: HIGH
- **Detects**: Detects management.endpoints.web.exposure.include=* exposing every actuator endpoint over HTTP.
- **Recommendation**: Expose only the endpoints you need (e.g. health, info) and secure the rest behind authentication.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing>

### SEC-ACT-002 - Sensitive actuator endpoints should not be exposed

- **Severity**: HIGH
- **Detects**: Detects high-value actuator endpoints (env, beans, configprops, heapdump, threaddump, shutdown) in the web exposure list.
- **Recommendation**: Remove sensitive endpoints from management.endpoints.web.exposure.include or protect them with authentication.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing>

### SEC-ACT-003 - Exposed actuator endpoints should be protected by a security chain

- **Severity**: MEDIUM
- **Detects**: Detects web-exposed actuator endpoints (beyond health/info) when no filter chain references /actuator.
- **Recommendation**: Add a SecurityFilterChain with a securityMatcher for the actuator base path that requires authentication/authorization.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.security>

### SEC-ACT-004 - Actuator health details should not be exposed unconditionally

- **Severity**: HIGH
- **Detects**: Detects management.endpoint.health.show-details=always, which leaks infrastructure details to anonymous callers.
- **Recommendation**: Change management.endpoint.health.show-details to 'when-authorized' (the default) or ensure the /health endpoint is strictly authenticated.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health.show-details>

### SEC-ACT-005 - The actuator shutdown endpoint should not be enabled

- **Severity**: HIGH
- **Detects**: Detects management.endpoint.shutdown.enabled=true, which lets a caller stop the application (denial of service) if reachable.
- **Recommendation**: Keep the shutdown endpoint disabled (the default); if you truly need it, restrict it to a secured management port behind strict authentication.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.enabling>

### SEC-ACT-006 - Sensitive actuator endpoints should use an isolated management port

- **Severity**: INFO
- **Detects**: Notes that sensitive actuator endpoints are exposed on the main application port because management.server.port is unset.
- **Recommendation**: Set management.server.port to a separate, network-restricted port so actuator endpoints are not reachable on the public application port.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/monitoring.html#actuator.monitoring.customizing-management-server-port>

## OAuth2 / JWT resource server

### SEC-OAUTH-001 - JWT resource server must validate tokens via issuer or JWK set

- **Severity**: HIGH
- **Detects**: Detects a bearer-token resource server with no issuer-uri, jwk-set-uri, public key, or JwtDecoder bean configured.
- **Recommendation**: Configure spring.security.oauth2.resourceserver.jwt.issuer-uri (or jwk-set-uri / a JwtDecoder bean) so signatures and the issuer are verified.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html>

### SEC-OAUTH-002 - Validate the JWT audience claim

- **Severity**: MEDIUM
- **Detects**: Notes that issuer-based resource servers do not validate the aud claim unless a custom validator is added.
- **Recommendation**: Add an audience OAuth2TokenValidator to the JwtDecoder so tokens minted for other resource servers are rejected.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-validation>

### SEC-OAUTH-003 - Prefer JWK rotation over a static signing key

- **Severity**: MEDIUM
- **Detects**: Detects a resource server pinned to a static public key (public-key-location) with no issuer or JWK set URI.
- **Recommendation**: Use a jwk-set-uri / issuer-uri so signing keys can rotate, rather than a single embedded public key.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html>

## Configuration hygiene

### SEC-CONFIG-001 - Spring Security debug mode should be off

- **Severity**: MEDIUM
- **Detects**: Detects spring.security.debug=true (or a DebugFilter), which logs filter chains and request details.
- **Recommendation**: Disable security debug mode outside local development; it leaks configuration and request information.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/configuration/java.html>

### SEC-CONFIG-002 - H2 console should not be enabled in production

- **Severity**: HIGH
- **Detects**: Detects spring.h2.console.enabled=true while a production profile is active; the console needs frame options relaxed and exposes the database.
- **Recommendation**: Disable the H2 console in production (keep it to dev profiles) so frame-options are not loosened and the database UI is not reachable.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.h2-web-console>

### SEC-CONFIG-003 - Replace WebSecurityConfigurerAdapter with a component-based configuration

- **Severity**: LOW
- **Detects**: Detects a WebSecurityConfigurerAdapter bean; the class was removed in Spring Security 6.
- **Recommendation**: Expose SecurityFilterChain and WebSecurityCustomizer beans instead of extending WebSecurityConfigurerAdapter.
- **Learn more**: <https://spring.io/blog/2022/02/21/spring-security-without-the-websecurityconfigureradapter>

### SEC-CONFIG-004 - Avoid bypassing the filter chain with web.ignoring()

- **Severity**: MEDIUM
- **Detects**: Notes that web.ignoring() paths skip Spring Security entirely (including header writers) and cannot be introspected from registered chains.
- **Recommendation**: Prefer permitAll() inside authorizeHttpRequests for non-static paths so security headers and context still apply; reserve web.ignoring() for truly static resources.
- **Learn more**: <https://docs.spring.io/spring-security/reference/servlet/configuration/java.html#jc-httpsecurity>

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
