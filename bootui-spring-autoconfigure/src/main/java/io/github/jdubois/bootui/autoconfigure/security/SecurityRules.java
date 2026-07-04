package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.FilterChainModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.PasswordEncoderModel;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

abstract class AbstractSecurityRule implements SecurityRule {

    private final SecurityRuleDefinition definition;

    AbstractSecurityRule(SecurityRuleDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final SecurityRuleDefinition definition() {
        return definition;
    }

    abstract SecurityRuleResultDto evaluateRule(SecurityContext context);

    @Override
    public final SecurityRuleResultDto evaluate(SecurityContext context) {
        try {
            return evaluateRule(context);
        } catch (RuntimeException | LinkageError ex) {
            return SecurityRuleSupport.error(definition, "Rule could not be evaluated: " + ex.getMessage());
        }
    }

    SecurityRuleResultDto pass() {
        return SecurityRuleSupport.pass(definition);
    }

    SecurityRuleResultDto skipped(String reason) {
        return SecurityRuleSupport.skipped(definition, reason);
    }

    SecurityRuleResultDto violation(List<String> details) {
        return details.isEmpty() ? pass() : SecurityRuleSupport.violation(definition, details);
    }

    SecurityRuleResultDto violation(String severityOverride, List<String> details) {
        return details.isEmpty() ? pass() : SecurityRuleSupport.violation(definition, severityOverride, details);
    }
}

// ---------------------------------------------------------------------------
// Authentication & passwords
// ---------------------------------------------------------------------------

final class NoOpPasswordEncoderRule extends AbstractSecurityRule {

    NoOpPasswordEncoderRule() {
        super(new SecurityRuleDefinition(
                "SEC-AUTH-001",
                "Password encoder must not store credentials in plain text",
                SecurityCategory.AUTHENTICATION,
                "CRITICAL",
                "Detects a NoOpPasswordEncoder bean, which keeps passwords in clear text.",
                "Use a delegating encoder (PasswordEncoderFactories.createDelegatingPasswordEncoder()) backed by bcrypt, Argon2, or PBKDF2.",
                "https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (String type : context.passwordEncoderTypes()) {
            if (type.contains("NoOpPasswordEncoder")) {
                details.add("PasswordEncoder bean " + type + " stores passwords without hashing.");
            }
        }
        return violation(details);
    }
}

final class WeakPasswordEncoderRule extends AbstractSecurityRule {

    private static final List<String> WEAK = List.of(
            "StandardPasswordEncoder",
            "MessageDigestPasswordEncoder",
            "Md4PasswordEncoder",
            "Md5",
            "ShaPasswordEncoder",
            "LdapShaPasswordEncoder");

    WeakPasswordEncoderRule() {
        super(new SecurityRuleDefinition(
                "SEC-AUTH-002",
                "Password encoder should not use a weak or legacy algorithm",
                SecurityCategory.AUTHENTICATION,
                "HIGH",
                "Detects deprecated encoders based on MD5/SHA or the legacy StandardPasswordEncoder.",
                "Migrate to bcrypt, Argon2, or PBKDF2 via a DelegatingPasswordEncoder so hashes upgrade over time.",
                "https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (String type : context.passwordEncoderTypes()) {
            if (type.contains("NoOpPasswordEncoder")) {
                continue;
            }
            for (String weak : WEAK) {
                if (type.contains(weak)) {
                    details.add("PasswordEncoder bean " + type + " uses a weak/legacy hashing algorithm.");
                    break;
                }
            }
        }
        return violation(details);
    }
}

final class MissingPasswordEncoderRule extends AbstractSecurityRule {

    MissingPasswordEncoderRule() {
        super(new SecurityRuleDefinition(
                "SEC-AUTH-003",
                "Form or HTTP Basic login should define a PasswordEncoder",
                SecurityCategory.AUTHENTICATION,
                "MEDIUM",
                "Detects form-login or HTTP Basic chains with no PasswordEncoder bean exposed to the context.",
                "Declare a PasswordEncoder bean (a delegating encoder) so stored credentials are hashed and verified consistently.",
                "https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/index.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.hasFormOrBasicChain()) {
            return pass();
        }
        if (!context.passwordEncoderTypes().isEmpty()) {
            return pass();
        }
        return violation(
                List.of("A form-login or HTTP Basic chain is configured but no PasswordEncoder bean was found."));
    }
}

final class DefaultInMemoryUserRule extends AbstractSecurityRule {

