package io.github.jdubois.bootui.autoconfigure.securityadvisor;

import io.github.jdubois.bootui.autoconfigure.securityadvisor.SecurityAdvisorModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.securityadvisor.SecurityAdvisorModel.FilterChainModel;
import io.github.jdubois.bootui.autoconfigure.securityadvisor.SecurityAdvisorModel.PasswordEncoderModel;
import io.github.jdubois.bootui.core.dto.SecurityAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

abstract class AbstractSecurityAdvisorRule implements SecurityAdvisorRule {

    private final SecurityAdvisorRuleDefinition definition;

    AbstractSecurityAdvisorRule(SecurityAdvisorRuleDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final SecurityAdvisorRuleDefinition definition() {
        return definition;
    }

    abstract SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context);

    @Override
    public final SecurityAdvisorRuleResultDto evaluate(SecurityAdvisorContext context) {
        try {
            return evaluateRule(context);
        } catch (RuntimeException | LinkageError ex) {
            return SecurityAdvisorRuleSupport.error(definition, "Rule could not be evaluated: " + ex.getMessage());
        }
    }

    SecurityAdvisorRuleResultDto pass() {
        return SecurityAdvisorRuleSupport.pass(definition);
    }

    SecurityAdvisorRuleResultDto skipped(String reason) {
        return SecurityAdvisorRuleSupport.skipped(definition, reason);
    }

    SecurityAdvisorRuleResultDto violation(List<String> details) {
        return details.isEmpty() ? pass() : SecurityAdvisorRuleSupport.violation(definition, details);
    }
}

// ---------------------------------------------------------------------------
// Authentication & passwords
// ---------------------------------------------------------------------------

final class NoOpPasswordEncoderRule extends AbstractSecurityAdvisorRule {

    NoOpPasswordEncoderRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-AUTH-001",
                "Password encoder must not store credentials in plain text",
                SecurityAdvisorCategory.AUTHENTICATION,
                "HIGH",
                "Detects a NoOpPasswordEncoder bean, which keeps passwords in clear text.",
                "Use a delegating encoder (PasswordEncoderFactories.createDelegatingPasswordEncoder()) backed by bcrypt, Argon2, or PBKDF2.",
                "https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (String type : context.passwordEncoderTypes()) {
            if (type.contains("NoOpPasswordEncoder")) {
                details.add("PasswordEncoder bean " + type + " stores passwords without hashing.");
            }
        }
        return violation(details);
    }
}

final class WeakPasswordEncoderRule extends AbstractSecurityAdvisorRule {

    private static final List<String> WEAK = List.of(
            "StandardPasswordEncoder",
            "MessageDigestPasswordEncoder",
            "Md4PasswordEncoder",
            "Md5",
            "ShaPasswordEncoder",
            "LdapShaPasswordEncoder");

    WeakPasswordEncoderRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-AUTH-002",
                "Password encoder should not use a weak or legacy algorithm",
                SecurityAdvisorCategory.AUTHENTICATION,
                "HIGH",
                "Detects deprecated encoders based on MD5/SHA or the legacy StandardPasswordEncoder.",
                "Migrate to bcrypt, Argon2, or PBKDF2 via a DelegatingPasswordEncoder so hashes upgrade over time.",
                "https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

final class MissingPasswordEncoderRule extends AbstractSecurityAdvisorRule {

    MissingPasswordEncoderRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-AUTH-003",
                "Form or HTTP Basic login should define a PasswordEncoder",
                SecurityAdvisorCategory.AUTHENTICATION,
                "MEDIUM",
                "Detects form-login or HTTP Basic chains with no PasswordEncoder bean exposed to the context.",
                "Declare a PasswordEncoder bean (a delegating encoder) so stored credentials are hashed and verified consistently.",
                "https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/index.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

final class DefaultInMemoryUserRule extends AbstractSecurityAdvisorRule {

    DefaultInMemoryUserRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-AUTH-004",
                "Do not rely on the generated spring.security.user account",
                SecurityAdvisorCategory.AUTHENTICATION,
                "MEDIUM",
                "Detects credentials configured through spring.security.user.name / spring.security.user.password.",
                "Replace the single property-based user with a real UserDetailsService or identity provider for anything beyond local demos.",
                "https://docs.spring.io/spring-boot/reference/web/spring-security.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

final class DefaultLoginPageProductionRule extends AbstractSecurityAdvisorRule {

    DefaultLoginPageProductionRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-AUTH-005",
                "Avoid the auto-generated login page in production",
                SecurityAdvisorCategory.AUTHENTICATION,
                "LOW",
                "Detects the framework's DefaultLoginPageGeneratingFilter while a production profile is active.",
                "Provide a custom login page via formLogin().loginPage(...) for production so the unstyled default page (which advertises the Spring Security stack) is not served.",
                "https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

final class WeakBcryptStrengthRule extends AbstractSecurityAdvisorRule {

    private static final int RECOMMENDED_MINIMUM_STRENGTH = 10;

    WeakBcryptStrengthRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-AUTH-006",
                "BCrypt password encoder should use an adequate work factor",
                SecurityAdvisorCategory.AUTHENTICATION,
                "LOW",
                "Detects a BCryptPasswordEncoder bean configured with a strength below the recommended minimum of 10 (the framework default).",
                "Use a BCrypt strength of at least 10 (the default) so password hashing stays computationally expensive; raise it as hardware improves, or migrate to Argon2/PBKDF2.",
                "https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

// ---------------------------------------------------------------------------
// Authorization
// ---------------------------------------------------------------------------

final class MissingAuthorizationFilterRule extends AbstractSecurityAdvisorRule {

    MissingAuthorizationFilterRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-AUTHZ-001",
                "Every filter chain should enforce authorization",
                SecurityAdvisorCategory.AUTHORIZATION,
                "HIGH",
                "Detects a SecurityFilterChain that installs no AuthorizationFilter, so matched requests are unguarded.",
                "Add authorizeHttpRequests(...) with at least anyRequest().authenticated() (or an explicit denyAll) to the chain.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (!chain.hasAuthorizationFilter()) {
                details.add(chain.describe() + " installs no authorization filter.");
            }
        }
        return violation(details);
    }
}

final class PermitAllCatchAllRule extends AbstractSecurityAdvisorRule {

    PermitAllCatchAllRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-AUTHZ-002",
                "Avoid blanket permitAll authorization",
                SecurityAdvisorCategory.AUTHORIZATION,
                "HIGH",
                "Detects a chain whose authorization grants every request to anonymous callers (permitAll catch-all).",
                "Restrict sensitive paths and finish with anyRequest().authenticated(); keep permitAll only for genuinely public endpoints.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (Boolean.TRUE.equals(chain.permitsAllAnonymous()) && chain.hasAuthenticationFilter()) {
                details.add(chain.describe()
                        + " permits every request anonymously even though it configures authentication.");
            }
        }
        return violation(details);
    }
}

final class EffectivelyDisabledSecurityRule extends AbstractSecurityAdvisorRule {

    EffectivelyDisabledSecurityRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-AUTHZ-003",
                "Application security should not be effectively disabled",
                SecurityAdvisorCategory.AUTHORIZATION,
                "HIGH",
                "Detects when every filter chain permits all requests anonymously and no chain requires authentication.",
                "Define authorization rules that require authentication for non-public endpoints instead of leaving the app fully open.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        List<FilterChainModel> chains = context.chains();
        if (chains.isEmpty()) {
            return pass();
        }
        boolean anyDeterminable = chains.stream().anyMatch(chain -> chain.permitsAllAnonymous() != null);
        if (!anyDeterminable) {
            return skipped("Authorization decisions could not be simulated for any chain.");
        }
        boolean allOpen = chains.stream().allMatch(chain -> Boolean.TRUE.equals(chain.permitsAllAnonymous()));
        boolean anyAuthentication = chains.stream().anyMatch(FilterChainModel::hasAuthenticationFilter);
        if (allOpen && !anyAuthentication) {
            return violation(List.of("All " + chains.size()
                    + " security filter chains permit every request anonymously with no authentication mechanism."));
        }
        return pass();
    }
}

final class CatchAllChainOrderingRule extends AbstractSecurityAdvisorRule {

    CatchAllChainOrderingRule() {
        super(
                new SecurityAdvisorRuleDefinition(
                        "SEC-AUTHZ-004",
                        "Catch-all filter chains should be ordered last",
                        SecurityAdvisorCategory.AUTHORIZATION,
                        "INFO",
                        "Detects a chain that matches any request placed before more specific chains, which then never run.",
                        "Give earlier chains an explicit securityMatcher and keep the catch-all (any request) chain last by @Order.",
                        "https://docs.spring.io/spring-security/reference/servlet/configuration/java.html#_multiple_httpsecurity_instances"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

// ---------------------------------------------------------------------------
// CSRF
// ---------------------------------------------------------------------------

final class CsrfDisabledStatefulRule extends AbstractSecurityAdvisorRule {

    CsrfDisabledStatefulRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-CSRF-001",
                "CSRF protection should stay on for session-based chains",
                SecurityAdvisorCategory.CSRF,
                "HIGH",
                "Detects a stateful (session/remember-me) chain with no CsrfFilter installed.",
                "Keep CSRF enabled for browser, cookie, or session authenticated chains; only disable it for stateless token APIs.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.isStateful() && !chain.hasFilter("CsrfFilter")) {
                details.add(chain.describe() + " manages sessions but does not install a CsrfFilter.");
            }
        }
        return violation(details);
    }
}

final class CsrfGloballyDisabledRule extends AbstractSecurityAdvisorRule {

    CsrfGloballyDisabledRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-CSRF-002",
                "CSRF should not be disabled without stateless authentication",
                SecurityAdvisorCategory.CSRF,
                "MEDIUM",
                "Detects chains with no CsrfFilter and no bearer-token (stateless) authentication to justify the removal.",
                "Disable CSRF only when the chain is stateless (e.g. bearer tokens); otherwise keep the CsrfFilter.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

final class SessionFixationRule extends AbstractSecurityAdvisorRule {

    SessionFixationRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-SESSION-001",
                "Session fixation protection should be enabled",
                SecurityAdvisorCategory.SESSION,
                "HIGH",
                "Detects a session-management strategy configured to skip changing the session id on authentication.",
                "Use the default changeSessionId (or migrateSession) session-fixation strategy instead of none().",
                "https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

final class SessionCookieSecureRule extends AbstractSecurityAdvisorRule {

    SessionCookieSecureRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-SESSION-002",
                "Session cookie should set the Secure flag",
                SecurityAdvisorCategory.SESSION,
                "MEDIUM",
                "Detects server.servlet.session.cookie.secure=false, or unset while a production profile is active.",
                "Set server.servlet.session.cookie.secure=true so the session cookie is only sent over HTTPS.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

final class SessionCookieHttpOnlyRule extends AbstractSecurityAdvisorRule {

    SessionCookieHttpOnlyRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-SESSION-003",
                "Session cookie should set the HttpOnly flag",
                SecurityAdvisorCategory.SESSION,
                "MEDIUM",
                "Detects server.servlet.session.cookie.http-only=false, exposing the session cookie to JavaScript.",
                "Keep server.servlet.session.cookie.http-only=true to mitigate cookie theft via XSS.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        if (context.isPropertyFalse("server.servlet.session.cookie.http-only")) {
            return violation(List.of("server.servlet.session.cookie.http-only is explicitly false."));
        }
        return pass();
    }
}

final class SessionCookieSameSiteRule extends AbstractSecurityAdvisorRule {

    SessionCookieSameSiteRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-SESSION-004",
                "Session cookie should declare a SameSite policy",
                SecurityAdvisorCategory.SESSION,
                "LOW",
                "Detects that server.servlet.session.cookie.same-site is unset, leaving the policy to the container default.",
                "Set server.servlet.session.cookie.same-site=Lax (or Strict) to reduce cross-site request exposure.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

final class SessionTimeoutRule extends AbstractSecurityAdvisorRule {

    SessionTimeoutRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-SESSION-005",
                "An explicit session timeout should be configured",
                SecurityAdvisorCategory.SESSION,
                "INFO",
                "Detects that server.servlet.session.timeout is unset, leaving the container's default timeout.",
                "Set server.servlet.session.timeout to a bounded value appropriate for the application's risk profile.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

final class BearerTokenStatefulRule extends AbstractSecurityAdvisorRule {

    BearerTokenStatefulRule() {
        super(
                new SecurityAdvisorRuleDefinition(
                        "SEC-SESSION-006",
                        "Bearer token authentication chains should be stateless",
                        SecurityAdvisorCategory.SESSION,
                        "HIGH",
                        "Detects a chain with both a Bearer token filter (stateless) and session management filters (stateful).",
                        "Configure sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS) to avoid creating HTTP sessions for REST API calls.",
                        "https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-stateless"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.hasFilterContaining("BearerTokenAuthenticationFilter") && chain.isStateful()) {
                details.add(chain.describe() + " accepts Bearer tokens but also maintains stateful sessions.");
            }
        }
        return violation(details);
    }
}

final class ConcurrentSessionControlRule extends AbstractSecurityAdvisorRule {

