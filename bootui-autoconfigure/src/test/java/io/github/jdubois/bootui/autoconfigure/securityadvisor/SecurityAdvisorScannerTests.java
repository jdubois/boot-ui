package io.github.jdubois.bootui.autoconfigure.securityadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.securityadvisor.SecurityAdvisorModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.securityadvisor.SecurityAdvisorModel.FilterChainModel;
import io.github.jdubois.bootui.core.dto.SecurityAdvisorReport;
import io.github.jdubois.bootui.core.dto.SecurityAdvisorRuleResultDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.web.FilterChainProxy;

class SecurityAdvisorScannerTests {

    private static final int RULE_COUNT = 37;
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
                List.of(new CorsConfigModel("/**", List.of("*"), List.of(), Boolean.TRUE)),
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
                        "ContentSecurityPolicyHeaderWriter"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://issuer.example.com")
                .withProperty("spring.security.oauth2.resourceserver.jwt.audiences", "bootui");
        SecurityAdvisorContext context = new SecurityAdvisorContext(
                List.of(chain),
                List.of("org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder"),
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
        FilterChainModel chain = new FilterChainModel(0, "any request", List.of("AuthorizationFilter"), null, null, List.of());
        SecurityAdvisorContext context = new SecurityAdvisorContext(
                List.of(chain), List.of(), List.of(), false, List.of(), false, false, false, false, new MockEnvironment());
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
}