    DefaultInMemoryUserRule() {
        super(new SecurityRuleDefinition(
                "SEC-AUTH-004",
                "Do not rely on the generated spring.security.user account",
                SecurityCategory.AUTHENTICATION,
                "MEDIUM",
                "Detects credentials configured through spring.security.user.name / spring.security.user.password.",
                "Replace the single property-based user with a real UserDetailsService or identity provider for anything beyond local demos.",
                "https://docs.spring.io/spring-boot/reference/web/spring-security.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        String name = context.firstProperty("spring.security.user.name");
        String password = context.firstProperty("spring.security.user.password");
        if (name == null && password == null) {
            return pass();
        }
        return violation(
                List.of(
                        "spring.security.user.* defines a static in-memory account; not suitable for shared or production use."));
    }
}

final class DefaultLoginPageProductionRule extends AbstractSecurityRule {

    DefaultLoginPageProductionRule() {
        super(new SecurityRuleDefinition(
                "SEC-AUTH-005",
                "Avoid the auto-generated login page in production",
                SecurityCategory.AUTHENTICATION,
                "LOW",
                "Detects the framework's DefaultLoginPageGeneratingFilter while a production profile is active.",
                "Provide a custom login page via formLogin().loginPage(...) for production so the unstyled default page (which advertises the Spring Security stack) is not served.",
                "https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.hasFilter("DefaultLoginPageGeneratingFilter")) {
                details.add(chain.describe() + " serves the auto-generated Spring Security login page in production.");
            }
        }
        return violation(details);
    }
}

final class WeakBcryptStrengthRule extends AbstractSecurityRule {

    private static final int RECOMMENDED_MINIMUM_STRENGTH = 10;

    WeakBcryptStrengthRule() {
        super(new SecurityRuleDefinition(
                "SEC-AUTH-006",
                "BCrypt password encoder should use an adequate work factor",
                SecurityCategory.AUTHENTICATION,
                "LOW",
                "Detects a BCryptPasswordEncoder bean configured with a strength below the recommended minimum of 10 (the framework default).",
                "Use a BCrypt strength of at least 10 (the default) so password hashing stays computationally expensive; raise it as hardware improves, or migrate to Argon2/PBKDF2.",
                "https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (PasswordEncoderModel encoder : context.passwordEncoders()) {
            Integer strength = encoder.bcryptStrength();
            if (strength != null && strength >= 0 && strength < RECOMMENDED_MINIMUM_STRENGTH) {
                details.add("PasswordEncoder bean " + encoder.type() + " uses BCrypt strength " + strength
                        + ", below the recommended minimum of " + RECOMMENDED_MINIMUM_STRENGTH + ".");
            }
        }
        return violation(details);
    }
}

final class BasicAuthWithoutTlsRule extends AbstractSecurityRule {

    BasicAuthWithoutTlsRule() {
        super(new SecurityRuleDefinition(
                "SEC-AUTH-007",
                "HTTP Basic authentication should run only over HTTPS",
                SecurityCategory.AUTHENTICATION,
                "HIGH",
                "Detects an HTTP Basic authentication chain (BasicAuthenticationFilter) while a production profile is active and no server-side TLS, HTTPS redirect, or forwarded-header strategy is configured. Basic sends the username/password Base64-encoded, not encrypted, on every request.",
                "Enforce HTTPS via server.ssl.* (or a forwarded-headers strategy when TLS terminates upstream) for any chain using httpBasic(), or switch to a mechanism that does not repeat credentials on every request.",
                "https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/basic.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.isProductionProfileActive() || context.isTlsConfigured()) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.hasFilter("BasicAuthenticationFilter")) {
                details.add(chain.describe() + " authenticates with HTTP Basic while no TLS is configured.");
            }
        }
        return violation(details);
    }
}

final class UsernameEnumerationRiskRule extends AbstractSecurityRule {

    UsernameEnumerationRiskRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-AUTH-008",
                        "hideUserNotFoundExceptions should stay enabled",
                        SecurityCategory.AUTHENTICATION,
                        "MEDIUM",
                        "Detects an AbstractUserDetailsAuthenticationProvider (e.g. DaoAuthenticationProvider) with hideUserNotFoundExceptions explicitly set to false, which lets a login failure distinguish an unknown username from a wrong password -- a username-enumeration oracle.",
                        "Leave hideUserNotFoundExceptions at its default (true) so a failed login always reports the same generic BadCredentialsException regardless of whether the username exists.",
                        "https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/dao-authentication-provider.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (context.hideUserNotFoundExceptionsDisabled()) {
            return violation(
                    List.of(
                            "An authentication provider sets hideUserNotFoundExceptions=false, allowing username enumeration via login error differences."));
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Authorization
// ---------------------------------------------------------------------------

final class MissingAuthorizationFilterRule extends AbstractSecurityRule {

    MissingAuthorizationFilterRule() {
        super(new SecurityRuleDefinition(
                "SEC-AUTHZ-001",
                "Every filter chain should enforce authorization",
                SecurityCategory.AUTHORIZATION,
                "HIGH",
                "Detects a SecurityFilterChain that installs no AuthorizationFilter, so matched requests are unguarded.",
                "Add authorizeHttpRequests(...) with at least anyRequest().authenticated() (or an explicit denyAll) to the chain.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (!chain.hasAuthorizationFilter()) {
                details.add(chain.describe() + " installs no authorization filter.");
            }
        }
        return violation(details);
    }
}

final class PermitAllCatchAllRule extends AbstractSecurityRule {

    PermitAllCatchAllRule() {
        super(new SecurityRuleDefinition(
                "SEC-AUTHZ-002",
                "Avoid blanket permitAll authorization",
                SecurityCategory.AUTHORIZATION,
                "HIGH",
                "Detects a chain whose authorization grants every request to anonymous callers (permitAll catch-all).",
                "Restrict sensitive paths and finish with anyRequest().authenticated(); keep permitAll only for genuinely public endpoints.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (Boolean.TRUE.equals(chain.permitsAllAnonymous())
                    && chain.matchesAnyRequest()
                    && chain.hasRealAuthenticationFilter()) {
                details.add(
                        chain.describe()
                                + " matches every request and permits it anonymously even though it configures authentication.");
            }
        }
        return violation(details);
    }
}

final class EffectivelyDisabledSecurityRule extends AbstractSecurityRule {

    EffectivelyDisabledSecurityRule() {
        super(new SecurityRuleDefinition(
                "SEC-AUTHZ-003",
                "Application security should not be effectively disabled",
                SecurityCategory.AUTHORIZATION,
                "HIGH",
                "Detects when every filter chain permits all requests anonymously and no chain requires authentication.",
                "Define authorization rules that require authentication for non-public endpoints instead of leaving the app fully open.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<FilterChainModel> chains = context.chains();
        if (chains.isEmpty()) {
            return pass();
        }
        boolean anyDeterminable = chains.stream().anyMatch(chain -> chain.permitsAllAnonymous() != null);
        if (!anyDeterminable) {
            return skipped("Authorization decisions could not be simulated for any chain.");
        }
        boolean allOpen = chains.stream().allMatch(chain -> Boolean.TRUE.equals(chain.permitsAllAnonymous()));
        boolean anyAuthentication = chains.stream().anyMatch(FilterChainModel::hasRealAuthenticationFilter);
        if (allOpen && !anyAuthentication) {
            return violation(List.of("All " + chains.size()
                    + " security filter chains permit every request anonymously with no authentication mechanism."));
        }
        return pass();
    }
}

final class CatchAllChainOrderingRule extends AbstractSecurityRule {

    CatchAllChainOrderingRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-AUTHZ-004",
                        "Catch-all filter chains should be ordered last",
                        SecurityCategory.AUTHORIZATION,
                        "INFO",
                        "Detects a chain that matches any request placed before more specific chains, which then never run.",
                        "Give earlier chains an explicit securityMatcher and keep the catch-all (any request) chain last by @Order.",
                        "https://docs.spring.io/spring-security/reference/servlet/configuration/java.html#_multiple_httpsecurity_instances"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<FilterChainModel> chains = context.chains();
        if (chains.size() < 2) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (int i = 0; i < chains.size() - 1; i++) {
            FilterChainModel chain = chains.get(i);
            if (chain.matchesAnyRequest()) {
                details.add(chain.describe()
                        + " matches any request but is not the last chain; later chains are unreachable.");
            }
        }
        return violation(details);
    }
}

final class AuthorizationRuleShadowedRule extends AbstractSecurityRule {

    AuthorizationRuleShadowedRule() {
        super(new SecurityRuleDefinition(
                "SEC-AUTHZ-005",
                "Broader authorizeHttpRequests matchers should not shadow narrower ones",
                SecurityCategory.AUTHORIZATION,
                "HIGH",
                "Detects an unconditional, method-agnostic catch-all matcher (e.g. requestMatchers(\"/**\")) registered before a narrower matcher in the same chain's authorizeHttpRequests rules. Requests are matched in declaration order and Spring Security does not guard against this (unlike anyRequest(), a plain requestMatchers(\"/**\") does not block further rules from being added after it), so the narrower, later rule can never take effect.",
                "Register narrower matchers (e.g. requestMatchers(\"/admin/**\").hasRole(\"ADMIN\")) before the broader catch-all, or replace the catch-all with anyRequest() so later requestMatchers additions are rejected at startup instead of silently ignored.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (Boolean.TRUE.equals(chain.authorizationRuleShadowed())) {
                details.add(
                        chain.describe()
                                + " registers a broader matcher before a narrower one in its authorizeHttpRequests rules; the narrower rule is unreachable.");
            }
        }
        return violation(details);
    }
}

// ---------------------------------------------------------------------------
// CSRF
// ---------------------------------------------------------------------------

final class CsrfDisabledStatefulRule extends AbstractSecurityRule {

    CsrfDisabledStatefulRule() {
        super(new SecurityRuleDefinition(
                "SEC-CSRF-001",
                "CSRF protection should stay on for session-based chains",
                SecurityCategory.CSRF,
                "HIGH",
                "Detects a stateful (session/remember-me) chain with no CsrfFilter installed.",
                "Keep CSRF enabled for browser, cookie, or session authenticated chains; only disable it for stateless token APIs.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.isStateful() && !chain.hasFilter("CsrfFilter")) {
                details.add(chain.describe() + " manages sessions but does not install a CsrfFilter.");
            }
        }
        return violation(details);
    }
}

final class CsrfGloballyDisabledRule extends AbstractSecurityRule {

    CsrfGloballyDisabledRule() {
        super(new SecurityRuleDefinition(
                "SEC-CSRF-002",
                "CSRF should not be disabled without stateless authentication",
                SecurityCategory.CSRF,
                "MEDIUM",
                "Detects chains with no CsrfFilter and no bearer-token (stateless) authentication to justify the removal.",
                "Disable CSRF only when the chain is stateless (e.g. bearer tokens); otherwise keep the CsrfFilter.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.isStateful()) {
                continue; // covered by SEC-CSRF-001
            }
            boolean stateless = chain.hasFilterContaining("BearerTokenAuthenticationFilter");
            if (!chain.hasFilter("CsrfFilter") && !stateless) {
                details.add(chain.describe() + " disables CSRF but is not a stateless token API.");
            }
        }
        return violation(details);
    }
}

// ---------------------------------------------------------------------------
// Session management
// ---------------------------------------------------------------------------

final class SessionFixationRule extends AbstractSecurityRule {

    SessionFixationRule() {
        super(new SecurityRuleDefinition(
                "SEC-SESSION-001",
                "Session fixation protection should be enabled",
                SecurityCategory.SESSION,
                "HIGH",
                "Detects a session-management strategy configured to skip changing the session id on authentication.",
                "Use the default changeSessionId (or migrateSession) session-fixation strategy instead of none().",
                "https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        boolean determinable = false;
        for (FilterChainModel chain : context.chains()) {
            if (chain.sessionFixationDisabled() != null) {
                determinable = true;
                if (Boolean.TRUE.equals(chain.sessionFixationDisabled())) {
                    details.add(chain.describe() + " disables session-fixation protection (sessionFixation().none()).");
                }
            }
        }
        if (!details.isEmpty()) {
            return violation(details);
        }
        return determinable ? pass() : skipped("Session-fixation strategy could not be introspected.");
    }
}

final class SessionCookieSecureRule extends AbstractSecurityRule {

    SessionCookieSecureRule() {
        super(new SecurityRuleDefinition(
                "SEC-SESSION-002",
                "Session cookie should set the Secure flag",
                SecurityCategory.SESSION,
                "MEDIUM",
                "Detects server.servlet.session.cookie.secure=false, or unset while a production profile is active.",
                "Set server.servlet.session.cookie.secure=true so the session cookie is only sent over HTTPS.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        String value = context.firstProperty("server.servlet.session.cookie.secure");
        if ("false".equalsIgnoreCase(String.valueOf(value))) {
            return violation(List.of("server.servlet.session.cookie.secure is explicitly false."));
        }
        if (value == null && context.isProductionProfileActive()) {
            return violation(
                    List.of("server.servlet.session.cookie.secure is not set while a production profile is active."));
        }
        return pass();
    }
}

final class SessionCookieHttpOnlyRule extends AbstractSecurityRule {

    SessionCookieHttpOnlyRule() {
        super(new SecurityRuleDefinition(
                "SEC-SESSION-003",
                "Session cookie should set the HttpOnly flag",
                SecurityCategory.SESSION,
                "MEDIUM",
                "Detects server.servlet.session.cookie.http-only=false, exposing the session cookie to JavaScript.",
                "Keep server.servlet.session.cookie.http-only=true to mitigate cookie theft via XSS.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (context.isPropertyFalse("server.servlet.session.cookie.http-only")) {
            return violation(List.of("server.servlet.session.cookie.http-only is explicitly false."));
        }
        return pass();
    }
}

final class SessionCookieSameSiteRule extends AbstractSecurityRule {

    SessionCookieSameSiteRule() {
        super(new SecurityRuleDefinition(
                "SEC-SESSION-004",
                "Session cookie should declare a SameSite policy",
                SecurityCategory.SESSION,
                "LOW",
                "Detects that server.servlet.session.cookie.same-site is unset, leaving the policy to the container default.",
                "Set server.servlet.session.cookie.same-site=Lax (or Strict) to reduce cross-site request exposure.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.hasStatefulChain()) {
            return pass();
        }
        String value = context.firstProperty("server.servlet.session.cookie.same-site");
        if (value == null) {
            return violation(List.of("server.servlet.session.cookie.same-site is not configured."));
        }
        return pass();
    }
}

final class SessionTimeoutRule extends AbstractSecurityRule {

    SessionTimeoutRule() {
        super(new SecurityRuleDefinition(
                "SEC-SESSION-005",
                "An explicit session timeout should be configured",
                SecurityCategory.SESSION,
                "INFO",
                "Detects that server.servlet.session.timeout is unset, leaving the container's default timeout.",
                "Set server.servlet.session.timeout to a bounded value appropriate for the application's risk profile.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.hasStatefulChain()) {
            return pass();
        }
        String value = context.firstProperty("server.servlet.session.timeout", "spring.session.timeout");
        if (value == null) {
            return violation(List.of("No explicit session timeout is configured for the session-based chains."));
        }
        return pass();
    }
}

final class BearerTokenStatefulRule extends AbstractSecurityRule {

    BearerTokenStatefulRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-SESSION-006",
                        "Bearer token authentication chains should be stateless",
                        SecurityCategory.SESSION,
                        "HIGH",
                        "Detects a chain with both a Bearer token filter (stateless) and session management filters (stateful).",
                        "Configure sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS) to avoid creating HTTP sessions for REST API calls.",
                        "https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-stateless"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.hasFilterContaining("BearerTokenAuthenticationFilter") && chain.isStateful()) {
                details.add(chain.describe() + " accepts Bearer tokens but also maintains stateful sessions.");
            }
        }
        return violation(details);
    }
}

final class ConcurrentSessionControlRule extends AbstractSecurityRule {

    ConcurrentSessionControlRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-SESSION-007",
                        "Consider configuring concurrent session control",
                        SecurityCategory.SESSION,
                        "INFO",
                        "Detects an interactive form-login chain that maintains sessions but installs no ConcurrentSessionFilter (no maximumSessions limit).",
                        "Set sessionManagement().maximumSessions(n) so a stolen or shared credential cannot open unlimited concurrent sessions.",
                        "https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html#ns-concurrent-sessions"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            boolean interactiveLogin = chain.hasFilter("UsernamePasswordAuthenticationFilter")
                    || chain.hasFilter("DefaultLoginPageGeneratingFilter");
            if (interactiveLogin && chain.isStateful() && !chain.hasFilterContaining("ConcurrentSession")) {
                details.add(chain.describe()
                        + " maintains sessions for an interactive login but configures no concurrent-session control.");
            }
        }
        return violation(details);
    }
}

final class WeakRememberMeKeyRule extends AbstractSecurityRule {

    private static final int MIN_KEY_LENGTH = 16;

    WeakRememberMeKeyRule() {
        super(new SecurityRuleDefinition(
                "SEC-SESSION-008",
                "Remember-me signing key should be sufficiently long",
                SecurityCategory.SESSION,
                "MEDIUM",
                "Detects a RememberMeAuthenticationFilter whose signing key is shorter than 16 characters, which makes the remember-me token's HMAC signature easier to brute-force and forge. Only the key's length is inspected -- the key value itself is never read into a finding.",
                "Configure a long, random remember-me key (16+ characters, generated from a secure source) via rememberMe().key(...), ideally sourced from an externalized secret rather than a literal in configuration.",
                "https://docs.spring.io/spring-security/reference/servlet/authentication/rememberme.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            Integer keyLength = chain.rememberMeKeyLength();
            if (keyLength != null && keyLength < MIN_KEY_LENGTH) {
                details.add(chain.describe() + " configures a remember-me signing key shorter than " + MIN_KEY_LENGTH
                        + " characters.");
            }
        }
        return violation(details);
    }
}

// ---------------------------------------------------------------------------
// Transport & security headers
// ---------------------------------------------------------------------------

final class HstsHeaderRule extends AbstractSecurityRule {

    HstsHeaderRule() {
        super(new SecurityRuleDefinition(
                "SEC-HEAD-001",
                "HTTP Strict Transport Security should be emitted",
                SecurityCategory.HEADERS,
                "MEDIUM",
                "Detects chains whose header writers do not include an HSTS writer.",
                "Keep the default HstsHeaderWriter (served over HTTPS) so browsers pin TLS for the domain.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-hsts"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.headerWriterFilterPresent() && !chain.hasHeaderWriterContaining("Hsts")) {
                details.add(chain.describe() + " does not emit a Strict-Transport-Security header.");
            }
        }
        return violation(details);
    }
}

final class FrameOptionsRule extends AbstractSecurityRule {

    FrameOptionsRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-HEAD-002",
                        "X-Frame-Options (clickjacking protection) should stay enabled",
                        SecurityCategory.HEADERS,
                        "HIGH",
                        "Detects chains whose header writers omit X-Frame-Options / frame-ancestors protection.",
                        "Keep the default XFrameOptionsHeaderWriter (DENY/SAMEORIGIN) or a frame-ancestors CSP instead of disabling frame options globally.",
                        "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-frame-options"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.headerWriterFilterPresent()
                    && !chain.hasHeaderWriterContaining("XFrameOptions")
                    && !chain.hasHeaderWriterContaining("ContentSecurityPolicy")) {
                details.add(chain.describe() + " emits no X-Frame-Options or frame-ancestors header.");
            }
        }
        return violation(details);
    }
}

final class ContentSecurityPolicyRule extends AbstractSecurityRule {

    ContentSecurityPolicyRule() {
        super(new SecurityRuleDefinition(
                "SEC-HEAD-003",
                "A Content-Security-Policy should be defined",
                SecurityCategory.HEADERS,
                "LOW",
                "Detects chains that serve no Content-Security-Policy header.",
                "Add a ContentSecurityPolicyHeaderWriter with a tailored policy to mitigate XSS and data injection.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-csp"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.headerWriterFilterPresent() && !chain.hasHeaderWriterContaining("ContentSecurityPolicy")) {
                details.add(chain.describe() + " does not define a Content-Security-Policy.");
            }
        }
        return violation(details);
    }
}

final class ContentTypeOptionsRule extends AbstractSecurityRule {

    ContentTypeOptionsRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-HEAD-004",
                        "X-Content-Type-Options should stay enabled",
                        SecurityCategory.HEADERS,
                        "LOW",
                        "Detects chains whose header writers omit X-Content-Type-Options: nosniff.",
                        "Keep the default XContentTypeOptionsHeaderWriter so browsers do not MIME-sniff responses.",
                        "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-content-type-options"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.headerWriterFilterPresent() && !chain.hasHeaderWriterContaining("XContentTypeOptions")) {
                details.add(chain.describe() + " does not emit X-Content-Type-Options: nosniff.");
            }
        }
        return violation(details);
    }
}

final class ReferrerPolicyHeaderRule extends AbstractSecurityRule {

    ReferrerPolicyHeaderRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-HEAD-005",
                        "A Referrer-Policy header should be emitted",
                        SecurityCategory.HEADERS,
                        "LOW",
                        "Detects chains whose header writers do not emit a Referrer-Policy header (not sent by default).",
                        "Add a ReferrerPolicyHeaderWriter via headers().referrerPolicy(...) with a policy such as strict-origin-when-cross-origin to limit referrer leakage.",
                        "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-referrer"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.headerWriterFilterPresent() && !chain.hasHeaderWriterContaining("ReferrerPolicy")) {
                details.add(chain.describe() + " does not emit a Referrer-Policy header.");
            }
        }
        return violation(details);
    }
}

final class PermissionsPolicyHeaderRule extends AbstractSecurityRule {

    PermissionsPolicyHeaderRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-HEAD-006",
                        "A Permissions-Policy header should be considered",
                        SecurityCategory.HEADERS,
                        "INFO",
                        "Detects chains whose header writers do not emit a Permissions-Policy header (not sent by default).",
                        "Add a PermissionsPolicyHeaderWriter via headers().permissionsPolicyHeader(...) to restrict powerful browser features the application does not use.",
                        "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-permissions-policy"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.headerWriterFilterPresent()
                    && !chain.hasHeaderWriterContaining("PermissionsPolicy")
                    && !chain.hasHeaderWriterContaining("FeaturePolicy")) {
                details.add(chain.describe() + " does not emit a Permissions-Policy header.");
            }
        }
        return violation(details);
    }
}

// ---------------------------------------------------------------------------
// Transport & security headers (continued)
// ---------------------------------------------------------------------------

final class HeaderWritersDisabledRule extends AbstractSecurityRule {

    HeaderWritersDisabledRule() {
        super(new SecurityRuleDefinition(
                "SEC-HEAD-007",
                "Security response headers should not be globally disabled",
                SecurityCategory.HEADERS,
                "HIGH",
                "Detects a browser-facing (authenticated or session) chain that installs no HeaderWriterFilter, which means headers().disable() removed every security header (HSTS, X-Frame-Options, X-Content-Type-Options, ...).",
                "Remove headers().disable(); keep the default HeaderWriterFilter so security headers are emitted, and only tune individual writers you do not need.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            boolean browserFacing = chain.hasRealAuthenticationFilter() || chain.isStateful();
            if (browserFacing && !chain.headerWriterFilterPresent()) {
                details.add(chain.describe() + " installs no HeaderWriterFilter, so security headers are disabled.");
            }
        }
        return violation(details);
    }
}

final class WeakHstsPolicyRule extends AbstractSecurityRule {