    ConcurrentSessionControlRule() {
        super(
                new SecurityAdvisorRuleDefinition(
                        "SEC-SESSION-007",
                        "Consider configuring concurrent session control",
                        SecurityAdvisorCategory.SESSION,
                        "INFO",
                        "Detects an interactive form-login chain that maintains sessions but installs no ConcurrentSessionFilter (no maximumSessions limit).",
                        "Set sessionManagement().maximumSessions(n) so a stolen or shared credential cannot open unlimited concurrent sessions.",
                        "https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html#ns-concurrent-sessions"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

// ---------------------------------------------------------------------------
// Transport & security headers
// ---------------------------------------------------------------------------

final class HstsHeaderRule extends AbstractSecurityAdvisorRule {

    HstsHeaderRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-HEAD-001",
                "HTTP Strict Transport Security should be emitted",
                SecurityAdvisorCategory.HEADERS,
                "MEDIUM",
                "Detects chains whose header writers do not include an HSTS writer.",
                "Keep the default HstsHeaderWriter (served over HTTPS) so browsers pin TLS for the domain.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-hsts"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.headerWriterFilterPresent() && !chain.hasHeaderWriterContaining("Hsts")) {
                details.add(chain.describe() + " does not emit a Strict-Transport-Security header.");
            }
        }
        return violation(details);
    }
}

final class FrameOptionsRule extends AbstractSecurityAdvisorRule {

    FrameOptionsRule() {
        super(
                new SecurityAdvisorRuleDefinition(
                        "SEC-HEAD-002",
                        "X-Frame-Options (clickjacking protection) should stay enabled",
                        SecurityAdvisorCategory.HEADERS,
                        "HIGH",
                        "Detects chains whose header writers omit X-Frame-Options / frame-ancestors protection.",
                        "Keep the default XFrameOptionsHeaderWriter (DENY/SAMEORIGIN) or a frame-ancestors CSP instead of disabling frame options globally.",
                        "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-frame-options"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

final class ContentSecurityPolicyRule extends AbstractSecurityAdvisorRule {

    ContentSecurityPolicyRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-HEAD-003",
                "A Content-Security-Policy should be defined",
                SecurityAdvisorCategory.HEADERS,
                "LOW",
                "Detects chains that serve no Content-Security-Policy header.",
                "Add a ContentSecurityPolicyHeaderWriter with a tailored policy to mitigate XSS and data injection.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-csp"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.headerWriterFilterPresent() && !chain.hasHeaderWriterContaining("ContentSecurityPolicy")) {
                details.add(chain.describe() + " does not define a Content-Security-Policy.");
            }
        }
        return violation(details);
    }
}

final class ContentTypeOptionsRule extends AbstractSecurityAdvisorRule {

    ContentTypeOptionsRule() {
        super(
                new SecurityAdvisorRuleDefinition(
                        "SEC-HEAD-004",
                        "X-Content-Type-Options should stay enabled",
                        SecurityAdvisorCategory.HEADERS,
                        "LOW",
                        "Detects chains whose header writers omit X-Content-Type-Options: nosniff.",
                        "Keep the default XContentTypeOptionsHeaderWriter so browsers do not MIME-sniff responses.",
                        "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-content-type-options"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.headerWriterFilterPresent() && !chain.hasHeaderWriterContaining("XContentTypeOptions")) {
                details.add(chain.describe() + " does not emit X-Content-Type-Options: nosniff.");
            }
        }
        return violation(details);
    }
}

final class ReferrerPolicyHeaderRule extends AbstractSecurityAdvisorRule {

    ReferrerPolicyHeaderRule() {
        super(
                new SecurityAdvisorRuleDefinition(
                        "SEC-HEAD-005",
                        "A Referrer-Policy header should be emitted",
                        SecurityAdvisorCategory.HEADERS,
                        "LOW",
                        "Detects chains whose header writers do not emit a Referrer-Policy header (not sent by default).",
                        "Add a ReferrerPolicyHeaderWriter via headers().referrerPolicy(...) with a policy such as strict-origin-when-cross-origin to limit referrer leakage.",
                        "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-referrer"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (chain.headerWriterFilterPresent() && !chain.hasHeaderWriterContaining("ReferrerPolicy")) {
                details.add(chain.describe() + " does not emit a Referrer-Policy header.");
            }
        }
        return violation(details);
    }
}

final class PermissionsPolicyHeaderRule extends AbstractSecurityAdvisorRule {

