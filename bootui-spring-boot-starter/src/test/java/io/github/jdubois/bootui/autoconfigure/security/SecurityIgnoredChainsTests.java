package io.github.jdubois.bootui.autoconfigure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

class SecurityIgnoredChainsTests {

    @Test
    void nativeWebIgnoringAssetsProducesOneLowReviewInsteadOfMissingAuthorization() {
        try (var application = new AnnotationConfigApplicationContext(ProtectedBackstop.class, IgnoredAssets.class)) {
            var scanner = scanner(application.getDefaultListableBeanFactory());
            SecurityReport report = scanner.scan();

            assertThat(report.filterChainsAnalyzed()).isEqualTo(3);
            assertThat(report.rulesEvaluated()).isEqualTo(51);
            assertThat(report.filterChains()).contains("/assets/**", "/images/**", "any request");
            assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-AUTHZ-001");
            SecurityRuleResultDto ignored = finding(report, "SEC-AUTHZ-006");
            assertThat(ignored.severity()).isEqualTo("LOW");
            assertThat(ignored.violationCount()).isOne();
            assertThat(ignored.sampleViolations()).singleElement().asString().contains("/assets/**", "/images/**");
            var page = scanner.ruleViolations(
                    "SEC-AUTHZ-006", report.violationDetails().scanId(), 0, 100);
            assertThat(page.violationCount()).isOne();
            assertThat(page.retainedCount()).isOne();
            assertThat(page.violations()).containsExactlyElementsOf(ignored.sampleViolations());
            assertThat(page.truncated()).isFalse();
            assertConsistentCounts(report);
        }
    }

    @Test
    void nativeCustomFilterDischargesOnlyTheIgnoredChainObligation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Filter filter = (request, response, chain) -> {
            calls.incrementAndGet();
            throw new AssertionError("Application filter must not run");
        };
        RequestMatcher matcher = new RequestMatcher() {
            @Override
            public boolean matches(HttpServletRequest request) {
                calls.incrementAndGet();
                throw new AssertionError("Application matcher must not run");
            }

            @Override
            public String toString() {
                calls.incrementAndGet();
                return "/**";
            }
        };
        var nativeChain = new DefaultSecurityFilterChain(matcher, filter);
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("springSecurityFilterChain", new FilterChainProxy(nativeChain));
        calls.set(0);
        Method observation =
                SecurityScanner.class.getDeclaredMethod("toChainModel", int.class, SecurityFilterChain.class);
        observation.setAccessible(true);
        var observed = (SecurityModel.FilterChainModel) observation.invoke(null, 0, nativeChain);
        assertThat(observed.details().chainSupported()).isTrue();
        assertThat(observed.filterNames()).containsExactly("Unknown");
        assertThat(observed.details().filtersKnown()).isFalse();
        assertThat(observed.isKnownFilterless()).isFalse();
        var context = new SecurityContext(
                List.of(observed),
                List.of(),
                List.of(),
                false,
                List.of(),
                false,
                false,
                false,
                false,
                List.of(),
                false,
                false,
                List.of(),
                false,
                new MockEnvironment());
        assertThat(new IgnoredSecurityPatternsRule().evaluate(context).status()).isEqualTo("PASS");

