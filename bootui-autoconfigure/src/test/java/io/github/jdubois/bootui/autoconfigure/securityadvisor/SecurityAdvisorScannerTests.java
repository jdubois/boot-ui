package io.github.jdubois.bootui.autoconfigure.securityadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.securityadvisor.SecurityAdvisorModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.securityadvisor.SecurityAdvisorModel.FilterChainModel;
import io.github.jdubois.bootui.autoconfigure.securityadvisor.SecurityAdvisorModel.PasswordEncoderModel;
import io.github.jdubois.bootui.core.dto.SecurityAdvisorReport;
import io.github.jdubois.bootui.core.dto.SecurityAdvisorRuleResultDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.web.FilterChainProxy;

class SecurityAdvisorScannerTests {

    private static final int RULE_COUNT = 46;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-04T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void scanReportsSecurityFindingsAcrossCategories() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of(
                        "SecurityContextHolderFilter",
                        "HeaderWriterFilter",
                        "BasicAuthenticationFilter",
                        "SessionManagementFilter",
                        "AuthorizationFilter"),
                Boolean.TRUE,
                Boolean.TRUE,
                List.of());
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "*")
                .withProperty("spring.security.debug", "true")
                .withProperty("spring.security.user.name", "admin")
                .withProperty("spring.security.user.password", "admin");
        SecurityAdvisorContext context = new SecurityAdvisorContext(
                List.of(chain),
                List.of(),
                List.of(new CorsConfigModel("/**", List.of("*"), List.of(), List.of(), List.of(), Boolean.TRUE)),
                true,
                List.of(),
                false,
                false,
                false,
                false,
                environment);
        SecurityAdvisorScanner scanner = new SecurityAdvisorScanner(context, CLOCK);

        SecurityAdvisorReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.filterChainsAnalyzed()).isEqualTo(1);
        assertThat(report.rulesEvaluated()).isEqualTo(RULE_COUNT);
        assertThat(report.violationsFound()).isPositive();
        assertThat(report.violationsFound()).isEqualTo(report.results().size());
        assertThat(report.results())
                .extracting(SecurityAdvisorRuleResultDto::id)
                .contains(
                        "SEC-AUTH-003",
                        "SEC-AUTH-004",
                        "SEC-AUTHZ-002",
                        "SEC-CSRF-001",
                        "SEC-SESSION-001",
                        "SEC-HEAD-002",
                        "SEC-HEAD-005",
                        "SEC-HEAD-006",
                        "SEC-CORS-002",
                        "SEC-CORS-003",
                        "SEC-ACT-001",
                        "SEC-CONFIG-001");
        // Results are ordered by severity; the first finding must be HIGH.
        assertThat(report.results().get(0).severity()).isEqualTo("HIGH");
        assertThat(report.filterChains()).containsExactly("any request");
    }

    @Test
    void scanReportsNoViolationsForAHardenedChain() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of(
                        "SecurityContextHolderFilter",
                        "CsrfFilter",
                        "HeaderWriterFilter",
                        "BearerTokenAuthenticationFilter",
                        "AuthorizationFilter"),
                Boolean.FALSE,
                Boolean.FALSE,
                List.of(
                        "HstsHeaderWriter",
                        "XFrameOptionsHeaderWriter",
                        "XContentTypeOptionsHeaderWriter",
                        "ContentSecurityPolicyHeaderWriter",
                        "ReferrerPolicyHeaderWriter",
                        "PermissionsPolicyHeaderWriter"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://issuer.example.com")
                .withProperty("spring.security.oauth2.resourceserver.jwt.audiences", "bootui");
        SecurityAdvisorContext context = new SecurityAdvisorContext(
                List.of(chain),
                List.of(new PasswordEncoderModel(
                        "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder", null)),
                List.of(),
                false,
                List.of(),
                true,
                false,
                false,
                false,
                environment);
        SecurityAdvisorScanner scanner = new SecurityAdvisorScanner(context, CLOCK);

        SecurityAdvisorReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.violationsFound()).isZero();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void scanDoesNotReportBootUiActuatorDefaultsAsHostApplicationFindings() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of(
                        "SecurityContextHolderFilter",
                        "CsrfFilter",
                        "HeaderWriterFilter",
                        "BearerTokenAuthenticationFilter",
                        "AuthorizationFilter"),
                Boolean.FALSE,
                Boolean.FALSE,
                List.of(
                        "HstsHeaderWriter",
                        "XFrameOptionsHeaderWriter",
                        "XContentTypeOptionsHeaderWriter",
                        "ContentSecurityPolicyHeaderWriter",
                        "ReferrerPolicyHeaderWriter",
                        "PermissionsPolicyHeaderWriter"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://issuer.example.com")
                .withProperty("spring.security.oauth2.resourceserver.jwt.audiences", "bootui");
        environment.getPropertySources().addLast(bootUiActuatorDefaultsInDefaultPropertiesSource());
        ConfigurationPropertySources.attach(environment);
        SecurityAdvisorContext context = new SecurityAdvisorContext(
                List.of(chain),
                List.of(new PasswordEncoderModel(
                        "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder", null)),
                List.of(),
                false,
                List.of(),
                true,
                false,
                false,
                false,
                environment);
        SecurityAdvisorScanner scanner = new SecurityAdvisorScanner(context, CLOCK);

        SecurityAdvisorReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.violationsFound()).isZero();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void scanReportsExplicitHostActuatorSettingsWhenBootUiDefaultsArePresent() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of(
                        "SecurityContextHolderFilter",
                        "CsrfFilter",
                        "HeaderWriterFilter",
                        "BearerTokenAuthenticationFilter",
                        "AuthorizationFilter"),
                Boolean.FALSE,
                Boolean.FALSE,
                List.of(
                        "HstsHeaderWriter",
                        "XFrameOptionsHeaderWriter",
                        "XContentTypeOptionsHeaderWriter",
                        "ContentSecurityPolicyHeaderWriter",
                        "ReferrerPolicyHeaderWriter",
                        "PermissionsPolicyHeaderWriter"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://issuer.example.com")
                .withProperty("spring.security.oauth2.resourceserver.jwt.audiences", "bootui")
                .withProperty("management.endpoints.web.exposure.include", "env,beans")
                .withProperty("management.endpoint.health.show-details", "always");
        environment.getPropertySources().addLast(bootUiActuatorDefaultsInDefaultPropertiesSource());
        ConfigurationPropertySources.attach(environment);
        SecurityAdvisorContext context = new SecurityAdvisorContext(
                List.of(chain),
                List.of(new PasswordEncoderModel(
                        "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder", null)),
                List.of(),
                false,
                List.of(),
                true,
                false,
                false,
                false,
                environment);
        SecurityAdvisorScanner scanner = new SecurityAdvisorScanner(context, CLOCK);

        SecurityAdvisorReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.results())
                .extracting(SecurityAdvisorRuleResultDto::id)
                .contains("SEC-ACT-002", "SEC-ACT-003", "SEC-ACT-004", "SEC-ACT-006");
    }

    @Test
    void scanReportsHostDefaultPropertiesActuatorExposure() {
        MockEnvironment environment = resourceServerEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource(
                "defaultProperties",
                new LinkedHashMap<>(Map.of("management.endpoints.web.exposure.include", "env,beans"))));
        ConfigurationPropertySources.attach(environment);
        SecurityAdvisorReport report =
                new SecurityAdvisorScanner(hardenedContext(List.of(), environment), CLOCK).scan();

        assertThat(report.results())
                .extracting(SecurityAdvisorRuleResultDto::id)
                .contains("SEC-ACT-002", "SEC-ACT-003", "SEC-ACT-006");
    }

    @Test
    void scanReturnsStableDisabledReportWhenNoFilterChainProxyIsAvailable() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        SecurityAdvisorScanner scanner = new SecurityAdvisorScanner(
                beanFactory.getBeanProvider(FilterChainProxy.class),
                beanFactory.getBeanProvider(ListableBeanFactory.class),
                new MockEnvironment(),
                CLOCK);

        SecurityAdvisorReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("DISABLED");
        assertThat(report.results()).isEmpty();
        assertThat(report.filterChainsAnalyzed()).isZero();
    }

    @Test
    void initialReportDoesNotInspectConfigurationBeforeExplicitScan() {
        FilterChainModel chain =
                new FilterChainModel(0, "any request", List.of("AuthorizationFilter"), null, null, List.of());
        SecurityAdvisorContext context = new SecurityAdvisorContext(
                List.of(chain),
                List.of(),
                List.of(),
                false,
                List.of(),
                false,
                false,
                false,
                false,
                new MockEnvironment());
        SecurityAdvisorScanner scanner = new SecurityAdvisorScanner(context, CLOCK);

        SecurityAdvisorReport report = scanner.initialReport();

        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void registryExposesTheFullActiveRuleset() {
        assertThat(SecurityAdvisorRuleRegistry.activeRules()).hasSize(RULE_COUNT);
        assertThat(SecurityAdvisorRuleRegistry.activeRules())
                .extracting(rule -> rule.definition().id())
                .doesNotHaveDuplicates();
    }

    @Test
    void flagsBcryptEncoderConfiguredBelowRecommendedStrength() {
        SecurityAdvisorContext context =
                hardenedContext(List.of(new PasswordEncoderModel(BCRYPT, 4)), resourceServerEnvironment());

        SecurityAdvisorReport report = new SecurityAdvisorScanner(context, CLOCK).scan();

        assertThat(report.results())
                .extracting(SecurityAdvisorRuleResultDto::id)
                .contains("SEC-AUTH-006");
    }

    @Test
    void doesNotFlagBcryptEncoderUsingDefaultOrAdequateStrength() {
        SecurityAdvisorContext context = hardenedContext(
                List.of(new PasswordEncoderModel(BCRYPT, -1), new PasswordEncoderModel(BCRYPT, 12)),
                resourceServerEnvironment());

        SecurityAdvisorReport report = new SecurityAdvisorScanner(context, CLOCK).scan();

        assertThat(report.violationsFound()).isZero();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void flagsInteractiveLoginChainWithoutConcurrentSessionControl() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of(
                        "SecurityContextHolderFilter",
                        "UsernamePasswordAuthenticationFilter",
                        "SessionManagementFilter",
                        "AuthorizationFilter"),
                Boolean.FALSE,
                Boolean.FALSE,
                List.of());

        SecurityAdvisorReport report =
                new SecurityAdvisorScanner(contextWith(chain, new MockEnvironment()), CLOCK).scan();

        assertThat(report.results())
                .extracting(SecurityAdvisorRuleResultDto::id)
                .contains("SEC-SESSION-007");
    }

    @Test
    void doesNotFlagConcurrentSessionControlWhenConfigured() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of(
                        "SecurityContextHolderFilter",
                        "UsernamePasswordAuthenticationFilter",
                        "SessionManagementFilter",
                        "ConcurrentSessionFilter",
                        "AuthorizationFilter"),
                Boolean.FALSE,
                Boolean.FALSE,
                List.of());

        SecurityAdvisorReport report =
                new SecurityAdvisorScanner(contextWith(chain, new MockEnvironment()), CLOCK).scan();

        assertThat(report.results())
                .extracting(SecurityAdvisorRuleResultDto::id)
                .doesNotContain("SEC-SESSION-007");
    }

    @Test
    void flagsMissingHttpsEnforcementInProduction() {
        MockEnvironment environment = resourceServerEnvironment();
        environment.setActiveProfiles("prod");
        SecurityAdvisorContext context = hardenedContext(List.of(), environment);

        SecurityAdvisorReport report = new SecurityAdvisorScanner(context, CLOCK).scan();

        assertThat(report.results())
                .extracting(SecurityAdvisorRuleResultDto::id)
                .contains("SEC-CONFIG-006");
    }

    @Test
    void doesNotFlagHttpsEnforcementWhenForwardedHeadersAreConfigured() {
        MockEnvironment environment = resourceServerEnvironment();
        environment.setActiveProfiles("prod");
        environment.withProperty("server.forward-headers-strategy", "framework");
        SecurityAdvisorContext context = hardenedContext(List.of(), environment);

        SecurityAdvisorReport report = new SecurityAdvisorScanner(context, CLOCK).scan();

        assertThat(report.results())
                .extracting(SecurityAdvisorRuleResultDto::id)
                .doesNotContain("SEC-CONFIG-006");
    }

    private static final String BCRYPT = "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder";

    private static FilterChainModel hardenedChain() {
        return new FilterChainModel(
                0,
                "any request",
                List.of(
                        "SecurityContextHolderFilter",
                        "CsrfFilter",
                        "HeaderWriterFilter",
                        "BearerTokenAuthenticationFilter",
                        "AuthorizationFilter"),
                Boolean.FALSE,
                Boolean.FALSE,
                List.of(
                        "HstsHeaderWriter",
                        "XFrameOptionsHeaderWriter",
                        "XContentTypeOptionsHeaderWriter",
                        "ContentSecurityPolicyHeaderWriter",
                        "ReferrerPolicyHeaderWriter",
                        "PermissionsPolicyHeaderWriter"));
    }

    private static MockEnvironment resourceServerEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://issuer.example.com")
                .withProperty("spring.security.oauth2.resourceserver.jwt.audiences", "bootui");
    }

    private static SecurityAdvisorContext hardenedContext(
            List<PasswordEncoderModel> encoders, MockEnvironment environment) {
        return new SecurityAdvisorContext(
                List.of(hardenedChain()),
                encoders,
                List.of(),
                false,
                List.of(),
                true,
                false,
                false,
                false,
                environment);
    }

    private static SecurityAdvisorContext contextWith(FilterChainModel chain, MockEnvironment environment) {
        return new SecurityAdvisorContext(
                List.of(chain), List.of(), List.of(), false, List.of(), true, false, false, false, environment);
    }

    @Test
    void ruleEvaluationWrapsRuntimeExceptionAsErrorResult() {
        SecurityAdvisorRuleResultDto result = new ThrowingRule().evaluate(emptyContext());

        assertThat(result.status()).isEqualTo(SecurityAdvisorRuleSupport.ERROR);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations()).hasSize(1);
        assertThat(result.sampleViolations().get(0))
                .contains("Rule could not be evaluated:")
                .contains("boom");
    }

    @Test
    void ruleEvaluationWrapsLinkageErrorAsErrorResult() {
        SecurityAdvisorRuleResultDto result = new LinkageErrorRule().evaluate(emptyContext());

        assertThat(result.status()).isEqualTo(SecurityAdvisorRuleSupport.ERROR);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations().get(0)).contains("missing");
    }

    @Test
    void skippedRuleSurfacesSkippedStatusAndReason() {
        SecurityAdvisorRuleResultDto result = new SkippingRule().evaluate(emptyContext());

        assertThat(result.status()).isEqualTo(SecurityAdvisorRuleSupport.SKIPPED);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations()).containsExactly("Not applicable in this context.");
    }

    @Test
    void everyActiveRuleRoutesThroughTheFailClosedBase() {
        assertThat(SecurityAdvisorRuleRegistry.activeRules())
                .allSatisfy(rule -> assertThat(rule).isInstanceOf(AbstractSecurityAdvisorRule.class));
    }

    private static SecurityAdvisorContext emptyContext() {
        return new SecurityAdvisorContext(
                List.of(), List.of(), List.of(), false, List.of(), false, false, false, false, new MockEnvironment());
    }

    private static SecurityAdvisorRuleDefinition testRuleDefinition() {
        return new SecurityAdvisorRuleDefinition(
                "SEC-TEST-001",
                "Deliberately failing test rule",
                SecurityAdvisorCategory.CONFIGURATION,
                "LOW",
                "Test-only rule used to exercise the fail-closed base.",
                "No action required.",
                null);
    }

    private static final class ThrowingRule extends AbstractSecurityAdvisorRule {

        ThrowingRule() {
            super(testRuleDefinition());
        }

        @Override
        SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
            throw new IllegalStateException("boom");
        }
    }

    private static final class LinkageErrorRule extends AbstractSecurityAdvisorRule {

        LinkageErrorRule() {
            super(testRuleDefinition());
        }

        @Override
        SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
            throw new NoClassDefFoundError("missing");
        }
    }

    private static final class SkippingRule extends AbstractSecurityAdvisorRule {

        SkippingRule() {
            super(testRuleDefinition());
        }

        @Override
        SecurityAdvisorRuleResultDto evaluateRule(SecurityAdvisorContext context) {
            return skipped("Not applicable in this context.");
        }
    }

    private static MapPropertySource bootUiActuatorDefaultsInDefaultPropertiesSource() {
        return new MapPropertySource(
                "defaultProperties",
                Map.of(
                        "management.endpoints.web.exposure.include",
                        "health,info,beans,conditions,configprops,env,loggers,mappings,metrics,startup,scheduledtasks",
                        "management.endpoint.health.show-details",
                        "always"));
    }
}
