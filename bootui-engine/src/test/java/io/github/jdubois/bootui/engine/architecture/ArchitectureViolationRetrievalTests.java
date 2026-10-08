package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.engine.advisor.AdvisorRuleRefusals;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
import io.github.jdubois.bootui.engine.architecture.cyclefixtures.alpha.Alpha;
import io.github.jdubois.bootui.engine.architecture.cyclefixtures.beta.Beta;
import io.github.jdubois.bootui.engine.architecture.pagingfixtures.AdvisorViolationFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ArchitectureViolationRetrievalTests {
    private static final String ROOT = AdvisorViolationFixture.class.getPackageName();
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1700000000000L), ZoneOffset.UTC);

    @Test
    void twentyNineSelfCallsAndSixteenGenericExceptionsRemainCompletelyRetrievable() {
        JavaClasses classes = new ClassFileImporter().importClasses(AdvisorViolationFixture.class);
        List<ArchitectureRule> rules =
                List.of(new NoSelfInvocationOfProxiedMethodsRule(), new NoGenericExceptionsRule());
        AtomicInteger imports = new AtomicInteger();
        ArchitectureScanner scanner = new ArchitectureScanner(
                () -> List.of(ROOT),
                packages -> {
                    imports.incrementAndGet();
                    return classes;
                },
                ArchitecturePlatform.SPRING,
                CLOCK,
                rules);
        ArchitectureReport report = scanner.scan();
        assertThat(report.violationDetails().total()).isEqualTo(45);
        assertThat(report.violationDetails().retained()).isEqualTo(45);
        assertThat(report.violationDetails().truncated()).isFalse();
        Map<String, Integer> counts = Map.of("ARCH-SPRING-004", 29, "ARCH-CODE-002", 16);
        for (ArchitectureRule rule : rules) {
            String id = rule.definition().id();
            var summary = report.results().stream()
                    .filter(result -> id.equals(result.id()))
                    .findFirst()
                    .orElseThrow();
            assertThat(summary.violationCount()).isEqualTo(counts.get(id));
            assertThat(summary.sampleViolations()).hasSize(10);
            ArchitectureContext expectedContext =
                    new ArchitectureContext(classes, List.of(ROOT), ArchitecturePlatform.SPRING);
            List<String> expected = ((AbstractArchitectureRule) rule)
                            .rule(expectedContext)
                            .allowEmptyShould(true)
                            .evaluate(classes)
                            .getFailureReport()
                            .getDetails()
                            .stream()
                            .map(ArchitectureRuleSupport::detail)
                            .toList();
            List<String> retrieved =
                    readAll(scanner, id, report.violationDetails().scanId(), 7);
            assertThat(retrieved).containsExactlyElementsOf(expected);
            assertThat(retrieved.subList(0, 10)).containsExactlyElementsOf(summary.sampleViolations());
        }
        assertThat(imports).hasValue(1);
        ArchitectureReport dismissed = scanner.applyDismissals(report, Set.of("ARCH-SPRING-004"));
        assertThat(dismissed.violationDetails()).isEqualTo(report.violationDetails());
        assertThat(readAll(
                        scanner, "ARCH-SPRING-004", dismissed.violationDetails().scanId(), 100))
                .hasSize(29);
    }

    @Test
    void readersKeepTheCompletedSnapshotDuringAnActiveScan() throws Exception {
        JavaClasses classes = new ClassFileImporter().importClasses(AdvisorViolationFixture.class);
        AtomicInteger imports = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ArchitectureScanner scanner = new ArchitectureScanner(
                () -> List.of(ROOT),
                packages -> {
                    if (imports.incrementAndGet() == 2) {
                        entered.countDown();
                        try {
                            if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                    }
                    return classes;
                },
                ArchitecturePlatform.SPRING,
                CLOCK,
                List.of(new NoSelfInvocationOfProxiedMethodsRule()));
        ArchitectureReport first = scanner.scan();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(scanner::scan);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(scanner.lastReport()).isSameAs(first);
            assertThat(readAll(
                            scanner, "ARCH-SPRING-004", first.violationDetails().scanId(), 5))
                    .hasSize(29);
            finish.countDown();
            ArchitectureReport next = pending.get(10, TimeUnit.SECONDS);
            assertThat(scanner.lastReport()).isSameAs(next);
            assertThat(next.violationDetails().scanId())
                    .isNotEqualTo(first.violationDetails().scanId());
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void aCompletedFailureReplacesTheReportAndTheDetailIndexTogether() {
        JavaClasses classes = new ClassFileImporter().importClasses(AdvisorViolationFixture.class);
        AtomicInteger imports = new AtomicInteger();
        ArchitectureScanner scanner = new ArchitectureScanner(
                () -> List.of(ROOT),
                packages -> {
                    if (imports.incrementAndGet() > 1) throw new IllegalStateException("Unavailable bytecode");
                    return classes;
                },
                ArchitecturePlatform.SPRING,
                CLOCK,
                List.of(new NoSelfInvocationOfProxiedMethodsRule()));
        ArchitectureReport first = scanner.scan();
        ArchitectureReport failed = scanner.scan();
        assertThat(failed.scan().status()).isEqualTo("ERROR");
        assertThat(failed.violationDetails().total()).isZero();
        assertThat(failed.violationDetails().retained()).isZero();
        assertThat(scanner.lastReport()).isSameAs(failed);
        assertThatThrownBy(() -> scanner.ruleViolations(
                        "ARCH-SPRING-004", first.violationDetails().scanId(), 0, 5))
                .isInstanceOfSatisfying(
                        AdvisorViolationException.class,
                        error -> assertThat(error.status()).isEqualTo(409));
        assertThatThrownBy(() -> scanner.ruleViolations(
                        "ARCH-SPRING-004", failed.violationDetails().scanId(), 0, 5))
                .isInstanceOfSatisfying(
                        AdvisorViolationException.class,
                        error -> assertThat(error.status()).isEqualTo(404));
    }

    @Test
    void packageCycleAggregationPreservesCountedMultiplicityPastThePreview() {
        JavaClasses classes = new ClassFileImporter().importClasses(Alpha.class, Beta.class);
        List<String> roots =
                java.util.Collections.nCopies(12, "io.github.jdubois.bootui.engine.architecture.cyclefixtures");
        ArchitectureScanner scanner = new ArchitectureScanner(
                () -> roots,
                packages -> classes,
                ArchitecturePlatform.SPRING,
                CLOCK,
                List.of(new FreeOfPackageCyclesRule()));
        ArchitectureReport report = scanner.scan();
        assertThat(report.results()).singleElement().satisfies(rule -> {
            assertThat(rule.id()).isEqualTo("ARCH-PKG-001");
            assertThat(rule.violationCount()).isEqualTo(12);
            assertThat(rule.sampleViolations()).hasSize(10);
        });
        List<String> all =
                readAll(scanner, "ARCH-PKG-001", report.violationDetails().scanId(), 5);
        assertThat(all)
                .hasSize(12)
                .containsOnly(report.results().get(0).sampleViolations().get(0));
    }

    @Test
    void exhaustedSharedRetentionKeepsAllCountsAndRejectsReplacedScanIds() {
        JavaClasses classes = new ClassFileImporter().importClasses(AdvisorViolationFixture.class);
        ArchitectureScanner scanner = new ArchitectureScanner(
                () -> List.of(ROOT),
                packages -> classes,
                ArchitecturePlatform.SPRING,
                CLOCK,
                List.of(new NoSelfInvocationOfProxiedMethodsRule(), new NoGenericExceptionsRule()));
        scanner.setViolationRetentionLimit(() -> 12);
        ArchitectureReport first = scanner.scan();
        assertThat(first.violationDetails().total()).isEqualTo(45);
        AdvisorRuleRefusals.assertEveryResultIsAKnownRule(
                first.results().stream().map(evaluated -> evaluated.id()).toList(),
                first.violationDetails().scanId(),
                (asked, scan) -> scanner.ruleViolations(asked, scan, 0, 1));
        assertThat(first.violationDetails().retained()).isEqualTo(12);
        assertThat(first.violationDetails().truncated()).isTrue();
        assertThat(first.results())
                .allSatisfy(result -> assertThat(result.sampleViolations()).hasSize(10));
        AdvisorRuleViolationsDto emptyRetained =
                scanner.ruleViolations("ARCH-CODE-002", first.violationDetails().scanId(), 0, 100);
        assertThat(emptyRetained.violationCount()).isEqualTo(16);
        assertThat(emptyRetained.violations()).isEmpty();
        assertThat(emptyRetained.truncated()).isTrue();
        assertThat(emptyRetained.page().hasMore()).isFalse();
        ArchitectureReport next = scanner.scan();
        assertThat(next.scan().scannedAt()).isEqualTo(first.scan().scannedAt());
        assertThat(next.violationDetails().scanId())
                .isNotEqualTo(first.violationDetails().scanId());
        assertThatThrownBy(() -> scanner.ruleViolations(
                        "ARCH-SPRING-004", first.violationDetails().scanId(), 0, 7))
                .isInstanceOfSatisfying(
                        AdvisorViolationException.class,
                        error -> assertThat(error.status()).isEqualTo(409));
        assertThat(scanner.lastReport()).isSameAs(next);
    }

    private static List<String> readAll(ArchitectureScanner scanner, String ruleId, String scanId, int limit) {
        List<String> details = new ArrayList<>();
        boolean more;
        do {
            AdvisorRuleViolationsDto page = scanner.ruleViolations(ruleId, scanId, details.size(), limit);
            assertThat(page.scanId()).isEqualTo(scanId);
            assertThat(page.truncated()).isFalse();
            details.addAll(page.violations());
            more = page.page().hasMore();
        } while (more);
        return details;
    }
}