        SecurityReport report = scanner(factory).scan();
        assertThat(calls).hasValue(0);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.evidence().limitations())
                .anyMatch(reason -> reason.contains("custom or unsupported filters"))
                .noneMatch(reason -> reason.startsWith("SEC-AUTHZ-006:"));
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-AUTHZ-006");
    }

    @Test
    void nativeUnconditionalEvidencePrecedesLongIgnoredPatternInventory() {
        try (var application =
                new AnnotationConfigApplicationContext(ProtectedBackstop.class, IgnoredLongInventory.class)) {
            var scanner = scanner(application.getDefaultListableBeanFactory());
            SecurityReport report = scanner.scan();
            assertThat(report.filterChainsAnalyzed()).isEqualTo(11);
            assertThat(report.filterChains().get(0)).isEqualTo("/bounded-static-assets-0/**");
            assertThat(report.filterChains().get(9)).isEqualTo("any request");
            SecurityRuleResultDto ignored = finding(report, "SEC-AUTHZ-006");
            assertThat(ignored.severity()).isEqualTo("HIGH");
            assertThat(ignored.violationCount()).isOne();
            assertThat(ignored.sampleViolations())
                    .singleElement()
                    .asString()
                    .contains("Chain #9 (any request)", "/bounded-static-assets-0/**")
                    .hasSizeLessThanOrEqualTo(240);
            var page = scanner.ruleViolations(
                    "SEC-AUTHZ-006", report.violationDetails().scanId(), 0, 100);
            assertThat(page.violationCount()).isOne();
            assertThat(page.retainedCount()).isOne();
            assertThat(page.violations()).containsExactlyElementsOf(ignored.sampleViolations());
            assertThat(page.violations()).singleElement().asString().contains("any request");
            assertConsistentCounts(report);
        }
    }

    @Test
    void nativeUnconditionalIgnoringRemainsOneHighFindingEvenWithAProtectedBackstop() {
        try (var application =
                new AnnotationConfigApplicationContext(ProtectedBackstop.class, IgnoredUnconditional.class)) {
            SecurityReport report =
                    scanner(application.getDefaultListableBeanFactory()).scan();

            assertThat(report.filterChainsAnalyzed()).isEqualTo(3);
            assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-AUTHZ-001");
            SecurityRuleResultDto ignored = finding(report, "SEC-AUTHZ-006");
            assertThat(ignored.severity()).isEqualTo("HIGH");
            assertThat(ignored.violationCount()).isOne();
            assertConsistentCounts(report);
        }
    }

    @Test
    void nativeGetOnlyAndBasePathAllPatternsAreNotUnconditionalIgnoring() {
        try (var application =
                new AnnotationConfigApplicationContext(ProtectedBackstop.class, IgnoredConstrainedAllPaths.class)) {
            SecurityReport report =
                    scanner(application.getDefaultListableBeanFactory()).scan();

            assertThat(report.filterChainsAnalyzed()).isEqualTo(3);
            assertThat(report.filterChains()).contains("GET /**", "/app/**");
            assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-AUTHZ-001");
            SecurityRuleResultDto ignored = finding(report, "SEC-AUTHZ-006");
            assertThat(ignored.severity()).isEqualTo("LOW");
            assertThat(ignored.violationCount()).isOne();
            assertConsistentCounts(report);
        }
    }

    @Test
    void nativeFilteredChainWithoutAuthorizationStillProducesHigh001() {
        try (var application = new AnnotationConfigApplicationContext(NoAuthorization.class)) {
            SecurityReport report =
                    scanner(application.getDefaultListableBeanFactory()).scan();

            assertThat(report.filterChainsAnalyzed()).isOne();
            assertThat(report.analysisErrors()).isEmpty();
            assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-AUTHZ-006");
            SecurityRuleResultDto missing = finding(report, "SEC-AUTHZ-001");
            assertThat(missing.severity()).isEqualTo("HIGH");
            assertThat(missing.violationCount()).isOne();
        }
    }

    @Test
    void nativePermitAllWithinARealChainIsNotIgnoring() {
        try (var application = new AnnotationConfigApplicationContext(PermitAll.class)) {
            SecurityReport report =
                    scanner(application.getDefaultListableBeanFactory()).scan();

            assertThat(report.filterChainsAnalyzed()).isOne();
            assertThat(report.analysisErrors()).isEmpty();
            assertThat(report.results())
                    .extracting(SecurityRuleResultDto::id)
                    .doesNotContain("SEC-AUTHZ-001", "SEC-AUTHZ-006")
                    .contains("SEC-AUTHZ-003");
        }
    }

    @Test
    void unknownIgnoredMatcherCannotUseItsDescriptionAsUnconditionalProof() {
        try (var application =
                new AnnotationConfigApplicationContext(ProtectedBackstop.class, IgnoredUnknownMatcher.class)) {
            AtomicInteger calls = application.getBean(AtomicInteger.class);
            calls.set(0);
            SecurityReport report =
                    scanner(application.getDefaultListableBeanFactory()).scan();

            assertThat(calls).hasValue(0);
            assertThat(report.analysisErrors()).isEmpty();
            SecurityRuleResultDto ignored = finding(report, "SEC-AUTHZ-006");
            assertThat(ignored.severity()).isEqualTo("LOW");
            assertThat(ignored.sampleViolations()).singleElement().asString().contains("unknown matcher");
            assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-AUTHZ-001");
            assertThat(report.scan().status()).isEqualTo("PARTIAL");
            assertThat(report.evidence().coverageComplete()).isFalse();
            assertThat(report.evidence().limitations()).anyMatch(reason -> reason.startsWith("SEC-AUTHZ-006:"));
        }
    }

    @Test
    void unsupportedEmptyPlaceholderIsNotIgnoringAndRetainsBothEvidenceGaps() {
        AtomicInteger calls = new AtomicInteger();
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("springSecurityFilterChain", new FilterChainProxy(unsupported(calls)));

        SecurityReport report = scanner(factory).scan();

        assertThat(calls).hasValue(0);
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(report.filterChains()).containsExactly("(unsupported chain)");
        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-AUTHZ-001", "SEC-AUTHZ-006");
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.evidence().limitations())
                .anyMatch(reason -> reason.startsWith("SEC-AUTHZ-001:"))
                .anyMatch(reason -> reason.startsWith("SEC-AUTHZ-006:"));
    }

    @Test
    void unsupportedNeighborDoesNotDiscardKnownIgnoredScopeOrMakeCoverageComplete() {
        AtomicInteger calls = new AtomicInteger();
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton(
                "springSecurityFilterChain",
                new FilterChainProxy(List.of(
                        new DefaultSecurityFilterChain(PathPatternRequestMatcher.pathPattern("/assets/**")),
                        unsupported(calls))));

        SecurityReport report = scanner(factory).scan();

        assertThat(calls).hasValue(0);
        assertThat(report.analysisErrors()).isEmpty();
        SecurityRuleResultDto ignored = finding(report, "SEC-AUTHZ-006");
        assertThat(ignored.severity()).isEqualTo("LOW");
        assertThat(ignored.violationCount()).isOne();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.evidence().limitations()).anyMatch(reason -> reason.startsWith("SEC-AUTHZ-006:"));
    }

    @Test
    void filteredChainDismissalDoesNotTransferToIgnoredScopesAndCountsRemainCompatible() {
        try (var application = new AnnotationConfigApplicationContext(NoAuthorization.class, IgnoredAssets.class)) {
            var scanner = scanner(application.getDefaultListableBeanFactory());
            SecurityReport report = scanner.scan();
            assertThat(finding(report, "SEC-AUTHZ-001").violationCount()).isOne();
            assertThat(finding(report, "SEC-AUTHZ-006").violationCount()).isOne();

            SecurityReport dismissed001 = scanner.applyDismissals(report, Set.of("SEC-AUTHZ-001", "SEC-CONFIG-004"));
            assertThat(finding(dismissed001, "SEC-AUTHZ-001").dismissed()).isTrue();
            assertThat(finding(dismissed001, "SEC-AUTHZ-006").dismissed()).isFalse();
            assertThat(finding(dismissed001, "SEC-AUTHZ-006").severity()).isEqualTo("LOW");
            assertConsistentCounts(dismissed001);

            SecurityReport dismissed006 = scanner.applyDismissals(report, Set.of("SEC-AUTHZ-006"));
            assertThat(finding(dismissed006, "SEC-AUTHZ-001").dismissed()).isFalse();
            assertThat(finding(dismissed006, "SEC-AUTHZ-006").dismissed()).isTrue();
            assertConsistentCounts(dismissed006);
            assertThat(scanner.ruleViolations(
                                    "SEC-AUTHZ-006",
                                    dismissed006.violationDetails().scanId(),
                                    0,
                                    100)
                            .violationCount())
                    .isOne();
        }
    }

    private static void assertConsistentCounts(SecurityReport report) {
        assertThat(report.analysisErrors()).isEmpty();
        var active =
                report.results().stream().filter(result -> !result.dismissed()).toList();
        assertThat(report.violationsFound()).isEqualTo(active.size());
        assertThat(report.scan().violationsFound()).isEqualTo(active.size());
        report.severityCounts()
                .forEach(bucket -> assertThat(bucket.count())
                        .as("legacy occurrence count for %s", bucket.severity())
                        .isEqualTo(active.stream()
                                .filter(result -> result.severity().equals(bucket.severity()))
                                .mapToInt(SecurityRuleResultDto::violationCount)
                                .sum()));
    }

    private static SecurityRuleResultDto finding(SecurityReport report, String id) {
        return report.results().stream()
                .filter(result -> result.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static SecurityScanner scanner(DefaultListableBeanFactory factory) {
        ObjectProvider<FilterChainProxy> proxies = mock(ObjectProvider.class);
        ObjectProvider<ListableBeanFactory> factories = mock(ObjectProvider.class);
        when(factories.getIfAvailable()).thenReturn(factory);
        return new SecurityScanner(proxies, factories, new MockEnvironment(), Clock.systemUTC());
    }

    private static SecurityFilterChain unsupported(AtomicInteger calls) {
        return new SecurityFilterChain() {
            @Override
            public boolean matches(HttpServletRequest request) {
                calls.incrementAndGet();
                throw new AssertionError("Application matcher must not run");
            }

            @Override
            public List<Filter> getFilters() {
                calls.incrementAndGet();
                throw new AssertionError("Application filter getter must not run");
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class ProtectedBackstop {
        @Bean
        SecurityFilterChain applicationChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(
                            authorize -> authorize.anyRequest().denyAll())
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class IgnoredAssets {
        @Bean
        WebSecurityCustomizer ignored() {
            return web -> web.ignoring()
                    .requestMatchers(
                            PathPatternRequestMatcher.pathPattern("/assets/**"),
                            PathPatternRequestMatcher.pathPattern("/images/**"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class IgnoredUnconditional {
        @Bean
        WebSecurityCustomizer ignored() {
            return web -> web.ignoring()
                    .requestMatchers(
                            PathPatternRequestMatcher.pathPattern("/assets/**"),
                            PathPatternRequestMatcher.pathPattern("/**"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class IgnoredLongInventory {
        @Bean
        WebSecurityCustomizer ignored() {
            List<RequestMatcher> matchers = new ArrayList<>();
            for (int index = 0; index < 9; index++) {
                matchers.add(PathPatternRequestMatcher.pathPattern("/bounded-static-assets-" + index + "/**"));
            }
            matchers.add(PathPatternRequestMatcher.pathPattern("/**"));
            return web -> web.ignoring().requestMatchers(matchers.toArray(RequestMatcher[]::new));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class IgnoredConstrainedAllPaths {
        @Bean
        WebSecurityCustomizer ignored() {
            return web -> web.ignoring()
                    .requestMatchers(
                            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/**"),
                            PathPatternRequestMatcher.withDefaults()
                                    .basePath("/app")
                                    .matcher("/**"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class NoAuthorization {
        @Bean
        SecurityFilterChain applicationChain(HttpSecurity http) throws Exception {
            return http.build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class PermitAll {
        @Bean
        SecurityFilterChain applicationChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(
                            authorize -> authorize.anyRequest().permitAll())
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class IgnoredUnknownMatcher {
        @Bean
        AtomicInteger matcherCalls() {
            return new AtomicInteger();
        }

        @Bean
        WebSecurityCustomizer ignored(AtomicInteger calls) {
            RequestMatcher matcher = new RequestMatcher() {
                @Override
                public boolean matches(HttpServletRequest request) {
                    calls.incrementAndGet();
                    throw new AssertionError("Application matcher must not run");
                }

                @Override
                public String toString() {
                    calls.incrementAndGet();
                    return "/**";
                }
            };
            return web -> web.ignoring().requestMatchers(matcher);
        }
    }
}
