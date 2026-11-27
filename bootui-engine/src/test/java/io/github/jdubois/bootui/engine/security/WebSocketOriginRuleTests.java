package io.github.jdubois.bootui.engine.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.engine.quarkussecurity.QuarkusSecurityScanner;
import io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityScanner;
import io.github.jdubois.bootui.engine.staticsecurity.StaticSecurityAdvisorService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class WebSocketOriginRuleTests {
    @Test
    void partialKnownWildcardSurvivesFailureAndStaticAdviceDoesNotSupplyEvidence(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path project) throws Exception {
        java.nio.file.Files.writeString(project.resolve("pom.xml"), "<project/>");
        var scanner = new StaticSecurityAdvisorService(() -> project, Clock.systemUTC());
        var builder = new WebSocketOriginObservation.Builder();
        builder.endpoint("/known", List.of("*"), false);
        builder.endpoint("/unknown", null, false);
        builder.failed();
        scanner.setWebSocketOriginsSupplier(builder::build);
        var report = scanner.scan();
        assertThat(report.violationsFound()).isEqualTo(2);
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(report.evidence().limitations()).anyMatch(note -> note.contains("could not be read"));
        assertThat(scanner.applyDismissals(report, Set.of(WebSocketOriginRule.ID))
                        .violationsFound())
                .isOne();
        assertThat(scanner.applyDismissals(report, Set.of(WebSocketOriginRule.ID))
                        .evidence()
                        .usable())
                .isTrue();
        scanner.setWebSocketOriginsSupplier(WebSocketOriginObservation::unavailable);
        var unknown = scanner.scan();
        assertThat(unknown.evidence().usable()).isFalse();
        assertThat(unknown.results()).extracting(result -> result.id()).containsExactly("SEC-STATIC-001");
    }

    private static StaticSecurityAdvisorService scanner(Supplier<WebSocketOriginObservation> supplier) {
        var scanner = new StaticSecurityAdvisorService(() -> null, Clock.systemUTC());
        scanner.setWebSocketOriginsSupplier(supplier);
        return scanner;
    }

    @Test
    void anyDeclaredWildcardCountsOncePerEndpointWithoutLeakingOriginText() {
        var builder = new WebSocketOriginObservation.Builder();
        builder.endpoint("/all", List.of("*", "https://*.secret.example"), true);
        builder.endpoint("/constrained", List.of("https://*.secret.example", "https://example.com:[*]"), true);
        builder.endpoint("/all", List.of("*"), true);
        builder.endpoint("/restrictive", List.of("https://example.com"), true);
        builder.endpoint("/unknown", List.of(), false);
        SecurityReport report = scanner(builder::build).scan();

        assertThat(report.violationsFound()).isOne();
        assertThat(report.rulesEvaluated()).isOne();
        assertThat(report.results()).singleElement().satisfies(finding -> {
            assertThat(finding.id()).isEqualTo(WebSocketOriginRule.ID);
            assertThat(finding.severity()).isEqualTo("INFO");
            assertThat(finding.violationCount()).isEqualTo(2);
            assertThat(finding.sampleViolations()).hasSize(2);
            assertThat(finding.sampleViolations().get(0)).contains("unrestricted");
            assertThat(finding.sampleViolations().get(1)).contains("constrained", "not necessarily every origin");
        });
        assertThat(report.toString()).doesNotContain("secret.example");
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
    }

    @Test
    void absenceAndUnknownPoliciesNeverManufactureUsableEvidence() {
        for (var observation : List.of(
                WebSocketOriginObservation.absent(),
                WebSocketOriginObservation.unavailable(),
                new WebSocketOriginObservation(
                        List.of(new WebSocketOriginObservation.Endpoint("/ws", false, false, false)), true, false))) {
            SecurityReport report = scanner(() -> observation).scan();
            assertThat(report.results()).isEmpty();
            assertThat(report.rulesEvaluated()).isZero();
            assertThat(report.evidence().usable()).isFalse();
        }
        var builder = new WebSocketOriginObservation.Builder();
        builder.endpoint("/ws", List.of(), true);
        SecurityReport restrictive = scanner(builder::build).scan();
        assertThat(restrictive.results()).isEmpty();
        assertThat(restrictive.evidence().usable()).isTrue();
        assertThat(restrictive.rulesEvaluated()).isOne();
        assertThat(restrictive.scan().status()).isEqualTo("PARTIAL");
    }

    @Test
    void previewRetentionAndDismissalSeparateRuleTotalsFromOccurrencesWithoutRecollecting() {
        var builder = new WebSocketOriginObservation.Builder();
        for (int i = 0; i < 30; i++) {
            builder.endpoint("/ws/" + i, List.of("*"), true);
        }
        AtomicInteger calls = new AtomicInteger();
        var scanner = scanner(() -> {
            calls.incrementAndGet();
            return builder.build();
        });
        scanner.setViolationRetentionLimit(() -> 7);
        scanner.initialReport();
        scanner.report();
        assertThat(calls).hasValue(0);
        SecurityReport report = scanner.scan();
        assertThat(report.violationsFound()).isOne();
        assertThat(report.results().get(0).sampleViolations()).hasSize(10);
        assertThat(scanner.ruleViolations(
                                WebSocketOriginRule.ID,
                                report.violationDetails().scanId(),
                                0,
                                100)
                        .violations())
                .hasSize(7);
        assertThat(scanner.ruleViolations(
                                WebSocketOriginRule.ID,
                                report.violationDetails().scanId(),
                                0,
                                100)
                        .violationCount())
                .isEqualTo(30);
        assertThat(report.severityCounts()).anySatisfy(count -> {
            assertThat(count.severity()).isEqualTo("INFO");
            assertThat(count.count()).isEqualTo(30);
        });
        assertThat(scanner.applyDismissals(report, Set.of(WebSocketOriginRule.ID))
                        .violationsFound())
                .isZero();
        assertThat(scanner.applyDismissals(report, Set.of()).violationsFound()).isOne();
        scanner.report();
        assertThat(calls).hasValue(1);
    }

    @Test
    void unsafePathsRemainDistinctAndNeitherOriginsNorCredentialsArePublished() {
        var builder = new WebSocketOriginObservation.Builder();
        builder.endpoint("/ws?token=secret", List.of("https://user:password@*.example.com?token=secret"), true);
        builder.endpoint("/ws#secret", List.of("*"), true);
        SecurityReport report = scanner(builder::build).scan();
        assertThat(report.violationsFound()).isOne();
        assertThat(report.results().get(0).violationCount()).isEqualTo(2);
        assertThat(report.toString()).doesNotContain("token=", "password", "#secret");
        assertThat(report.results().get(0).sampleViolations()).allMatch(s -> s.contains("path omitted"));
    }

    @Test
    void boundsPreserveKnownFindingsButLimitCoverage() {
        var builder = new WebSocketOriginObservation.Builder();
        var origins = new ArrayList<String>();
        origins.add("*");
        origins.addAll(java.util.Collections.nCopies(101, "https://example.com"));
        builder.endpoint("/large-policy", origins, true);
        builder.endpoint("/large-text", List.of("x".repeat(513)), true);
        for (int i = 0; i < 1100; i++) {
            builder.endpoint("/ws/" + i, List.of("*"), true);
        }
        assertThat(builder.build().endpoints()).hasSize(1000);
        SecurityReport report = scanner(builder::build).scan();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.violationsFound()).isOne();
        assertThat(report.results().get(0).violationCount()).isEqualTo(999);
        assertThat(report.results().get(0).sampleViolations())
                .contains("/large-policy: declares the unrestricted '*' origin.");
    }

    @Test
    void genuineFailuresAreSanitizedAnalysisErrorsNotCleanPolicies() {
        SecurityReport report = scanner(() -> {
                    throw new IllegalStateException("token=secret");
                })
                .scan();
        assertThat(report.results()).isEmpty();
        assertThat(report.analysisErrors()).singleElement().satisfies(error -> {
            assertThat(error.id()).isEqualTo(WebSocketOriginRule.ID);
            assertThat(error.status()).isEqualTo("ERROR");
        });
        assertThat(report.toString()).doesNotContain("token=secret");
        assertThat(report.evidence().usable()).isFalse();
    }

    @Test
    void sharedRuleRunsWithoutReactiveChainsOrReadableQuarkusSnapshot() {
        var builder = new WebSocketOriginObservation.Builder();
        builder.endpoint("/ws", List.of("*"), true);
        builder.endpoint("/another", List.of("*"), true);
        var reactive = ReactiveSecurityScanner.using(() -> null, Clock.systemUTC());
        reactive.setWebSocketOriginsSupplier(builder::build);
        var quarkus = QuarkusSecurityScanner.usingSnapshot(() -> null, Clock.systemUTC());
        quarkus.setWebSocketOriginsSupplier(builder::build);

        for (SecurityReport report : List.of(reactive.scan(), quarkus.scan())) {
            assertThat(report.results()).extracting(result -> result.id()).contains(WebSocketOriginRule.ID);
            assertThat(report.violationsFound()).isOne();
            assertThat(report.evidence().usable()).isTrue();
        }
        assertThat(reactive.applyDismissals(reactive.lastReport(), Set.of()).violationsFound())
                .isOne();
        assertThat(quarkus.applyDismissals(quarkus.lastReport(), Set.of()).violationsFound())
                .isOne();
        assertThat(quarkus.lastReport().analysisErrors()).isNotEmpty();
        assertThat(reactive.ruleViolations(
                                WebSocketOriginRule.ID,
                                reactive.lastReport().violationDetails().scanId(),
                                0,
                                10)
                        .violationCount())
                .isEqualTo(2);
        assertThat(quarkus.ruleViolations(
                                WebSocketOriginRule.ID,
                                quarkus.lastReport().violationDetails().scanId(),
                                0,
                                10)
                        .violationCount())
                .isEqualTo(2);
    }
}