    WeakHstsPolicyRule() {
        super(new SecurityRuleDefinition(
                "SEC-HEAD-008",
                "HSTS should use a strong max-age and includeSubDomains",
                SecurityCategory.HEADERS,
                "LOW",
                "Detects an HstsHeaderWriter configured with a max-age under one year or without includeSubDomains, which weakens the protocol-downgrade and cookie-hijacking protection HSTS is meant to provide.",
                "Keep the default HstsHeaderWriter settings (max-age=31536000, includeSubDomains=true), or configure headers().httpStrictTransportSecurity() explicitly with those values.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-hsts"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.hasWeakHsts()) {
                details.add(
                        chain.describe() + " emits an HSTS header with a weak max-age or without includeSubDomains.");
            }
        }
        return violation(details);
    }
}

final class WeakContentSecurityPolicyRule extends AbstractSecurityRule {

    WeakContentSecurityPolicyRule() {
        super(new SecurityRuleDefinition(
                "SEC-HEAD-009",
                "Content-Security-Policy should not allow unsafe-inline/unsafe-eval, unscoped wildcards, or omit key hardening directives",
                SecurityCategory.HEADERS,
                "MEDIUM",
                "Detects a ContentSecurityPolicyHeaderWriter policy that includes 'unsafe-inline' or 'unsafe-eval', an unscoped wildcard source (bare * or a scheme-only wildcard such as https://*, but not a scoped pattern like https://*.example.com), or that omits the base-uri/frame-ancestors directives or both object-src and default-src -- all of which weaken the XSS/clickjacking mitigation a CSP is meant to provide.",
                "Remove 'unsafe-inline'/'unsafe-eval' and unscoped wildcard sources (scope wildcards to a trusted domain, e.g. https://*.example.com); add base-uri 'self', frame-ancestors 'none' (or an explicit allow-list), and an object-src (or default-src) directive.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-csp"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.hasWeakCsp()) {
                details.add(
                        chain.describe()
                                + " defines a Content-Security-Policy that allows unsafe-inline/unsafe-eval, an unscoped wildcard source, or omits base-uri/frame-ancestors/object-src hardening directives.");
            }
        }
        return violation(details);
    }
}

final class CrossOriginIsolationHeadersRule extends AbstractSecurityRule {