    PermissionsPolicyHeaderRule() {
        super(
                new SecurityAdvisorRuleDefinition(
                        "SEC-HEAD-006",
                        "A Permissions-Policy header should be considered",
                        SecurityAdvisorCategory.HEADERS,
                        "INFO",
                        "Detects chains whose header writers do not emit a Permissions-Policy header (not sent by default).",
                        "Add a PermissionsPolicyHeaderWriter via headers().permissionsPolicyHeader(...) to restrict powerful browser features the application does not use.",
                        "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-permissions-policy"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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
// CORS
// ---------------------------------------------------------------------------

final class CorsWildcardOriginRule extends AbstractSecurityAdvisorRule {

    CorsWildcardOriginRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-CORS-001",
                "CORS should not allow all origins",
                SecurityAdvisorCategory.CORS,
                "HIGH",
                "Detects a CorsConfiguration that allows the * wildcard origin.",
                "Enumerate the exact trusted origins instead of \"*\"; use allowedOriginPatterns only for tightly-scoped patterns.",
                "https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (CorsConfigModel cors : context.corsConfigs()) {
            if (cors.allowsWildcardOrigin() && !cors.allowsCredentials()) {
                details.add(cors.describe());
            }
        }
        return violation(details);
    }
}

final class CorsWildcardWithCredentialsRule extends AbstractSecurityAdvisorRule {

    CorsWildcardWithCredentialsRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-CORS-002",
                "CORS must not combine wildcard origins with credentials",
                SecurityAdvisorCategory.CORS,
                "HIGH",
                "Detects a CorsConfiguration that allows the * origin together with allowCredentials=true.",
                "Never pair allowCredentials(true) with a wildcard origin; list explicit origins so cookies and auth headers are not leaked cross-site.",
                "https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (CorsConfigModel cors : context.corsConfigs()) {
            if (cors.allowsWildcardOrigin() && cors.allowsCredentials()) {
                details.add(cors.describe() + " with allowCredentials=true.");
            }
        }
        return violation(details);
    }
}

final class CorsNotInSecurityChainRule extends AbstractSecurityAdvisorRule {

    CorsNotInSecurityChainRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-CORS-003",
                "CORS should be wired through the security filter chain",
                SecurityAdvisorCategory.CORS,
                "INFO",
                "Detects a CorsConfigurationSource bean while no filter chain installs a CorsFilter.",
                "Enable .cors(...) on the HttpSecurity so preflight handling is consistent with the security chain rather than MVC-only.",
                "https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

final class CorsWildcardMethodsHeadersRule extends AbstractSecurityAdvisorRule {

    CorsWildcardMethodsHeadersRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-CORS-004",
                "CORS should not allow all methods or headers with credentials",
                SecurityAdvisorCategory.CORS,
                "MEDIUM",
                "Detects a CorsConfiguration that allows the * wildcard for methods or headers together with allowCredentials=true.",
                "Enumerate the exact methods and headers the API needs instead of \"*\" when credentials are allowed, so cross-site callers cannot send arbitrary authenticated requests.",
                "https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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
        return violation(details);
    }
}

// ---------------------------------------------------------------------------
// Method security
// ---------------------------------------------------------------------------

final class MethodSecurityAnnotationsIgnoredRule extends AbstractSecurityAdvisorRule {

    MethodSecurityAnnotationsIgnoredRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-METHOD-001",
                "Method security annotations require method security to be enabled",
                SecurityAdvisorCategory.METHOD_SECURITY,
                "HIGH",
                "Detects @PreAuthorize/@Secured usage while no method-security interceptors are registered, so the annotations are ignored.",
                "Add @EnableMethodSecurity to a configuration class so the security annotations are actually enforced.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        if (context.methodSecurityAnnotationsPresent() && !context.methodSecurityEnabled()) {
            return violation(
                    List.of(
                            "Security method annotations were found but method security is not enabled; the annotations are silently ignored."));
        }
        return pass();
    }
}

final class LegacyGlobalMethodSecurityRule extends AbstractSecurityAdvisorRule {

    LegacyGlobalMethodSecurityRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-METHOD-002",
                "Replace @EnableGlobalMethodSecurity with @EnableMethodSecurity",
                SecurityAdvisorCategory.METHOD_SECURITY,
                "LOW",
                "Detects the legacy @EnableGlobalMethodSecurity configuration removed/deprecated in Spring Security 6+.",
                "Migrate to @EnableMethodSecurity, which enables @PreAuthorize/@PostAuthorize by default and uses the modern AuthorizationManager API.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        if (context.globalMethodSecurityLegacyPresent()) {
            return violation(List.of("@EnableGlobalMethodSecurity is in use; migrate to @EnableMethodSecurity."));
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Actuator exposure
// ---------------------------------------------------------------------------

final class ActuatorWildcardExposureRule extends AbstractSecurityAdvisorRule {

    ActuatorWildcardExposureRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-ACT-001",
                "Actuator endpoints should not all be web-exposed",
                SecurityAdvisorCategory.ACTUATOR,
                "HIGH",
                "Detects management.endpoints.web.exposure.include=* exposing every actuator endpoint over HTTP.",
                "Expose only the endpoints you need (e.g. health, info) and secure the rest behind authentication.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        String include = context.firstHostProperty("management.endpoints.web.exposure.include");
        if (include != null && include.trim().equals("*")) {
            return violation(List.of("management.endpoints.web.exposure.include=* exposes all actuator endpoints."));
        }
        return pass();
    }
}

final class ActuatorSensitiveExposureRule extends AbstractSecurityAdvisorRule {

    private static final List<String> SENSITIVE =
            List.of("env", "beans", "configprops", "heapdump", "threaddump", "shutdown", "loggers", "mappings");

    ActuatorSensitiveExposureRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-ACT-002",
                "Sensitive actuator endpoints should not be exposed",
                SecurityAdvisorCategory.ACTUATOR,
                "HIGH",
                "Detects high-value actuator endpoints (env, beans, configprops, heapdump, threaddump, shutdown) in the web exposure list.",
                "Remove sensitive endpoints from management.endpoints.web.exposure.include or protect them with authentication.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        String include = context.firstHostProperty("management.endpoints.web.exposure.include");
        if (include == null) {
            return pass();
        }
        String normalized = include.toLowerCase(Locale.ROOT);
        if (normalized.trim().equals("*")) {
            return pass(); // covered by SEC-ACT-001
        }
        List<String> tokens = List.of(normalized.split(","));
        List<String> details = new ArrayList<>();
        for (String token : tokens) {
            String value = token.trim();
            if (SENSITIVE.contains(value)) {
                details.add("Actuator endpoint '" + value + "' is web-exposed.");
            }
        }
        return violation(details);
    }
}

final class ActuatorUnprotectedRule extends AbstractSecurityAdvisorRule {

    ActuatorUnprotectedRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-ACT-003",
                "Exposed actuator endpoints should be protected by a security chain",
                SecurityAdvisorCategory.ACTUATOR,
                "MEDIUM",
                "Detects web-exposed actuator endpoints (beyond health/info) when no filter chain references /actuator.",
                "Add a SecurityFilterChain with a securityMatcher for the actuator base path that requires authentication/authorization.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.security"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        String include = context.firstHostProperty("management.endpoints.web.exposure.include");
        if (include == null) {
            return pass();
        }
        String normalized = include.toLowerCase(Locale.ROOT).trim();
        boolean exposesBeyondBasics = normalized.equals("*")
                || List.of(normalized.split(",")).stream()
                        .map(String::trim)
                        .anyMatch(token -> !token.isEmpty() && !token.equals("health") && !token.equals("info"));
        if (!exposesBeyondBasics) {
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

final class HealthDetailsExposureRule extends AbstractSecurityAdvisorRule {

    HealthDetailsExposureRule() {
        super(
                new SecurityAdvisorRuleDefinition(
                        "SEC-ACT-004",
                        "Actuator health details should not be exposed unconditionally",
                        SecurityAdvisorCategory.ACTUATOR,
                        "HIGH",
                        "Detects management.endpoint.health.show-details=always, which leaks infrastructure details to anonymous callers.",
                        "Change management.endpoint.health.show-details to 'when-authorized' (the default) or ensure the /health endpoint is strictly authenticated.",
                        "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health.show-details"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        String showDetails = context.firstHostProperty("management.endpoint.health.show-details");
        if ("always".equalsIgnoreCase(showDetails)) {
            return violation(List.of("management.endpoint.health.show-details is set to 'always'."));
        }
        return pass();
    }
}

final class ShutdownEndpointEnabledRule extends AbstractSecurityAdvisorRule {

    ShutdownEndpointEnabledRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-ACT-005",
                "The actuator shutdown endpoint should not be enabled",
                SecurityAdvisorCategory.ACTUATOR,
                "HIGH",
                "Detects management.endpoint.shutdown.enabled=true, which lets a caller stop the application (denial of service) if reachable.",
                "Keep the shutdown endpoint disabled (the default); if you truly need it, restrict it to a secured management port behind strict authentication.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.enabling"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        if (context.isPropertyTrue("management.endpoint.shutdown.enabled")) {
            return violation(List.of("management.endpoint.shutdown.enabled=true exposes application shutdown."));
        }
        return pass();
    }
}

final class ManagementPortIsolationRule extends AbstractSecurityAdvisorRule {

    ManagementPortIsolationRule() {
        super(
                new SecurityAdvisorRuleDefinition(
                        "SEC-ACT-006",
                        "Sensitive actuator endpoints should use an isolated management port",
                        SecurityAdvisorCategory.ACTUATOR,
                        "INFO",
                        "Notes that sensitive actuator endpoints are exposed on the main application port because management.server.port is unset.",
                        "Set management.server.port to a separate, network-restricted port so actuator endpoints are not reachable on the public application port.",
                        "https://docs.spring.io/spring-boot/reference/actuator/monitoring.html#actuator.monitoring.customizing-management-server-port"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        String include = context.firstHostProperty("management.endpoints.web.exposure.include");
        if (include == null) {
            return pass();
        }
        String normalized = include.toLowerCase(Locale.ROOT).trim();
        boolean exposesBeyondBasics = normalized.equals("*")
                || List.of(normalized.split(",")).stream()
                        .map(String::trim)
                        .anyMatch(token -> !token.isEmpty() && !token.equals("health") && !token.equals("info"));
        if (!exposesBeyondBasics) {
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
// OAuth2 / JWT resource server
// ---------------------------------------------------------------------------

final class ResourceServerValidationRule extends AbstractSecurityAdvisorRule {

    ResourceServerValidationRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-OAUTH-001",
                "JWT resource server must validate tokens via issuer or JWK set",
                SecurityAdvisorCategory.OAUTH2,
                "HIGH",
                "Detects a bearer-token resource server with no issuer-uri, jwk-set-uri, public key, or JwtDecoder bean configured.",
                "Configure spring.security.oauth2.resourceserver.jwt.issuer-uri (or jwk-set-uri / a JwtDecoder bean) so signatures and the issuer are verified.",
                "https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

final class JwtAudienceValidationRule extends AbstractSecurityAdvisorRule {

    JwtAudienceValidationRule() {
        super(
                new SecurityAdvisorRuleDefinition(
                        "SEC-OAUTH-002",
                        "Validate the JWT audience claim",
                        SecurityAdvisorCategory.OAUTH2,
                        "MEDIUM",
                        "Notes that issuer-based resource servers do not validate the aud claim unless a custom validator is added.",
                        "Add an audience OAuth2TokenValidator to the JwtDecoder so tokens minted for other resource servers are rejected.",
                        "https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-validation"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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
        return violation(
                List.of(
                        "Resource server uses issuer/JWK validation; confirm a custom audience (aud) validator is registered."));
    }
}

final class JwtStaticKeyRule extends AbstractSecurityAdvisorRule {

    JwtStaticKeyRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-OAUTH-003",
                "Prefer JWK rotation over a static signing key",
                SecurityAdvisorCategory.OAUTH2,
                "MEDIUM",
                "Detects a resource server pinned to a static public key (public-key-location) with no issuer or JWK set URI.",
                "Use a jwk-set-uri / issuer-uri so signing keys can rotate, rather than a single embedded public key.",
                "https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

final class SecurityDebugRule extends AbstractSecurityAdvisorRule {

    SecurityDebugRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-CONFIG-001",
                "Spring Security debug mode should be off",
                SecurityAdvisorCategory.CONFIGURATION,
                "MEDIUM",
                "Detects spring.security.debug=true (or a DebugFilter), which logs filter chains and request details.",
                "Disable security debug mode outside local development; it leaks configuration and request information.",
                "https://docs.spring.io/spring-security/reference/servlet/configuration/java.html"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        boolean debugFilter = context.chains().stream().anyMatch(chain -> chain.hasFilterContaining("DebugFilter"));
        if (context.isPropertyTrue("spring.security.debug") || debugFilter) {
            String suffix = context.isProductionProfileActive() ? " while a production profile is active" : "";
            return violation(List.of("Spring Security debug mode is enabled" + suffix + "."));
        }
        return pass();
    }
}

final class H2ConsoleFrameOptionsRule extends AbstractSecurityAdvisorRule {

    H2ConsoleFrameOptionsRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-CONFIG-002",
                "H2 console should not be enabled in production",
                SecurityAdvisorCategory.CONFIGURATION,
                "HIGH",
                "Detects spring.h2.console.enabled=true while a production profile is active; the console needs frame options relaxed and exposes the database.",
                "Disable the H2 console in production (keep it to dev profiles) so frame-options are not loosened and the database UI is not reachable.",
                "https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.h2-web-console"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        if (context.isPropertyTrue("spring.h2.console.enabled") && context.isProductionProfileActive()) {
            return violation(List.of("spring.h2.console.enabled=true while a production profile is active."));
        }
        return pass();
    }
}

final class WebSecurityConfigurerAdapterRule extends AbstractSecurityAdvisorRule {

    WebSecurityConfigurerAdapterRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-CONFIG-003",
                "Replace WebSecurityConfigurerAdapter with a component-based configuration",
                SecurityAdvisorCategory.CONFIGURATION,
                "LOW",
                "Detects a WebSecurityConfigurerAdapter bean; the class was removed in Spring Security 6.",
                "Expose SecurityFilterChain and WebSecurityCustomizer beans instead of extending WebSecurityConfigurerAdapter.",
                "https://spring.io/blog/2022/02/21/spring-security-without-the-websecurityconfigureradapter"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        if (context.webSecurityConfigurerAdapterPresent()) {
            return violation(List.of("A WebSecurityConfigurerAdapter is still in use."));
        }
        return pass();
    }
}

final class WebIgnoringRule extends AbstractSecurityAdvisorRule {

    WebIgnoringRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-CONFIG-004",
                "Avoid bypassing the filter chain with web.ignoring()",
                SecurityAdvisorCategory.CONFIGURATION,
                "MEDIUM",
                "Notes that web.ignoring() paths skip Spring Security entirely (including header writers) and cannot be introspected from registered chains.",
                "Prefer permitAll() inside authorizeHttpRequests for non-static paths so security headers and context still apply; reserve web.ignoring() for truly static resources.",
                "https://docs.spring.io/spring-security/reference/servlet/configuration/java.html#jc-httpsecurity"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        return skipped(
                "web.ignoring() paths bypass the filter chain and are not visible to the advisor; review them manually.");
    }
}

final class ErrorResponseDisclosureRule extends AbstractSecurityAdvisorRule {

    ErrorResponseDisclosureRule() {
        super(new SecurityAdvisorRuleDefinition(
                "SEC-CONFIG-005",
                "Error responses should not leak stack traces or internal messages",
                SecurityAdvisorCategory.CONFIGURATION,
                "MEDIUM",
                "Detects server.error.include-stacktrace / include-message / include-binding-errors set to 'always', which exposes internal details in error responses.",
                "Use 'never' (or 'on_param') for include-stacktrace and keep include-message / include-binding-errors at 'never' in production to avoid information disclosure.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.error-handling"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
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

final class HttpsEnforcementRule extends AbstractSecurityAdvisorRule {

    HttpsEnforcementRule() {
        super(
                new SecurityAdvisorRuleDefinition(
                        "SEC-CONFIG-006",
                        "Application should enforce HTTPS in production",
                        SecurityAdvisorCategory.CONFIGURATION,
                        "LOW",
                        "Notes that, while a production profile is active, the application configures no server-side TLS, HTTPS redirect (requiresChannel/ChannelProcessingFilter), or forwarded-header strategy indicating TLS is terminated upstream.",
                        "Enforce HTTPS via server.ssl.* (or requiresChannel().requiresSecure()), or set server.forward-headers-strategy=framework when TLS is terminated by a proxy so secure cookies and redirects behave correctly.",
                        "https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.embedded-container.configure-ssl"));
    }

    @Override
    SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
        if (!context.isProductionProfileActive() || isTlsHandled(context)) {
            return pass();
        }
        return violation(
                List.of(
                        "No server-side TLS, HTTPS redirect, or forwarded-header strategy is configured while a production profile is active."));
    }

    private boolean isTlsHandled(SecurityAdvisorContext context) {
        if (context.isPropertyTrue("server.ssl.enabled")
                || context.firstProperty("server.ssl.key-store") != null
                || context.firstProperty("server.ssl.bundle") != null
                || context.firstProperty("server.ssl.certificate") != null) {
            return true;
        }
        String forwarded = context.firstProperty("server.forward-headers-strategy");
        if (forwarded != null && ("framework".equalsIgnoreCase(forwarded) || "native".equalsIgnoreCase(forwarded))) {
            return true;
        }
        return context.chains().stream()
                .anyMatch(
                        chain -> chain.hasFilter("ChannelProcessingFilter") || chain.hasFilter("HttpsRedirectFilter"));
    }
}
