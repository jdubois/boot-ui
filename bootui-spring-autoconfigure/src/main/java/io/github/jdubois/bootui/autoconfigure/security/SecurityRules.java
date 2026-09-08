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
        context.evidence().evaluation().begin();
        SecurityRuleResultDto result = evaluateObserved(context);
        context.evidence().evaluation().finish(result);
        return result;
    }

    private SecurityRuleResultDto evaluateObserved(SecurityContext context) {
        try {
            if (definition.category() == SecurityCategory.ACTUATOR
                    && !context.actuator().errors().isEmpty()) {
                return SecurityRuleSupport.error(definition, "Invalid or conflicting Actuator configuration.");
            }
            if (definition.category() == SecurityCategory.ACTUATOR
                    && !context.required(context.actuator().complete())) {
                return skipped("Actuator configuration selection exceeded supported observation limits.");
            }
            SecurityRuleResultDto result = evaluateRule(context);
            if (definition.category() == SecurityCategory.ACTUATOR) {
                context.required(context.evidence().operationsKnown());
            }
            boolean headersKnown = definition.category() != SecurityCategory.HEADERS
                    || context.required(context.chains().stream()
                            .allMatch(chain -> chain.details().headersKnown()));
            if (SecurityRuleSupport.PASS.equals(result.status()) && !headersKnown) {
                return skipped("Custom, conditional or multiple header writers leave delivered policy unknown.");
            }
            // Property, method, provider and header-writer observations are not invalidated by an
            // unrelated custom filter. Chain-dependent absence still needs a complete inventory.
            boolean filtersKnown = !context.evidence().evaluation().hasApplicableTargets()
                    || switch (definition.category()) {
                        case AUTHORIZATION, CSRF, SESSION ->
                            context.required(context.chains().stream()
                                    .allMatch(chain -> chain.details().filtersKnown()));
                        default -> true;
                    };
            if (SecurityRuleSupport.PASS.equals(result.status()) && !filtersKnown) {
                return skipped("Some ordered chains are unsupported; absence cannot be established.");
            }
            return result;
        } catch (SecurityRuleSupport.IncompleteObservationException
                | SecurityActuatorObservation.ObservationLimitException ex) {
            return skipped("Supported observation limits or provenance prevent a complete conclusion.");
        } catch (RuntimeException | LinkageError ex) {
            return SecurityRuleSupport.error(definition, "Rule could not be evaluated: framework metadata failure.");
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

    SecurityRuleResultDto encoderViolation(SecurityContext context, List<String> details) {
        boolean known = context.required(
                !(context.hasFormOrBasicChain() && context.passwordEncoders().isEmpty())
                        && context.passwordEncoderTypes().stream().noneMatch(type -> type.startsWith("Unknown")));
        if (details.isEmpty() && !known) {
            return skipped("Active provider encoder metadata is unavailable; unrelated beans are not evidence.");
        }
        return violation(details);
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
                "Detects a supported active DAO provider selecting NoOpPasswordEncoder for new password encoding.",
                "Use a delegating encoder (PasswordEncoderFactories.createDelegatingPasswordEncoder()) backed by bcrypt, Argon2, or PBKDF2.",
                "https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (String type : context.targets(context.passwordEncoderTypes())) {
            if (type.contains("NoOpPasswordEncoder")) {
                details.add("An active DAO provider selects " + type + " for encoding without hashing.");
            }
        }
        return encoderViolation(context, details);
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
                "Detects a supported active provider selecting a legacy encoder for new hashes. A delegating encoder's legacy matching map is not penalized.",
                "Migrate to bcrypt, Argon2, or PBKDF2 via a DelegatingPasswordEncoder so hashes upgrade over time.",
                "https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (String type : context.targets(context.passwordEncoderTypes())) {
            if (type.contains("NoOpPasswordEncoder")) {
                continue;
            }
            for (String weak : WEAK) {
                if (type.contains(weak)) {
                    details.add("An active DAO provider selects " + type + " for weak/legacy hashing.");
                    break;
                }
            }
        }
        return encoderViolation(context, details);
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
        if (!context.applies(context.hasFormOrBasicChain())) {
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
                "Review the Boot property-backed default account",
                SecurityCategory.AUTHENTICATION,
                "MEDIUM",
                "Detects an active Boot-created account with an explicitly configured password.",
                "Replace the single property-based user with a real UserDetailsService or identity provider for anything beyond local demos.",
                "https://docs.spring.io/spring-boot/reference/web/spring-security.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        String password = context.firstProperty("spring.security.user.password");
        if (!context.applies(context.generatedUserDetailsManagerPresent()) || password == null) {
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
        if (!context.applies(context.isProductionProfileActive())) {
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
                "Detects a supported active provider selecting bcrypt with a readable strength below the framework default of 10. Unknown encoder or cost metadata remains incomplete.",
                "Use a BCrypt strength of at least 10 (the default) so password hashing stays computationally expensive; raise it as hardware improves, or migrate to Argon2/PBKDF2.",
                "https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (PasswordEncoderModel encoder : context.passwordEncoders()) {
            Integer strength = encoder.bcryptStrength();
            if (context.applies(strength != null) && strength >= 0 && strength < RECOMMENDED_MINIMUM_STRENGTH) {
                details.add("PasswordEncoder bean " + encoder.type() + " uses BCrypt strength " + strength
                        + ", below the recommended minimum of " + RECOMMENDED_MINIMUM_STRENGTH + ".");
            }
        }
        return encoderViolation(context, details);
    }
}

final class BasicAuthWithoutTlsRule extends AbstractSecurityRule {

    BasicAuthWithoutTlsRule() {
        super(new SecurityRuleDefinition(
                "SEC-AUTH-007",
                "HTTP Basic authentication should run only over HTTPS",
                SecurityCategory.AUTHENTICATION,
                "HIGH",
                "Reviews Basic authentication in production without observed direct TLS or a supported unconditional redirect on that chain. Forwarded headers do not establish TLS enforcement.",
                "Require HTTPS at the server or trusted edge for Basic authentication. Verify edge TLS separately; forwarding configuration alone is not transport protection.",
                "https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/basic.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (context.applies(chain.hasFilter("BasicAuthenticationFilter")) && !context.isTlsConfiguredFor(chain)) {
                details.add(
                        chain.describe()
                                + " uses Basic without observed direct TLS/chain-local redirect; verify upstream transport enforcement.");
            }
        }
        return violation(details);
    }
}

final class FormLoginWithoutTlsRule extends AbstractSecurityRule {

    FormLoginWithoutTlsRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-AUTH-010",
                        "Form login should run only over HTTPS",
                        SecurityCategory.AUTHENTICATION,
                        "HIGH",
                        "Reviews production form login without observed direct TLS or a supported unconditional redirect on that chain. Upstream TLS is outside the observation.",
                        "Enforce HTTPS at the server or trusted edge; verify proxy TLS independently from forwarded-header handling.",
                        "https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#transmit-passwords-only-over-tls-or-other-strong-transport"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (context.applies(chain.hasFilter("UsernamePasswordAuthenticationFilter"))
                    && !context.isTlsConfiguredFor(chain)) {
                details.add(
                        chain.describe()
                                + " accepts form credentials without observed direct TLS/chain-local redirect; verify upstream enforcement.");
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
                        "Detects an active supported DAO provider retaining distinct internal unknown-user exceptions. Response handlers and externally visible errors are not observed.",
                        "Prefer the default hideUserNotFoundExceptions=true and verify that failure handlers do not disclose account existence.",
                        "https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/dao-authentication-provider.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        context.applies(context.hasFormOrBasicChain());
        if (context.hideUserNotFoundExceptionsDisabled()) {
            return violation(
                    List.of(
                            "An active provider sets hideUserNotFoundExceptions=false; review externally visible failure handling."));
        }
        return pass();
    }
}

final class GeneratedUserInProductionRule extends AbstractSecurityRule {

    GeneratedUserInProductionRule() {
        super(new SecurityRuleDefinition(
                "SEC-AUTH-009",
                "Do not run production on Spring Boot's auto-generated default user",
                SecurityCategory.AUTHENTICATION,
                "HIGH",
                "Detects Spring Boot's auto-configured InMemoryUserDetailsManager (created only when no"
                        + " replacement authentication service is configured) attached to a supported active provider"
                        + " with no explicit user password in production. A username-only override still leaves"
                        + " the generated password. This is separate from the explicitly configured password review.",
                "Register a real UserDetailsService, AuthenticationProvider, or external identity provider before"
                        + " running in production; do not rely on the console-logged generated password.",
                "https://docs.spring.io/spring-boot/reference/web/spring-security.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.applies(context.generatedUserDetailsManagerPresent() && context.isProductionProfileActive())) {
            return pass();
        }
        if (context.firstProperty("spring.security.user.password") != null) {
            // spring.security.user.* is set, so SEC-AUTH-004 already covers this (explicit, non-generated
            // credentials); avoid double-reporting the same static-account risk under two rule ids.
            return pass();
        }
        return violation(List.of(
                "Spring Boot's auto-generated default user/password (InMemoryUserDetailsManager) is active while a"
                        + " production profile is running, with no explicit spring.security.user.password."));
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
                "Detects a known chain without the standard HTTP authorization filter. Custom filters and method controls are outside this observation.",
                "Add authorizeHttpRequests(...) with at least anyRequest().authenticated() (or an explicit denyAll) to the chain.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (context.applies(chain.details().filtersKnown()) && !chain.hasAuthorizationFilter()) {
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
                "Detects a structurally supported unconditional grant in a chain that also configures authentication.",
                "Restrict sensitive paths and finish with anyRequest().authenticated(); keep permitAll only for genuinely public endpoints.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            boolean authenticated = context.applies(chain.hasRealAuthenticationFilter());
            if (authenticated) {
                context.required(!chain.hasAuthorizationFilter() || chain.permitsAllAnonymous() != null);
            }
            if (Boolean.TRUE.equals(chain.permitsAllAnonymous()) && authenticated) {
                details.add(chain.describe()
                        + " grants all requests in its scope even though it configures authentication.");
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
                "Detects a fully known chain inventory granting requests unconditionally with a catch-all scope and no authentication mechanism.",
                "Define authorization rules that require authentication for non-public endpoints instead of leaving the app fully open.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<FilterChainModel> chains = context.targets(context.chains());
        if (chains.isEmpty()) {
            return pass();
        }
        if (chains.stream()
                .anyMatch(chain ->
                        chain.permitsAllAnonymous() == null || !chain.details().filtersKnown())) {
            return skipped("Complete structural authorization coverage is unavailable.");
        }
        boolean allOpen = chains.stream().allMatch(chain -> Boolean.TRUE.equals(chain.permitsAllAnonymous()));
        boolean anyAuthentication = chains.stream().anyMatch(FilterChainModel::hasRealAuthenticationFilter);
        if (allOpen && !anyAuthentication && chains.stream().anyMatch(FilterChainModel::matchesAnyRequest)) {
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
        if (!context.applies(chains.size() >= 2)) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (int i = 0; i < chains.size() - 1; i++) {
            FilterChainModel chain = chains.get(i);
            context.required(chain.details().unconditional() || chain.details().matcher() != null);
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
                "INFO",
                "Reviews an unconditional, method-agnostic matcher preceding later authorization mappings. This is INFO unless a supported constant grant shadows a later constant denial; custom effects remain unknown.",
                "Register narrower matchers (e.g. requestMatchers(\"/admin/**\").hasRole(\"ADMIN\")) before the broader catch-all, or replace the catch-all with anyRequest() so later requestMatchers additions are rejected at startup instead of silently ignored.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        boolean permissiveShadow = false;
        for (FilterChainModel chain : context.chains()) {
            if (context.applies(chain.hasAuthorizationFilter())) {
                context.required(chain.authorizationRuleShadowed() != null);
            }
            if (Boolean.TRUE.equals(chain.authorizationRuleShadowed())) {
                String decision = "an unknown decision";
                var mappings = chain.details().mappings();
                for (int index = 0; index < mappings.size() - 1; index++) {
                    var mapping = mappings.get(index);
                    if (!mapping.matcher().unconditional()) continue;
                    decision = Boolean.TRUE.equals(mapping.grant())
                            ? "an unconditional grant"
                            : Boolean.FALSE.equals(mapping.grant()) ? "an unconditional denial" : decision;
                    permissiveShadow |= Boolean.TRUE.equals(mapping.grant())
                            && mappings.subList(index + 1, mappings.size()).stream()
                                    .anyMatch(later -> Boolean.FALSE.equals(later.grant()));
                    break;
                }
                details.add(chain.describe() + " has an earlier catch-all with " + decision
                        + "; later authorization mappings are unreachable.");
            }
        }
        if (details.isEmpty()
                && context.chains().stream().anyMatch(FilterChainModel::hasAuthorizationFilter)
                && context.chains().stream().noneMatch(chain -> chain.authorizationRuleShadowed() != null)) {
            return skipped("Authorization matcher order could not be inspected for any filter chain.");
        }
        return violation(permissiveShadow ? SecurityRuleSupport.HIGH : SecurityRuleSupport.INFO, details);
    }
}

// ---------------------------------------------------------------------------
// CSRF
// ---------------------------------------------------------------------------

final class CsrfDisabledStatefulRule extends AbstractSecurityRule {

    CsrfDisabledStatefulRule() {
        super(new SecurityRuleDefinition(
                "SEC-CSRF-001",
                "CSRF protection should stay on for browser-automatic credentials",
                SecurityCategory.CSRF,
                "HIGH",
                "Detects interactive login or remember-me credentials without a CsrfFilter, independently of session persistence.",
                "Keep CSRF enabled for automatically submitted browser credentials; header-bearer-only APIs have different applicability.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (context.applies(chain.browserCredentials())) {
                context.required(chain.details().csrfKnown());
                if (!chain.hasFilter("CsrfFilter")) {
                    details.add(
                            chain.describe() + " configures browser credentials but does not install a CsrfFilter.");
                }
            }
        }
        return violation(details);
    }
}

final class CsrfGloballyDisabledRule extends AbstractSecurityRule {

    CsrfGloballyDisabledRule() {
        super(new SecurityRuleDefinition(
                "SEC-CSRF-002",
                "CSRF protection should stay on for HTTP Basic authentication",
                SecurityCategory.CSRF,
                "MEDIUM",
                "Detects an HTTP Basic chain with no CsrfFilter. Basic authentication is stateless, but browsers automatically resend its credentials, so state-changing requests remain vulnerable to CSRF.",
                "Keep CSRF protection enabled for browser-reachable HTTP Basic endpoints, or use bearer credentials that browsers do not attach automatically.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (context.applies(!chain.browserCredentials() && chain.hasFilter("BasicAuthenticationFilter"))) {
                context.required(chain.details().csrfKnown());
                if (!chain.hasFilter("CsrfFilter")) {
                    details.add(chain.describe()
                            + " disables CSRF for HTTP Basic; browsers automatically resend Basic credentials.");
                }
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
                "Detects recognized authentication-filter or session-management strategies explicitly disabling fixation protection. Modern defaults do not require a SessionManagementFilter.",
                "Use the default changeSessionId (or migrateSession) session-fixation strategy instead of none().",
                "https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        boolean determinable = false;
        boolean applicable = false;
        for (FilterChainModel chain : context.chains()) {
            boolean target = context.applies(chain.browserCredentials() || chain.hasFilter("SessionManagementFilter"));
            applicable |= target;
            if (target) context.required(chain.sessionFixationDisabled() != null);
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
        return determinable || !applicable ? pass() : skipped("Session-fixation strategy could not be introspected.");
    }
}

final class SessionCookieSecureRule extends AbstractSecurityRule {

    SessionCookieSecureRule() {
        super(new SecurityRuleDefinition(
                "SEC-SESSION-002",
                "Session cookie should set the Secure flag",
                SecurityCategory.SESSION,
                "MEDIUM",
                "Reviews explicit Secure=false or lack of an explicit Secure override in production. Unset may derive Secure from each request.",
                "Set server.servlet.session.cookie.secure=true so the session cookie is only sent over HTTPS.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        String value = context.firstProperty("server.servlet.session.cookie.secure");
        context.applies(context.hasStatefulChain());
        if ("false".equalsIgnoreCase(String.valueOf(value))) {
            return violation(List.of("server.servlet.session.cookie.secure is explicitly false."));
        }
        if (value == null && context.isProductionProfileActive() && context.hasStatefulChain()) {
            return violation(
                    List.of(
                            "No explicit Secure override is set; verify HTTPS request/container cookie behavior in production."));
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
        context.applies(context.hasStatefulChain());
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
                "Reviews explicit session-cookie SameSite configuration. An unset property leaves container and browser behavior unknown, not a violation.",
                "Set server.servlet.session.cookie.same-site=Lax (or Strict) to reduce cross-site request exposure.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.applies(context.hasStatefulChain())) {
            return pass();
        }
        String value = context.firstProperty("server.servlet.session.cookie.same-site");
        if (value == null) {
            return skipped("SameSite is not explicit; effective container and browser defaults are not observed.");
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
                "Detects that server.servlet.session.timeout is unset, which uses Spring Boot's 30-minute default.",
                "Confirm that the 30-minute default suits the application's risk profile or set server.servlet.session.timeout explicitly.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.applies(context.hasStatefulChain())) {
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
                        "Review bearer authentication saved in sessions",
                        SecurityCategory.SESSION,
                        "LOW",
                        "Detects the bearer authentication filter saving its security context to an HTTP session. The holder's read repository is not evidence of bearer persistence.",
                        "Confirm persistence is intentional. For header-bearer-only APIs use a request-only save repository; mixed interactive login can intentionally retain sessions.",
                        "https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-stateless"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (context.applies(chain.hasFilter("BearerTokenAuthenticationFilter"))
                    && Boolean.TRUE.equals(chain.details().bearerSavesSession())) {
                details.add(chain.describe() + " explicitly saves bearer authentication in an HTTP session.");
            }
        }
        boolean known = context.required(context.chains().stream()
                .noneMatch(chain -> chain.hasFilter("BearerTokenAuthenticationFilter")
                        && chain.details().bearerSavesSession() == null));
        if (details.isEmpty() && !known) {
            return skipped("Bearer filter save repository is unsupported.");
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
                        "Consider sessionManagement().maximumSessions(n) if concurrency limits fit the application. This optional review does not make a maximum mandatory or prove misuse.",
                        "https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html#ns-concurrent-sessions"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            boolean interactiveLogin = chain.hasFilter("UsernamePasswordAuthenticationFilter")
                    || chain.hasFilter("DefaultLoginPageGeneratingFilter");
            if (context.applies(interactiveLogin && chain.isStateful())
                    && !chain.hasFilterContaining("ConcurrentSession")) {
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
                "Reviews a short key in the recognized token-based remember-me digest signature. Length is not an entropy measurement; persistent-token services are not covered.",
                "Configure a long, random remember-me key (16+ characters, generated from a secure source) via rememberMe().key(...), ideally sourced from an externalized secret rather than a literal in configuration.",
                "https://docs.spring.io/spring-security/reference/servlet/authentication/rememberme.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            Integer keyLength = chain.rememberMeKeyLength();
            if (context.applies(chain.hasFilter("RememberMeAuthenticationFilter"))) {
                context.required(keyLength != null);
            }
            if (keyLength != null && keyLength < MIN_KEY_LENGTH) {
                details.add(chain.describe() + " configures a remember-me signing key shorter than " + MIN_KEY_LENGTH
                        + " characters.");
            }
        }
        return violation(details);
    }
}

final class SessionCookieNamePrefixRule extends AbstractSecurityRule {

    SessionCookieNamePrefixRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-SESSION-009",
                        "Custom session cookie names should use a __Host-/__Secure- prefix",
                        SecurityCategory.SESSION,
                        "LOW",
                        "Detects server.servlet.session.cookie.name configured to a custom value that does not start with the"
                                + " __Host- or __Secure- cookie-name prefix (exact case -- browsers only honor these prefixes"
                                + " verbatim). The unmodified default name, JSESSIONID, is not flagged; this rule only fires"
                                + " once an application has already chosen to customize the cookie name.",
                        "Name the session cookie with the __Host- prefix, e.g. __Host-SESSION (requires Secure, no"
                                + " Domain attribute, and Path=/) or, at minimum, the __Secure- prefix, e.g."
                                + " __Secure-SESSION, so the browser rejects the cookie unless it was set over HTTPS --"
                                + " hardening against cookie-tossing from a sibling or subdomain.",
                        "https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html#cookie-name-prefixes"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        String name = context.firstProperty("server.servlet.session.cookie.name");
        context.applies(context.hasStatefulChain());
        if (name == null || name.startsWith("__Host-") || name.startsWith("__Secure-")) {
            return pass();
        }
        return violation(List.of("server.servlet.session.cookie.name is set to '" + name
                + "', which does not use the __Host- or __Secure- cookie-name prefix."));
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
                "Reviews supported chain header configuration without a recognized HSTS writer. Writer configuration is not proof of actual HTTPS/header delivery.",
                "Keep the default HstsHeaderWriter (served over HTTPS) so browsers pin TLS for the domain.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-hsts"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (context.applies(chain.headerWriterFilterPresent())
                    && chain.details().headersKnown()
                    && !chain.hasHeaderWriterContaining("Hsts")) {
                details.add(
                        chain.describe()
                                + " has no standard HSTS writer; actual HTTPS responses and external headers are not observed.");
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
                        "Reviews browser-credential chains without an effective recognized framing restriction. Enforcing frame-ancestors overrides X-Frame-Options, even when permissive; unknown CSP cannot establish safe fallback.",
                        "Use a restrictive enforcing frame-ancestors policy, or keep XFrameOptionsHeaderWriter when that directive is absent. Review actual delivered policy separately.",
                        "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-frame-options"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        boolean unknown = false;
        for (FilterChainModel chain : context.chains()) {
            if (!context.applies(chain.browserCredentials() && chain.headerWriterFilterPresent())) continue;
            Boolean protectedFromFraming = chain.framingProtected();
            context.required(protectedFromFraming != null);
            if (protectedFromFraming == null) unknown = true;
            else if (!protectedFromFraming)
                details.add(
                        chain.describe()
                                + " has no effective recognized framing restriction; enforcing frame-ancestors takes precedence over X-Frame-Options.");
        }
        if (!details.isEmpty()) return violation(details);
        return unknown
                ? skipped("Enforcing CSP or writer scope is unknown; X-Frame-Options cannot establish safe fallback.")
                : pass();
    }
}

final class ContentSecurityPolicyRule extends AbstractSecurityRule {

    ContentSecurityPolicyRule() {
        super(new SecurityRuleDefinition(
                "SEC-HEAD-003",
                "A Content-Security-Policy should be defined",
                SecurityCategory.HEADERS,
                "LOW",
                "Reviews browser-credential chains without a recognized enforcing CSP writer. Report-only is not enforcement, and API chains are not assumed to serve documents.",
                "Add a ContentSecurityPolicyHeaderWriter with a tailored policy to mitigate XSS and data injection.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-csp"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (context.applies(chain.browserCredentials() && chain.headerWriterFilterPresent())
                    && chain.details().headersKnown()) {
                boolean cspPresent = chain.hasHeaderWriterContaining("ContentSecurityPolicy");
                if (cspPresent) {
                    context.required(chain.cspPolicyDirectives() != null && chain.cspReportOnly() != null);
                }
                if (!cspPresent || Boolean.TRUE.equals(chain.cspReportOnly())) {
                    details.add(chain.describe()
                            + " has no recognized enforcing Content-Security-Policy; review document responses.");
                }
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
                        "Reviews supported chain writer configuration without X-Content-Type-Options: nosniff; externally delivered headers are not observed.",
                        "Keep the default XContentTypeOptionsHeaderWriter so browsers do not MIME-sniff responses.",
                        "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-content-type-options"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (context.applies(chain.headerWriterFilterPresent())
                    && chain.details().headersKnown()
                    && !chain.hasHeaderWriterContaining("XContentTypeOptions")) {
                details.add(chain.describe() + " has no standard nosniff writer; actual responses are not observed.");
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
            if (context.applies(chain.headerWriterFilterPresent())
                    && !chain.hasHeaderWriterContaining("ReferrerPolicy")) {
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
            if (context.applies(chain.headerWriterFilterPresent())
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
                "LOW",
                "Detects a browser-credential chain without Spring's HeaderWriterFilter. Custom filters and external infrastructure may supply headers.",
                "Remove headers().disable(); keep the default HeaderWriterFilter so security headers are emitted, and only tune individual writers you do not need.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            boolean browserFacing = context.applies(chain.browserCredentials());
            if (browserFacing && !chain.headerWriterFilterPresent()) {
                details.add(chain.describe()
                        + " installs no standard HeaderWriterFilter; delivered security headers are unknown.");
            }
        }
        return violation(details);
    }
}

final class WeakHstsPolicyRule extends AbstractSecurityRule {

    WeakHstsPolicyRule() {
        super(new SecurityRuleDefinition(
                "SEC-HEAD-008",
                "Review HSTS max-age below the framework default",
                SecurityCategory.HEADERS,
                "LOW",
                "Reviews max-age below Spring's one-year default. Zero removes an HSTS policy; shorter rollout durations can be deliberate.",
                "Choose max-age for the deployment and rollout. Enable includeSubDomains only when all subdomains support HTTPS.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-hsts"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (context.applies(chain.hasHeaderWriterContaining("Hsts"))) {
                context.required(chain.hstsMaxAgeSeconds() != null);
            }
            if (chain.hasWeakHsts()) {
                details.add(chain.describe() + " configures HSTS max-age " + chain.hstsMaxAgeSeconds()
                        + (Long.valueOf(0).equals(chain.hstsMaxAgeSeconds())
                                ? " (policy removal)."
                                : " (below Spring's default; review rollout intent)."));
            }
        }
        return violation(details);
    }
}

final class WeakContentSecurityPolicyRule extends AbstractSecurityRule {

    WeakContentSecurityPolicyRule() {
        super(new SecurityRuleDefinition(
                "SEC-HEAD-009",
                "Review permissive CSP script execution",
                SecurityCategory.HEADERS,
                "MEDIUM",
                "Inspects a supported single enforcing policy's effective script directives, including nonce/hash and strict-dynamic semantics. Framing is assessed separately.",
                "Restrict script execution with application-specific nonce/hash or trusted sources and remove unnecessary eval permissions.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#servlet-headers-csp"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            Boolean weak = chain.weakCspObservation();
            if (context.applies(chain.hasHeaderWriterContaining("ContentSecurityPolicy"))) {
                context.required(weak != null);
            }
            if (Boolean.TRUE.equals(weak)) {
                details.add(chain.describe() + " configures an enforcing CSP with permissive script execution.");
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
                "Reviews browser-credential chains with neither opener nor embedder policy in supported header configuration. Cross-origin isolation is capability-specific and optional, not a universal requirement.",
                "Add CrossOriginOpenerPolicyHeaderWriter / CrossOriginEmbedderPolicyHeaderWriter via headers().crossOriginOpenerPolicy(...) / .crossOriginEmbedderPolicy(...) if the application needs cross-origin isolation (e.g. for SharedArrayBuffer) or Spectre-style side-channel hardening.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (FilterChainModel chain : context.chains()) {
            if (context.applies(chain.browserCredentials() && chain.headerWriterFilterPresent())
                    && chain.details().headersKnown()
                    && (!chain.hasHeaderWriterContaining("CrossOriginOpenerPolicy")
                            || !chain.hasHeaderWriterContaining("CrossOriginEmbedderPolicy"))) {
                details.add(
                        chain.describe()
                                + " lacks a recognized COOP/COEP pair; consider it only for features requiring cross-origin isolation.");
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
                "LOW",
                "Reviews a supported attached policy allowing wildcard origins without credentials. This can be intentional for public data; credentialed cases belong to SEC-CORS-002.",
                "Enumerate the exact trusted origins instead of \"*\"; use allowedOriginPatterns only for tightly-scoped patterns.",
                "https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (CorsConfigModel cors : context.targets(context.corsConfigs())) {
            if (cors.allowsWildcardOrigin() && !cors.allowsCredentials()) {
                details.add(cors.describe());
            }
        }
        boolean complete = context.required(!context.customCorsSourcePresent());
        if (details.isEmpty() && !complete) {
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
                "Distinguishes Spring's rejected literal wildcard/credentials combination from accepted wildcard origin-pattern reflection.",
                "Never pair allowCredentials(true) with a wildcard origin; list explicit origins so cookies and auth headers are not leaked cross-site.",
                "https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        for (CorsConfigModel cors : context.targets(context.corsConfigs())) {
            if (cors.allowsWildcardOrigin() && cors.allowsCredentials()) {
                details.add(
                        cors.allowedOrigins().contains("*")
                                ? "An attached CORS configuration combines literal allowedOrigins=* and credentials; Spring rejects this combination."
                                : "An attached origin-pattern wildcard reflects arbitrary origins with credentials.");
            }
        }
        boolean complete = context.required(!context.customCorsSourcePresent());
        if (details.isEmpty() && !complete) {
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
                "Reviews attached CORS handling rather than unused source beans. Dynamic, MVC-managed, or differing chain attachments remain unknown.",
                "Enable .cors(...) on the HttpSecurity so preflight handling is consistent with the security chain rather than MVC-only.",
                "https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (context.customCorsSourcePresent()) return skipped("Attached CORS handling is dynamic or MVC-managed.");
        if (!context.applies(context.corsSourcePresent())) return pass();
        if (context.chains().stream()
                .anyMatch(chain -> chain.details().filtersKnown()
                        && !chain.hasFilter("CorsFilter")
                        && !chain.hasFilter("PreFlightRequestFilter"))) {
            return skipped(
                    "CORS attachment differs across chains; external handling and intended origin scope are unknown.");
        }
        return pass();
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
        for (CorsConfigModel cors : context.targets(context.corsConfigs())) {
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
        boolean complete = context.required(!context.customCorsSourcePresent());
        if (details.isEmpty() && !complete) {
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
                "Reviews supported attached origin patterns with broad host scope, beyond wildcard cases covered by SEC-CORS-001/002. A scheme wildcard alone is not host broadening; public-suffix ownership is not inferred.",
                "Replace broad patterns with the exact origins (or tightly-scoped subdomain wildcards such as https://*.example.com) the application trusts; broad patterns combined with credentials let untrusted sites make authenticated cross-site calls.",
                "https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        boolean credentialed = false;
        for (CorsConfigModel cors : context.targets(context.corsConfigs())) {
            if (cors.allowsWildcardOrigin()) continue;
            List<String> broad = cors.broadOriginPatterns();
            if (broad.isEmpty()) {
                continue;
            }
            String suffix = cors.allowsCredentials() ? " with allowCredentials=true" : "";
            credentialed = credentialed || cors.allowsCredentials();
            details.add(cors.describe() + " uses " + broad.size() + " broad host pattern(s)" + suffix + ".");
        }
        boolean complete = context.required(!context.customCorsSourcePresent());
        if (details.isEmpty() && !complete) {
            return skipped(
                    "A custom CorsConfigurationSource is present and cannot be introspected for broad origin patterns.");
        }
        return violation(credentialed ? SecurityRuleSupport.HIGH : SecurityRuleSupport.LOW, details);
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
                "Detects method-security annotation families without matching recognized infrastructure activation.",
                "Enable the matching @EnableMethodSecurity family: prePostEnabled, securedEnabled, or jsr250Enabled. Verify proxy and invocation behavior separately.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.required(context.evidence().methodFamiliesKnown()))
            return skipped("Method-security family metadata is incomplete.");
        context.applies(!context.evidence().usedMethodFamilies().isEmpty());
        List<String> disabled = context.evidence().usedMethodFamilies().stream()
                .filter(family -> !context.evidence().enabledMethodFamilies().contains(family))
                .map(family ->
                        "Method annotations in the " + family + " family are present without recognized activation.")
                .toList();
        if (!disabled.isEmpty()) return violation(disabled);
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
                "Detects @EnableGlobalMethodSecurity, deprecated since Spring Security 6 and still present in Spring Security 7.",
                "Migrate to @EnableMethodSecurity, which enables @PreAuthorize/@PostAuthorize by default and uses the modern AuthorizationManager API.",
                "https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        context.applies(context.methodSecurityEnabled() || context.globalMethodSecurityLegacyPresent());
        context.required(context.evidence().methodFamiliesKnown());
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
                "Reviews wildcard host web selection of observed sensitive Actuator operations after exclusion and access limits. Selection does not establish anonymous access.",
                "Expose only the endpoints you need (e.g. health, info) and secure the rest behind authentication.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.actuator().wildcardIncluded()
                || context.actuator().sensitiveEndpoints().isEmpty()) {
            return pass();
        }
        if (!context.applies(context.evidence().operations().stream()
                .anyMatch(operation -> context.actuator().sensitiveEndpoints().contains(operation.endpoint())))) {
            return context.evidence().operationsKnown()
                    ? pass()
                    : skipped("Actual management operation inventory is incomplete or in another context.");
        }
        return violation(
                List.of(
                        "Wildcard host web selection includes observed sensitive Actuator operations; authorization is assessed separately."));
    }
}

final class ActuatorSensitiveExposureRule extends AbstractSecurityRule {

    ActuatorSensitiveExposureRule() {
        super(new SecurityRuleDefinition(
                "SEC-ACT-002",
                "Sensitive actuator endpoints should not be exposed",
                SecurityCategory.ACTUATOR,
                "HIGH",
                "Reviews explicitly selected, observed sensitive Actuator operations after exclusion and access limits. Wildcard selection is handled by SEC-ACT-001, avoiding a duplicate penalty.",
                "Remove sensitive endpoints from management.endpoints.web.exposure.include (or add them to management.endpoints.web.exposure.exclude) so they are not reachable, or protect them with authentication.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (context.actuator().wildcardIncluded()) return pass();
        Set<String> exposed = context.effectiveSensitiveActuatorExposure();
        if (exposed.isEmpty()) {
            return pass();
        }
        List<String> details = exposed.stream()
                .filter(id -> context.applies(context.evidence().operations().stream()
                        .anyMatch(operation -> operation.endpoint().equals(id))))
                .sorted()
                .map(value -> "Observed Actuator endpoint '" + value
                        + "' is selected for web exposure; authorization is separate.")
                .toList();
        if (details.isEmpty() && !context.evidence().operationsKnown())
            return skipped("Actual management operation inventory is unavailable.");
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
                "Reviews an exact observed, selected Actuator operation with a supported unconditional grant in its first matching chain. Earlier unknown chains or authorization mappings block conclusions; no request or callback is executed.",
                "Require authentication/authorization for the actuator base path -- either inside the chain that matches it (e.g. requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole(\"ADMIN\")) or through a dedicated SecurityFilterChain with a securityMatcher for that path.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.security"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.exposesBeyondHealthAndInfo()) {
            return pass();
        }
        if (context.actuator().separateManagementPort())
            return skipped("Separate management context authorization is not observed.");
        List<String> details = new ArrayList<>();
        boolean unknown = !context.evidence().operationsKnown();
        for (SecurityContext.Operation operation : context.evidence().operations()) {
            if (operation.endpoint().equals("health")
                    || operation.endpoint().equals("info")
                    || !context.selectedOperation(operation)) continue;
            context.applies(true);
            boolean matched = false;
            for (FilterChainModel chain : context.chains()) {
                if (chain.details().matcher() == null) {
                    unknown = true;
                    matched = true;
                    break;
                }
                Boolean matches = chain.details().matcher().matches(operation.method(), operation.path());
                if (matches == null) {
                    unknown = true;
                    matched = true;
                    break;
                }
                if (!matches) continue;
                matched = true;
                Boolean grant =
                        SecurityScanner.grantFor(chain.details().mappings(), operation.method(), operation.path());
                if (Boolean.TRUE.equals(grant)) {
                    details.add("Observed " + operation.method() + " operation for Actuator '" + operation.endpoint()
                            + "' has a structurally unconditional grant in " + chain.describe() + ".");
                } else if (grant == null) unknown = true;
                break;
            }
            if (!matched) unknown = true;
        }
        context.required(!unknown);
        if (!details.isEmpty()) return violation(details);
        return unknown
                ? skipped("Exact operation or ordered authorization metadata is incomplete; no callback was executed.")
                : pass();
    }
}

final class HealthDetailsExposureRule extends AbstractSecurityRule {

    HealthDetailsExposureRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-ACT-004",
                        "Actuator health details/components should not be exposed unconditionally",
                        SecurityCategory.ACTUATOR,
                        "LOW",
                        "Detects management.endpoint.health.show-details=always or show-components=always, either of"
                                + " which includes component details for callers authorized to reach an observed health operation."
                                + " Neither setting bypasses endpoint authorization; show-components may inherit show-details.",
                        "Leave show-details/show-components at 'never' (the default), or set them to 'when-authorized'"
                                + " and require authentication for the health endpoint.",
                        "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health.show-details"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.actuator().exposed("health")) return pass();
        context.applies(context.observedEndpoint("health"));
        List<String> details = new ArrayList<>();
        if ("always".equalsIgnoreCase(context.firstHostProperty("management.endpoint.health.show-details"))) {
            details.add("management.endpoint.health.show-details is set to 'always'.");
        }
        if ("always".equalsIgnoreCase(context.firstHostProperty("management.endpoint.health.show-components"))) {
            details.add("management.endpoint.health.show-components is set to 'always'.");
        }
        if (!details.isEmpty()) {
            if (!context.observedEndpoint("health"))
                return context.evidence().operationsKnown()
                        ? pass()
                        : skipped("Actual health operation metadata is unavailable.");
        }
        return violation(details);
    }
}

final class ShutdownEndpointEnabledRule extends AbstractSecurityRule {

    ShutdownEndpointEnabledRule() {
        super(new SecurityRuleDefinition(
                "SEC-ACT-005",
                "The actuator shutdown endpoint should not be enabled",
                SecurityCategory.ACTUATOR,
                "HIGH",
                "Reviews an observed shutdown write operation selected by effective web exposure and unrestricted access. Shutdown defaults to NONE, and read-only access removes writes; authorization is separate.",
                "Keep the shutdown endpoint disabled (the default); if you truly need it, restrict it to a secured management port behind strict authentication.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.enabling"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (context.actuator().exposed("shutdown")) {
            if (context.applies(context.evidence().operations().stream()
                    .anyMatch(operation -> operation.endpoint().equals("shutdown")
                            && !operation.method().equals("GET")))) {
                return violation(
                        List.of(
                                "Host configuration selects an observed shutdown write operation; verify authorization and network access."));
            }
            if (!context.evidence().operationsKnown())
                return skipped("Actual shutdown operation metadata is unavailable.");
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
                        "Reviews selected observed management operations beyond health/info sharing the application listener. An equal configured port is shared, -1 disables management HTTP, and an explicit random port is separate.",
                        "Consider a separate listener with explicit network restrictions and authorization; a different port alone is not an isolation guarantee.",
                        "https://docs.spring.io/spring-boot/reference/actuator/monitoring.html#actuator.monitoring.customizing-management-server-port"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.exposesBeyondHealthAndInfo()) {
            return pass();
        }
        if (!context.applies(context.evidence().operations().stream()
                .anyMatch(operation -> context.selectedOperation(operation)
                        && !operation.endpoint().equals("health")
                        && !operation.endpoint().equals("info")))) {
            return context.evidence().operationsKnown()
                    ? pass()
                    : skipped("Actual management operation inventory is unavailable.");
        }
        if (context.actuator().separateManagementPort()) {
            return pass();
        }
        return violation(
                List.of(
                        "Selected management endpoints share the application listener; a separate port still requires network and authorization controls."));
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
                        "Reviews show-values=always on selected observed env/configprops operations. The setting may disclose values to authorized callers; it does not bypass endpoint authorization or custom sanitizers.",
                        "The default is 'never'. Use 'when-authorized' only with appropriate authorized roles, and review endpoint access independently.",
                        "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.sanitization"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        boolean unknown = false;
        context.applies(context.observedEndpoint("env") || context.observedEndpoint("configprops"));
        if (context.actuator().exposed("env")
                && "always".equalsIgnoreCase(context.firstHostProperty("management.endpoint.env.show-values"))) {
            if (!context.observedEndpoint("env")) unknown = !context.evidence().operationsKnown();
            else
                details.add(
                        "Selected env endpoint has show-values=always; this disclosure setting does not bypass authorization.");
        }
        if (context.actuator().exposed("configprops")
                && "always"
                        .equalsIgnoreCase(context.firstHostProperty("management.endpoint.configprops.show-values"))) {
            if (!context.observedEndpoint("configprops"))
                unknown |= !context.evidence().operationsKnown();
            else
                details.add(
                        "Selected configprops endpoint has show-values=always; this disclosure setting does not bypass authorization.");
        }
        context.required(!unknown);
        if (details.isEmpty() && unknown) return skipped("Actual value-disclosure operation metadata is unavailable.");
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
                "Resource server must validate tokens via JWT issuer/JWK or opaque-token introspection",
                SecurityCategory.OAUTH2,
                "HIGH",
                "Recognizes JWT decoders and opaque-token introspectors attached to supported active providers."
                        + " Missing global beans or properties do not establish missing validation; unsupported attachment remains unknown.",
                "Configure spring.security.oauth2.resourceserver.jwt.issuer-uri (or jwk-set-uri / a JwtDecoder bean)"
                        + " for JWT resource servers, or"
                        + " spring.security.oauth2.resourceserver.opaquetoken.introspection-uri (or a custom"
                        + " OpaqueTokenIntrospector bean) for opaque-token resource servers, so incoming bearer"
                        + " tokens are actually verified.",
                "https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        boolean bearerChain = context.chains().stream()
                .anyMatch(chain -> chain.hasFilterContaining("BearerTokenAuthenticationFilter"));
        if (!context.applies(bearerChain)) {
            return pass();
        }
        if (!context.jwtDecoderTypes().isEmpty()
                || !context.opaqueTokenIntrospectorTypes().isEmpty()) {
            return pass();
        }
        return skipped(
                "Inline decoder, introspector or resolver attachment is not readable; missing global beans do not imply missing validation.");
    }
}

final class JwtAudienceValidationRule extends AbstractSecurityRule {

    JwtAudienceValidationRule() {
        super(
                new SecurityRuleDefinition(
                        "SEC-OAUTH-002",
                        "Validate the JWT audience claim",
                        SecurityCategory.OAUTH2,
                        "INFO",
                        "Reviews explicit audience configuration for an observed Boot-managed JWT decoder. Custom decoders and unrelated validator beans do not establish audience behavior.",
                        "For Boot-managed JWT validation configure spring.security.oauth2.resourceserver.jwt.audiences. For a custom decoder attach the appropriate audience validator directly.",
                        "https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-validation"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.applies(
                context.chains().stream().anyMatch(chain -> chain.hasFilter("BearerTokenAuthenticationFilter"))))
            return pass();
        if (!context.evidence().bootManagedJwt())
            return skipped("Active Boot-managed decoder provenance is unavailable; custom validation remains unknown.");
        if (!SecurityActuatorObservation.tokens(
                        context.environment(), "spring.security.oauth2.resourceserver.jwt.audiences")
                .isEmpty()) return pass();
        return violation(
                List.of(
                        "Observed Boot-managed JWT decoder has no explicit audiences setting; review token recipient validation for this API."));
    }
}

final class InsecureJwtMetadataUrlRule extends AbstractSecurityRule {

    InsecureJwtMetadataUrlRule() {
        super(new SecurityRuleDefinition(
                "SEC-OAUTH-004",
                "JWT issuer and JWK endpoints should use HTTPS",
                SecurityCategory.OAUTH2,
                "HIGH",
                "Detects an issuer-uri or jwk-set-uri that uses plain HTTP. Discovery metadata or signing keys fetched without transport authentication can be modified by an active network attacker.",
                "Use HTTPS issuer and JWK endpoints with certificate validation enabled; reserve HTTP endpoints for isolated test environments.",
                "https://www.rfc-editor.org/rfc/rfc8414.html#section-3.3"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        addIfInsecureUrl(context, details, "spring.security.oauth2.resourceserver.jwt.issuer-uri");
        addIfInsecureUrl(context, details, "spring.security.oauth2.resourceserver.jwt.jwk-set-uri");
        return violation(details);
    }

    private static void addIfInsecureUrl(SecurityContext context, List<String> details, String key) {
        String value = context.firstProperty(key);
        context.applies(value != null);
        if (value != null && value.toLowerCase(Locale.ROOT).startsWith("http://")) {
            details.add(key + " uses plain HTTP.");
        }
    }
}

final class JwtStaticKeyRule extends AbstractSecurityRule {

    JwtStaticKeyRule() {
        super(new SecurityRuleDefinition(
                "SEC-OAUTH-003",
                "Review static verification-key rotation",
                SecurityCategory.OAUTH2,
                "INFO",
                "Detects a resource server pinned to a static public key (public-key-location) with no issuer or JWK set URI.",
                "Document replacement and rollover for the configured trust anchor. Static keys can rotate out of band; remote JWKS is optional.",
                "https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        String publicKey = context.firstProperty("spring.security.oauth2.resourceserver.jwt.public-key-location");
        if (!context.applies(publicKey != null)) {
            return pass();
        }
        boolean rotatable = context.firstProperty(
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri")
                != null;
        if (rotatable) {
            return pass();
        }
        return violation(
                List.of(
                        "A static public-key location is configured without issuer/JWK metadata; confirm out-of-band rotation. Custom decoder behavior is not inferred."));
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
                "Detects Spring Security's DebugFilter, installed by @EnableWebSecurity(debug = true), which logs filter chains and request details.",
                "Disable security debug mode outside local development; it leaks configuration and request information.",
                "https://docs.spring.io/spring-security/reference/servlet/configuration/java.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        context.applies(!context.chains().isEmpty());
        boolean debugFilterPresent = context.securityDebugFilterPresent()
                || context.chains().stream().anyMatch(chain -> chain.hasFilter("DebugFilter"));
        if (debugFilterPresent) {
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
                "Reviews spring.h2.console.enabled=true in production as host configuration intent, not proof of an instantiated or anonymously reachable console.",
                "Disable the H2 console in production (keep it to dev profiles) so frame-options are not loosened and the database UI is not reachable.",
                "https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.h2-web-console"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (context.applies(
                context.isPropertyTrue("spring.h2.console.enabled") && context.isProductionProfileActive())) {
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
                "Reviews Boot 4 spring.web.error inclusion settings that are unconditional or caller-enabled; custom error responses are not observed.",
                "Use 'never' for sensitive error details. The 'on-param' mode is caller-controlled, not a confidentiality boundary.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.error-handling"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        List<String> details = new ArrayList<>();
        context.applies(!context.chains().isEmpty());
        for (String suffix : List.of("include-stacktrace", "include-message", "include-binding-errors")) {
            String key = "spring.web.error." + suffix;
            String value = context.firstProperty(key);
            if (value != null && Set.of("always", "on-param", "on_param").contains(value.toLowerCase(Locale.ROOT))) {
                details.add(
                        key + " permits inclusion of internal error details, unconditionally or by caller request.");
            }
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
                        "Reviews production deployments without complete direct TLS or supported chain-local redirect evidence; another chain's redirect and forwarding settings are not global protection.",
                        "Enforce HTTPS at the server or trusted edge. Verify edge policy separately and configure trusted forwarding only after establishing that policy.",
                        "https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.embedded-container.configure-ssl"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.applies(context.isProductionProfileActive())
                || context.isTlsConfigured()
                || context.chains().stream()
                        .anyMatch(chain -> chain.isFormOrBasic() && !context.isTlsConfiguredFor(chain))) {
            return pass();
        }
        return violation(
                List.of(
                        "No complete direct TLS/chain-local redirect evidence is observed in production. Forwarded headers do not establish upstream TLS enforcement."));
    }
}

final class HardcodedSecretPropertyRule extends AbstractSecurityRule {

    HardcodedSecretPropertyRule() {
        super(new SecurityRuleDefinition(
                "SEC-CONFIG-007",
                "Configuration should not hold literal secret values",
                SecurityCategory.CONFIGURATION,
                "HIGH",
                "Reviews literal strings under credential-shaped terminal keys in bounded known packaged classpath sources. Higher-priority sources shadow lower values; unknown provenance prevents hardcoding conclusions. External and dynamic sources are not enumerated for secrets. Only key names are reported.",
                "Move the literal value out of the configuration file into an environment variable, a secrets manager, or a mounted config-tree secret, and reference it with ${ENV_VAR_NAME} instead of a hardcoded literal.",
                "https://docs.spring.io/spring-boot/reference/features/external-config.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        Set<String> keys = context.suspectedHardcodedSecretKeys();
        boolean complete = context.required(context.secretObservationComplete());
        context.applies(complete && !context.chains().isEmpty());
        if (keys.isEmpty()) {
            return complete
                    ? pass()
                    : skipped("External or custom property sources were not read to classify hardcoded credentials.");
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
                "Reviews the actual FilterChainProxy's supported StrictHttpFirewall when normally blocked URL tokens have been allowed. Unused firewall beans do not establish effective policy.",
                "Keep the StrictHttpFirewall defaults; only relax a specific token (e.g. setAllowUrlEncodedSlash(true)) after verifying every downstream matcher and handler safely tolerates it.",
                "https://docs.spring.io/spring-security/reference/servlet/exploits/firewall.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        context.applies(!context.chains().isEmpty());
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
                "Reviews configured DEBUG/TRACE security logger levels in production, including more-specific child overrides and root fallback. Programmatic logging changes are outside this observation.",
                "Keep org.springframework.security logging at INFO or WARN in production; reserve DEBUG/TRACE for local troubleshooting.",
                "https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.log-levels"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(SecurityContext context) {
        if (!context.applies(context.isProductionProfileActive())) return pass();
        List<String> details = new ArrayList<>();
        for (String logger : context.securityLoggerNames()) {
            String level = context.firstProperty("logging.level." + logger);
            if ("DEBUG".equalsIgnoreCase(level) || "TRACE".equalsIgnoreCase(level)) {
                details.add("Configured verbose override for " + logger + "; actual logged content is not inspected.");
            }
        }
        return violation(details);
    }
}