    CrossOriginIsolationHeadersRule() {
        super(new SecurityRuleDefinition(
                "SEC-HEAD-010",
                "Cross-origin isolation headers should be considered",
                SecurityCategory.HEADERS,
                "INFO",
                "Detects chains whose header writers emit neither a Cross-Origin-Opener-Policy nor a Cross-Origin-Embedder-Policy header (neither is sent by default).",
                "Add CrossOriginOpenerPolicyHeaderWriter / CrossOriginEmbedderPolicyHeaderWriter via headers().crossOriginOpenerPolicy(...) / .crossOriginEmbedderPolicy(...) if the application needs cross-origin isolation (e.g. for SharedArrayBuffer) or Spectre-style side-channel hardening.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.headerWriterFilterPresent()
                    && !chain.hasHeaderWriterContaining("CrossOriginOpenerPolicy")
                    && !chain.hasHeaderWriterContaining("CrossOriginEmbedderPolicy")) {
                details.add(chain.describe()
                        + " does not emit a Cross-Origin-Opener-Policy or Cross-Origin-Embedder-Policy header.");
            }
        }
        return violation(details);
    }
}

// ---------------------------------------------------------------------------
// CORS
// ---------------------------------------------------------------------------

final class CorsWildcardOriginRule extends AbstractSecurityRule {

    CorsWildcardOriginRule() {
        super(new SecurityRuleDefinition(
                "SEC-CORS-001",
                "CORS should not allow all origins",
                SecurityCategory.CORS,
                "HIGH",
                "Detects a CorsConfiguration that allows the * wildcard origin.",
                "Enumerate the exact trusted origins instead of \"*\"; use allowedOriginPatterns only for tightly-scoped patterns.",
                "https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (CorsConfigModel cors : context.corsConfigs()) {
            if (cors.allowsWildcardOrigin() && !cors.allowsCredentials()) {
                details.add(cors.describe());
            }
        }
        if (details.isEmpty() && context.customCorsSourcePresent()) {
            return skipped(
                    "A custom CorsConfigurationSource is present and cannot be introspected for wildcard origins.");
        }
        return violation(details);
    }
}

final class CorsWildcardWithCredentialsRule extends AbstractSecurityRule {

    CorsWildcardWithCredentialsRule() {
        super(new SecurityRuleDefinition(
                "SEC-CORS-002",
                "CORS must not combine wildcard origins with credentials",
                SecurityCategory.CORS,
                "HIGH",
                "Detects a CorsConfiguration that allows the * origin together with allowCredentials=true.",
                "Never pair allowCredentials(true) with a wildcard origin; list explicit origins so cookies and auth headers are not leaked cross-site.",
                "https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (CorsConfigModel cors : context.corsConfigs()) {
            if (cors.allowsWildcardOrigin() && cors.allowsCredentials()) {
                details.add(cors.describe() + " with allowCredentials=true.");
            }
        }
        if (details.isEmpty() && context.customCorsSourcePresent()) {
            return skipped(
                    "A custom CorsConfigurationSource is present and cannot be introspected for wildcard origins with credentials.");
        }
        return violation(details);
    }
}

final class CorsNotInSecurityChainRule extends AbstractSecurityRule {

    CorsNotInSecurityChainRule() {
        super(new SecurityRuleDefinition(
                "SEC-CORS-003",
                "CORS should be wired through the security filter chain",
                SecurityCategory.CORS,
                "INFO",
                "Detects a CorsConfigurationSource bean while no filter chain installs a CorsFilter.",
                "Enable .cors(...) on the HttpSecurity so preflight handling is consistent with the security chain rather than MVC-only.",
                "https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.corsSourcePresent()) {
            return pass();
        }
        boolean anyCorsFilter = context.chains().stream().anyMatch(chain -> chain.hasFilter("CorsFilter"));
        if (anyCorsFilter) {
            return pass();
        }
        return violation(List.of(
                "A CorsConfigurationSource bean is present but no security filter chain installs a CorsFilter."));
    }
}

final class CorsWildcardMethodsHeadersRule extends AbstractSecurityRule {

    CorsWildcardMethodsHeadersRule() {
        super(new SecurityRuleDefinition(
                "SEC-CORS-004",
                "CORS should not allow all methods or headers with credentials",
                SecurityCategory.CORS,
                "MEDIUM",
                "Detects a CorsConfiguration that allows the * wildcard for methods or headers together with allowCredentials=true.",
                "Enumerate the exact methods and headers the API needs instead of \"*\" when credentials are allowed, so cross-site callers cannot send arbitrary authenticated requests.",
                "https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (CorsConfigModel cors : context.corsConfigs()) {
            if (!cors.allowsCredentials()) {
                continue;
            }
            if (cors.allowsWildcardMethod()) {
                details.add(cors.describe() + " allows all HTTP methods (*) with allowCredentials=true.");
            }
            if (cors.allowsWildcardHeader()) {
                details.add(cors.describe() + " allows all request headers (*) with allowCredentials=true.");
            }
        }
        if (details.isEmpty() && context.customCorsSourcePresent()) {
            return skipped(
                    "A custom CorsConfigurationSource is present and cannot be introspected for wildcard methods/headers with credentials.");
        }
        return violation(details);
    }
}

// ---------------------------------------------------------------------------
// CORS (continued)
// ---------------------------------------------------------------------------

final class BroadCorsOriginPatternRule extends AbstractSecurityRule {

    BroadCorsOriginPatternRule() {
        super(new SecurityRuleDefinition(
                "SEC-CORS-006",
                "CORS should not allow broad origin patterns",
                SecurityCategory.CORS,
                "MEDIUM",
                "Detects allowedOriginPatterns that match a dangerously broad set of origins (wildcard scheme or host, e.g. https://*, *://*, *.com) beyond the exact \"*\" already covered by SEC-CORS-001/002.",
                "Replace broad patterns with the exact origins (or tightly-scoped subdomain wildcards such as https://*.example.com) the application trusts; broad patterns combined with credentials let untrusted sites make authenticated cross-site calls.",
                "https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        boolean credentialed = false;
        for (CorsConfigModel cors : context.corsConfigs()) {
            List<String> broad = cors.broadOriginPatterns();
            if (broad.isEmpty()) {
                continue;
            }
            String suffix = cors.allowsCredentials() ? " with allowCredentials=true" : "";
            credentialed = credentialed || cors.allowsCredentials();
            details.add(cors.describe() + " uses broad origin patterns " + broad + suffix + ".");
        }
        if (details.isEmpty() && context.customCorsSourcePresent()) {
            return skipped(
                    "A custom CorsConfigurationSource is present and cannot be introspected for broad origin patterns.");
        }
        return violation(credentialed ? SecurityRuleSupport.HIGH : SecurityRuleSupport.MEDIUM, details);
    }
}

// ---------------------------------------------------------------------------
// Method security
// ---------------------------------------------------------------------------

final class MethodSecurityAnnotationsIgnoredRule extends AbstractSecurityRule {

    MethodSecurityAnnotationsIgnoredRule() {
        super(new SecurityRuleDefinition(
                "SEC-METHOD-001",
                "Method security annotations require method security to be enabled",
                SecurityCategory.METHOD_SECURITY,
                "HIGH",
                "Detects @PreAuthorize/@Secured usage while no method-security interceptors are registered, so the annotations are ignored.",
                "Add @EnableMethodSecurity to a configuration class so the security annotations are actually enforced.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (context.methodSecurityAnnotationsPresent() && !context.methodSecurityEnabled()) {
            return violation(
                    List.of(
                            "Security method annotations were found but method security is not enabled; the annotations are silently ignored."));
        }
        return pass();
    }
}

final class LegacyGlobalMethodSecurityRule extends AbstractSecurityRule {

    LegacyGlobalMethodSecurityRule() {
        super(new SecurityRuleDefinition(
                "SEC-METHOD-002",
                "Replace @EnableGlobalMethodSecurity with @EnableMethodSecurity",
                SecurityCategory.METHOD_SECURITY,
                "LOW",
                "Detects the legacy @EnableGlobalMethodSecurity configuration removed/deprecated in Spring Security 6+.",
                "Migrate to @EnableMethodSecurity, which enables @PreAuthorize/@PostAuthorize by default and uses the modern AuthorizationManager API.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (context.globalMethodSecurityLegacyPresent()) {
            return violation(List.of("@EnableGlobalMethodSecurity is in use; migrate to @EnableMethodSecurity."));
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Actuator exposure
// ---------------------------------------------------------------------------

final class ActuatorWildcardExposureRule extends AbstractSecurityRule {

    ActuatorWildcardExposureRule() {
        super(new SecurityRuleDefinition(
                "SEC-ACT-001",
                "Actuator endpoints should not all be web-exposed",
                SecurityCategory.ACTUATOR,
                "HIGH",
                "Detects management.endpoints.web.exposure.include=* exposing actuator endpoints over HTTP beyond health/info, after subtracting management.endpoints.web.exposure.exclude. A wildcard include fully hardened by excluding every sensitive endpoint is not flagged.",
                "Expose only the endpoints you need (e.g. health, info) and secure the rest behind authentication.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        String include = context.firstHostProperty("management.endpoints.web.exposure.include");
        if (include == null || !include.trim().equals("*") || !context.exposesBeyondHealthAndInfo()) {
            return pass();
        }
        String exclude = context.firstHostProperty("management.endpoints.web.exposure.exclude");
        if (exclude == null || exclude.isBlank()) {
            return violation(List.of("management.endpoints.web.exposure.include=* exposes all actuator endpoints."));
        }
        return violation(List.of("management.endpoints.web.exposure.include=* with exclude=" + exclude.trim()
                + " still leaves sensitive endpoints reachable beyond health/info."));
    }
}

final class ActuatorSensitiveExposureRule extends AbstractSecurityRule {

    ActuatorSensitiveExposureRule() {
        super(new SecurityRuleDefinition(
                "SEC-ACT-002",
                "Sensitive actuator endpoints should not be exposed",
                SecurityCategory.ACTUATOR,
                "HIGH",
                "Detects high-value actuator endpoints (env, beans, configprops, heapdump, threaddump, shutdown, loggers, mappings) that remain web-exposed once management.endpoints.web.exposure.exclude is subtracted from the include list.",
                "Remove sensitive endpoints from management.endpoints.web.exposure.include (or add them to management.endpoints.web.exposure.exclude) so they are not reachable, or protect them with authentication.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        Set<String> exposed = context.effectiveSensitiveActuatorExposure();
        if (exposed.isEmpty()) {
            return pass();
        }
        List<String> details = exposed.stream()
                .sorted()
                .map(value -> "Actuator endpoint '" + value + "' is web-exposed.")
                .toList();
        return violation(details);
    }
}

final class ActuatorUnprotectedRule extends AbstractSecurityRule {

    ActuatorUnprotectedRule() {
        super(new SecurityRuleDefinition(
                "SEC-ACT-003",
                "Exposed actuator endpoints should be protected by a security chain",
                SecurityCategory.ACTUATOR,
                "MEDIUM",
                "Detects web-exposed actuator endpoints (beyond health/info, after subtracting management.endpoints.web.exposure.exclude) when no filter chain references /actuator.",
                "Add a SecurityFilterChain with a securityMatcher for the actuator base path that requires authentication/authorization.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.security"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.exposesBeyondHealthAndInfo()) {
            return pass();
        }
        String base = context.firstProperty("management.endpoints.web.base-path");
        String basePath = (base == null || base.isBlank()) ? "/actuator" : base.trim();
        boolean chainReferencesActuator = context.chains().stream()
                .anyMatch(chain -> chain.matcher() != null
                        && chain.matcher().toLowerCase(Locale.ROOT).contains(basePath.toLowerCase(Locale.ROOT)));
        if (chainReferencesActuator) {
            return pass();
        }
        return violation(List.of(
                "Actuator endpoints are exposed at " + basePath + " but no security filter chain matches that path."));
    }
}

final class HealthDetailsExposureRule extends AbstractSecurityRule {

    HealthDetailsExposureRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-ACT-004",
                        "Actuator health details should not be exposed unconditionally",
                        SecurityCategory.ACTUATOR,
                        "HIGH",
                        "Detects management.endpoint.health.show-details=always, which leaks infrastructure details to anonymous callers.",
                        "Change management.endpoint.health.show-details to 'when-authorized' (the default) or ensure the /health endpoint is strictly authenticated.",
                        "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health.show-details"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        String showDetails = context.firstHostProperty("management.endpoint.health.show-details");
        if ("always".equalsIgnoreCase(showDetails)) {
            return violation(List.of("management.endpoint.health.show-details is set to 'always'."));
        }
        return pass();
    }
}

final class ShutdownEndpointEnabledRule extends AbstractSecurityRule {

    ShutdownEndpointEnabledRule() {
        super(new SecurityRuleDefinition(
                "SEC-ACT-005",
                "The actuator shutdown endpoint should not be enabled",
                SecurityCategory.ACTUATOR,
                "HIGH",
                "Detects management.endpoint.shutdown.enabled=true, which lets a caller stop the application (denial of service) if reachable.",
                "Keep the shutdown endpoint disabled (the default); if you truly need it, restrict it to a secured management port behind strict authentication.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.enabling"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (context.isPropertyTrue("management.endpoint.shutdown.enabled")) {
            return violation(List.of("management.endpoint.shutdown.enabled=true exposes application shutdown."));
        }
        return pass();
    }
}

final class ManagementPortIsolationRule extends AbstractSecurityRule {

    ManagementPortIsolationRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-ACT-006",
                        "Sensitive actuator endpoints should use an isolated management port",
                        SecurityCategory.ACTUATOR,
                        "INFO",
                        "Notes that sensitive actuator endpoints (beyond health/info, after subtracting management.endpoints.web.exposure.exclude) are exposed on the main application port because management.server.port is unset.",
                        "Set management.server.port to a separate, network-restricted port so actuator endpoints are not reachable on the public application port.",
                        "https://docs.spring.io/spring-boot/reference/actuator/monitoring.html#actuator.monitoring.customizing-management-server-port"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.exposesBeyondHealthAndInfo()) {
            return pass();
        }
        if (context.firstProperty("management.server.port") != null) {
            return pass();
        }
        return violation(
                List.of(
                        "Sensitive actuator endpoints are exposed but management.server.port is unset, so they share the application port."));
    }
}

// ---------------------------------------------------------------------------
// Actuator exposure (continued)
// ---------------------------------------------------------------------------

final class ActuatorShowValuesRule extends AbstractSecurityRule {

    ActuatorShowValuesRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-ACT-007",
                        "Actuator env/configprops values must stay sanitized",
                        SecurityCategory.ACTUATOR,
                        "HIGH",
                        "Detects management.endpoint.env.show-values=always or management.endpoint.configprops.show-values=always, which reveals unsanitized property values (including secrets) to callers of /env and /configprops.",
                        "Leave show-values at 'never' or 'when-authorized' (the defaults) so the actuator sanitizer masks sensitive values; only relax it behind strict authorization.",
                        "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.sanitization"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        if ("always".equalsIgnoreCase(context.firstHostProperty("management.endpoint.env.show-values"))) {
            details.add("management.endpoint.env.show-values=always exposes unsanitized /env values.");
        }
        if ("always".equalsIgnoreCase(context.firstHostProperty("management.endpoint.configprops.show-values"))) {
            details.add("management.endpoint.configprops.show-values=always exposes unsanitized /configprops values.");
        }
        return violation(details);
    }
}

// ---------------------------------------------------------------------------
// OAuth2 / JWT resource server
// ---------------------------------------------------------------------------

final class ResourceServerValidationRule extends AbstractSecurityRule {

    ResourceServerValidationRule() {
        super(new SecurityRuleDefinition(
                "SEC-OAUTH-001",
                "JWT resource server must validate tokens via issuer or JWK set",
                SecurityCategory.OAUTH2,
                "HIGH",
                "Detects a bearer-token resource server with no issuer-uri, jwk-set-uri, public key, or JwtDecoder bean configured.",
                "Configure spring.security.oauth2.resourceserver.jwt.issuer-uri (or jwk-set-uri / a JwtDecoder bean) so signatures and the issuer are verified.",
                "https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        boolean bearerChain = context.chains().stream()
                .anyMatch(chain -> chain.hasFilterContaining("BearerTokenAuthenticationFilter"));
        if (!bearerChain) {
            return pass();
        }
        boolean configured = context.firstProperty(
                                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                                "spring.security.oauth2.resourceserver.jwt.public-key-location")
                        != null
                || !context.jwtDecoderTypes().isEmpty();
        if (configured) {
            return pass();
        }
        return violation(List.of(
                "A bearer-token resource server is active but no issuer/JWK/JwtDecoder validation is configured."));
    }
}

final class JwtAudienceValidationRule extends AbstractSecurityRule {

    JwtAudienceValidationRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-OAUTH-002",
                        "Validate the JWT audience claim",
                        SecurityCategory.OAUTH2,
                        "MEDIUM",
                        "Notes that issuer-based resource servers do not validate the aud claim unless a custom OAuth2TokenValidator bean (including one composed via DelegatingOAuth2TokenValidator) or a custom JwtDecoder is registered.",
                        "Add an audience OAuth2TokenValidator to the JwtDecoder so tokens minted for other resource servers are rejected.",
                        "https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-validation"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        boolean issuerBased = context.firstProperty(
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri")
                != null;
        if (!issuerBased) {
            return pass();
        }
        String audiences = context.firstProperty("spring.security.oauth2.resourceserver.jwt.audiences");
        if (audiences != null) {
            return pass();
        }
        if (!context.oauth2TokenValidatorTypes().isEmpty()) {
            // A custom OAuth2TokenValidator bean (including a DelegatingOAuth2TokenValidator that
            // composes an audience check alongside the default issuer/timestamp validators) may
            // already validate the audience, so this is advisory only.
            return violation(
                    SecurityRuleSupport.INFO,
                    List.of("Resource server uses issuer/JWK validation with a custom OAuth2TokenValidator ("
                            + String.join(", ", context.oauth2TokenValidatorTypes())
                            + "); confirm it validates the audience (aud) claim."));
        }
        if (!context.jwtDecoderTypes().isEmpty()) {
            // A custom JwtDecoder may already register an audience validator, so this is advisory only.
            return violation(
                    SecurityRuleSupport.INFO,
                    List.of("Resource server uses issuer/JWK validation with a custom JwtDecoder ("
                            + String.join(", ", context.jwtDecoderTypes())
                            + "); confirm it validates the audience (aud) claim."));
        }
        return violation(
                List.of(
                        "Resource server uses issuer/JWK validation; confirm a custom audience (aud) validator is registered."));
    }
}

final class JwtStaticKeyRule extends AbstractSecurityRule {

    JwtStaticKeyRule() {
        super(new SecurityRuleDefinition(
                "SEC-OAUTH-003",
                "Prefer JWK rotation over a static signing key",
                SecurityCategory.OAUTH2,
                "MEDIUM",
                "Detects a resource server pinned to a static public key (public-key-location) with no issuer or JWK set URI.",
                "Use a jwk-set-uri / issuer-uri so signing keys can rotate, rather than a single embedded public key.",
                "https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        String publicKey = context.firstProperty("spring.security.oauth2.resourceserver.jwt.public-key-location");
        if (publicKey == null) {
            return pass();
        }
        boolean rotatable = context.firstProperty(
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri")
                != null;
        if (rotatable) {
            return pass();
        }
        return violation(List.of(
                "Resource server validates tokens with a static public key (no issuer/JWK set URI for rotation)."));
    }
}

// ---------------------------------------------------------------------------
// Configuration hygiene
// ---------------------------------------------------------------------------

final class SecurityDebugRule extends AbstractSecurityRule {

    SecurityDebugRule() {
        super(new SecurityRuleDefinition(
                "SEC-CONFIG-001",
                "Spring Security debug mode should be off",
                SecurityCategory.CONFIGURATION,
                "MEDIUM",
                "Detects spring.security.debug=true (or a DebugFilter), which logs filter chains and request details.",
                "Disable security debug mode outside local development; it leaks configuration and request information.",
                "https://docs.spring.io/spring-security/reference/servlet/configuration/java.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        boolean debugFilter = context.chains().stream().anyMatch(chain -> chain.hasFilterContaining("DebugFilter"));
        if (context.isPropertyTrue("spring.security.debug") || debugFilter) {
            String suffix = context.isProductionProfileActive() ? " while a production profile is active" : "";
            return violation(List.of("Spring Security debug mode is enabled" + suffix + "."));
        }
        return pass();
    }
}

final class H2ConsoleFrameOptionsRule extends AbstractSecurityRule {

    H2ConsoleFrameOptionsRule() {
        super(new SecurityRuleDefinition(
                "SEC-CONFIG-002",
                "H2 console should not be enabled in production",
                SecurityCategory.CONFIGURATION,
                "HIGH",
                "Detects spring.h2.console.enabled=true while a production profile is active; the console needs frame options relaxed and exposes the database.",
                "Disable the H2 console in production (keep it to dev profiles) so frame-options are not loosened and the database UI is not reachable.",
                "https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.h2-web-console"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (context.isPropertyTrue("spring.h2.console.enabled") && context.isProductionProfileActive()) {
            return violation(List.of("spring.h2.console.enabled=true while a production profile is active."));
        }
        return pass();
    }
}

final class ErrorResponseDisclosureRule extends AbstractSecurityRule {

    ErrorResponseDisclosureRule() {
        super(new SecurityRuleDefinition(
                "SEC-CONFIG-005",
                "Error responses should not leak stack traces or internal messages",
                SecurityCategory.CONFIGURATION,
                "MEDIUM",
                "Detects server.error.include-stacktrace / include-message / include-binding-errors set to 'always', which exposes internal details in error responses.",
                "Use 'never' (or 'on_param') for include-stacktrace and keep include-message / include-binding-errors at 'never' in production to avoid information disclosure.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.error-handling"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        if ("always".equalsIgnoreCase(context.firstProperty("server.error.include-stacktrace"))) {
            details.add("server.error.include-stacktrace=always exposes stack traces in error responses.");
        }
        if ("always".equalsIgnoreCase(context.firstProperty("server.error.include-message"))) {
            details.add("server.error.include-message=always exposes exception messages in error responses.");
        }
        if ("always".equalsIgnoreCase(context.firstProperty("server.error.include-binding-errors"))) {
            details.add(
                    "server.error.include-binding-errors=always exposes binding/validation details in error responses.");
        }
        return violation(details);
    }
}

final class HttpsEnforcementRule extends AbstractSecurityRule {

    HttpsEnforcementRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-CONFIG-006",
                        "Application should enforce HTTPS in production",
                        SecurityCategory.CONFIGURATION,
                        "LOW",
                        "Notes that, while a production profile is active, the application configures no server-side TLS, HTTPS redirect (requiresChannel/ChannelProcessingFilter), or forwarded-header strategy indicating TLS is terminated upstream.",
                        "Enforce HTTPS via server.ssl.* (or requiresChannel().requiresSecure()), or set server.forward-headers-strategy=framework when TLS is terminated by a proxy so secure cookies and redirects behave correctly.",
                        "https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.embedded-container.configure-ssl"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.isProductionProfileActive() || context.isTlsConfigured()) {
            return pass();
        }
        return violation(
                List.of(
                        "No server-side TLS, HTTPS redirect, or forwarded-header strategy is configured while a production profile is active."));
    }
}

final class HardcodedSecretPropertyRule extends AbstractSecurityRule {

    HardcodedSecretPropertyRule() {
        super(new SecurityRuleDefinition(
                "SEC-CONFIG-007",
                "Configuration should not hold literal secret values",
                SecurityCategory.CONFIGURATION,
                "HIGH",
                "Detects configuration property names that look like they hold a credential (password, secret, token, api-key, client-secret, private-key) whose value is a literal, unresolved string rather than an externalized reference. Keys ending in a lifetime/shape suffix (-expiration, -expiry, -expires, -ttl, -timeout, -duration, -validity, -max-age, -refresh-interval) are excluded because they configure how long a token lives, not its value (e.g. jwt.token.expiration=3600). System properties, the OS environment, the random-value source, and mounted config-tree secrets are not scanned because they are already externalized. Property values are never read into the finding; only the offending property name is reported. This remains a name-based heuristic, not a secret-shape check, so review each finding -- it may still name a non-secret value (e.g. oauth.token.type=Bearer).",
                "Move the literal value out of the configuration file into an environment variable, a secrets manager, or a mounted config-tree secret, and reference it with ${ENV_VAR_NAME} instead of a hardcoded literal.",
                "https://docs.spring.io/spring-boot/reference/features/external-config.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        Set<String> keys = context.suspectedHardcodedSecretKeys();
        if (keys.isEmpty()) {
            return pass();
        }
        List<String> details = keys.stream()
                .sorted()
                .map(key -> "Property '" + key
                        + "' appears to hold a hardcoded secret value in the application configuration.")
                .toList();
        return violation(details);
    }
}

final class StrictHttpFirewallWeakenedRule extends AbstractSecurityRule {

    StrictHttpFirewallWeakenedRule() {
        super(new SecurityRuleDefinition(
                "SEC-CONFIG-008",
                "StrictHttpFirewall should not relax its default URL protections",
                SecurityCategory.CONFIGURATION,
                "HIGH",
                "Detects a custom StrictHttpFirewall bean that has re-allowed one or more normally-blocked encoded/raw URL tokens (URL-encoded slash, backslash, semicolon, or double slash), which can enable authorization-matcher bypass or path-traversal style attacks.",
                "Keep the StrictHttpFirewall defaults; only relax a specific token (e.g. setAllowUrlEncodedSlash(true)) after verifying every downstream matcher and handler safely tolerates it.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/firewall.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (context.strictHttpFirewallWeakened()) {
            return violation(
                    List.of(
                            "A StrictHttpFirewall bean re-allows one or more normally-blocked URL tokens (encoded slash, backslash, semicolon, or double slash)."));
        }
        return pass();
    }
}

final class SecurityDebugLoggingProductionRule extends AbstractSecurityRule {

    SecurityDebugLoggingProductionRule() {
        super(new SecurityRuleDefinition(
                "SEC-CONFIG-009",
                "Spring Security framework logging should not run at DEBUG/TRACE in production",
                SecurityCategory.CONFIGURATION,
                "MEDIUM",
                "Detects logging.level.org.springframework.security=DEBUG (or TRACE) while a production profile is active, which logs filter chain decisions, header values, and request/response details. Distinct from spring.security.debug (SEC-CONFIG-001), Spring Security's own dedicated debug filter.",
                "Keep org.springframework.security logging at INFO or WARN in production; reserve DEBUG/TRACE for local troubleshooting.",
                "https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.log-levels"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        String level = context.firstProperty("logging.level.org.springframework.security");
        if (level == null) {
            return pass();
        }
        String normalized = level.trim().toUpperCase(Locale.ROOT);
        if ((normalized.equals("DEBUG") || normalized.equals("TRACE")) && context.isProductionProfileActive()) {
            return violation(List.of("logging.level.org.springframework.security=" + normalized
                    + " while a production profile is active."));
        }
        return pass();
    }
}
