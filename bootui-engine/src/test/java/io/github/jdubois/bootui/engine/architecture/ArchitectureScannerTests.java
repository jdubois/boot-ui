package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import io.github.jdubois.bootui.engine.action.ActionBusyException;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ArchitectureScannerTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.engine.architecture.fixtures";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);
    private static final String SENSITIVE_DETAIL = "password=secret\njdbc://private-host\t" + "private".repeat(200);

    private ArchitectureScanner scanner(List<String> basePackages) {
        return new ArchitectureScanner(
                () -> basePackages, new ClassFileArchitectureImporter(), ArchitecturePlatform.SPRING, CLOCK);
    }

    @Test
    void initialReportIsNotScanned() {
        ArchitectureReport report = scanner(List.of(FIXTURES)).initialReport();

        assertThat(report.localOnly()).isTrue();
        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.scan().scannedAt()).isNull();
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.classesAnalyzed()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.basePackages()).containsExactly(FIXTURES);
    }

    @Test
    void initialReportNeverImportsOrEvaluatesRules() {
        AtomicInteger discoveries = new AtomicInteger();
        ArchitectureScanner scanner = new ArchitectureScanner(
                () -> {
                    discoveries.incrementAndGet();
                    return List.of(FIXTURES);
                },
                packages -> {
                    throw new AssertionError("Initial report must not import classes");
                },
                ArchitecturePlatform.SPRING,
                CLOCK,
                List.of(new VirtualMachineErrorRule()));

        assertThat(scanner.initialReport().scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(discoveries).hasValue(1);
    }

    @Test
    void discoveryFailuresAreExplicitAndRecoverOnTheNextScan() {
        for (Throwable failure :
                List.of(new IllegalStateException(SENSITIVE_DETAIL), new NoClassDefFoundError(SENSITIVE_DETAIL))) {
            AtomicInteger discoveries = new AtomicInteger();
            ArchitectureScanner scanner = new ArchitectureScanner(
                    () -> {
                        if (discoveries.incrementAndGet() <= 2) {
                            throwFailure(failure);
                        }
                        return List.of();
                    },
                    packages -> {
                        throw new AssertionError("Failed or empty discovery must not import classes");
                    },
                    ArchitecturePlatform.SPRING,
                    CLOCK);

            ArchitectureReport initial = scanner.initialReport();
            assertFailedReport(initial, "Application base packages could not be detected");
            assertThat(initial.scan().scannedAt()).isNull();
            ArchitectureReport failedScan = scanner.scan();
            assertFailedReport(failedScan, "Application base packages could not be detected");
            assertThat(failedScan.scan().scannedAt()).isEqualTo(CLOCK.millis());
            assertThat(scanner.scan().scan().status()).isEqualTo("SCANNED");
            assertThat(discoveries).hasValue(3);
        }
    }

    @Test
    void nullAndMalformedBasePackagesAreErrorsRatherThanEmptySuccess() {
        List<List<String>> invalidPackages = Arrays.asList(
                null,
                Arrays.asList(FIXTURES, null),
                List.of(""),
                List.of(" "),
                List.of("com..example"),
                List.of(".example"),
                List.of("example."),
                List.of("com/example"),
                List.of("com.*"),
                List.of(FIXTURES, SENSITIVE_DETAIL));
        for (List<String> packages : invalidPackages) {
            ArchitectureScanner scanner = new ArchitectureScanner(
                    () -> packages,
                    ignored -> {
                        throw new AssertionError("Invalid packages must not reach the importer");
                    },
                    ArchitecturePlatform.SPRING,
                    CLOCK);

            assertFailedReport(scanner.initialReport(), "Application base packages could not be detected");
            assertFailedReport(scanner.scan(), "Application base packages could not be detected");
        }
    }

    @Test
    void literalJvmPackageNamesAreNotRestrictedToJavaSourceIdentifiers() {
        List<String> roots = List.of("example.kotlin-package", "example.123", "example.caf\u00e9");
        AtomicInteger imports = new AtomicInteger();
        ArchitectureScanner scanner = new ArchitectureScanner(
                () -> roots,
                packages -> {
                    assertThat(packages).isEqualTo(roots);
                    imports.incrementAndGet();
                    return new ClassFileImporter().importClasses();
                },
                ArchitecturePlatform.SPRING,
                CLOCK);
        assertThat(scanner.scan().scan().status()).isEqualTo("SCANNED");
        assertThat(imports).hasValue(1);
    }

    @Test
    void importFailuresAreSanitizedErrorsAndReleaseSingleFlightForRetry() {
        JavaClasses imported = new ClassFileImporter().importClasses(Object.class);
        for (Throwable failure :
                List.of(new IllegalStateException(SENSITIVE_DETAIL), new NoClassDefFoundError(SENSITIVE_DETAIL))) {
            AtomicInteger imports = new AtomicInteger();
            ArchitectureScanner scanner = new ArchitectureScanner(
                    () -> List.of(FIXTURES),
                    packages -> {
                        if (imports.incrementAndGet() == 1) {
                            throwFailure(failure);
                        }
                        return imported;
                    },
                    ArchitecturePlatform.SPRING,
                    CLOCK,
                    List.of(fixedRule(result("ARCH-T-001", ArchitectureRuleSupport.PASS))));

            ArchitectureReport failed = scanner.scan();
            assertFailedReport(failed, "Application classes could not be imported for analysis");
            assertThat(failed.basePackages()).containsExactly(FIXTURES);
            assertThat(failed.scan().message()).contains(failure.getClass().getSimpleName());
            assertThat(failed.scan().scannedAt()).isEqualTo(CLOCK.millis());
            assertThat(scanner.scan().scan().status()).isEqualTo("SCANNED");
            assertThat(imports).hasValue(2);
        }
    }

    @Test
    void nullImportIsAnErrorButGenuinelyEmptyImportRemainsScanned() {
        ArchitectureScanner nullImport =
                new ArchitectureScanner(() -> List.of(FIXTURES), packages -> null, ArchitecturePlatform.SPRING, CLOCK);
        assertFailedReport(nullImport.scan(), "Application classes could not be imported for analysis");

        ArchitectureScanner emptyImport = new ArchitectureScanner(
                () -> List.of(FIXTURES),
                packages -> new ClassFileImporter().importClasses(),
                ArchitecturePlatform.SPRING,
                CLOCK);
        ArchitectureReport report = emptyImport.scan();
        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.classesAnalyzed()).isZero();
        assertThat(report.analysisErrors()).isEmpty();
    }

    @Test
    void partialScanRetainsViolationsAndErrorsThroughDismissal() {
        ArchitectureRuleResultDto violation = result("ARCH-T-002", ArchitectureRuleSupport.VIOLATION);
        ArchitectureScanner scanner = scannerWithRules(List.of(
                new ThrowingRule(),
                fixedRule(result("ARCH-T-001", ArchitectureRuleSupport.PASS)),
                fixedRule(violation),
                new NotApplicableRule()));

        ArchitectureReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.scan().message()).contains("incomplete", "1 rule(s)");
        assertThat(report.rulesEvaluated()).isEqualTo(4);
        assertThat(report.classesAnalyzed()).isEqualTo(1);
        assertThat(report.results()).containsExactly(violation);
        assertThat(report.violationsFound()).isEqualTo(1);
        assertThat(report.severityCounts())
                .filteredOn(count -> count.severity().equals("HIGH"))
                .extracting("count")
                .containsExactly(2);
        assertThat(report.analysisErrors()).singleElement().satisfies(error -> {
            assertThat(error.status()).isEqualTo("ERROR");
            assertThat(error.sampleViolations())
                    .containsExactly("Rule could not be evaluated (IllegalStateException).");
        });

        ArchitectureReport dismissed = scanner.applyDismissals(report, Set.of("ARCH-T-002", "ARCH-TEST-001"));
        assertThat(dismissed.scan().status()).isEqualTo("PARTIAL");
        assertThat(dismissed.scan().scannedAt()).isEqualTo(report.scan().scannedAt());
        assertThat(dismissed.results())
                .singleElement()
                .satisfies(result -> assertThat(result.dismissed()).isTrue());
        assertThat(dismissed.violationsFound()).isZero();
        assertThat(dismissed.analysisErrors()).isEqualTo(report.analysisErrors());
        assertThat(dismissed.severityCounts())
                .allSatisfy(count -> assertThat(count.count()).isZero());
    }

    @Test
    void errorsWithoutSuccessfulEvaluationsAreNotPartialEvenWithSkippedRules() {
        for (List<ArchitectureRule> rules : List.of(
                List.<ArchitectureRule>of(new ThrowingRule()),
                List.<ArchitectureRule>of(new ThrowingRule(), new NotApplicableRule()))) {
            ArchitectureReport report = scannerWithRules(rules).scan();

            assertThat(report.scan().status()).isEqualTo("ERROR");
            assertThat(report.rulesEvaluated()).isEqualTo(rules.size());
            assertThat(report.classesAnalyzed()).isEqualTo(1);
            assertThat(report.results()).isEmpty();
            assertThat(report.analysisErrors()).hasSize(1);
        }
    }

    @Test
    void passingEvaluationMakesRuleFailurePartialEvenWithoutViolations() {
        ArchitectureReport report = scannerWithRules(
                        List.of(new LinkageErrorRule(), fixedRule(result("ARCH-T-001", ArchitectureRuleSupport.PASS))))
                .scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results()).isEmpty();
        assertThat(report.analysisErrors()).hasSize(1);
    }

    @Test
    void laterScanCanRecoverFromRuleFailure() {
        AtomicInteger evaluations = new AtomicInteger();
        ArchitectureRule rule = new AbstractArchitectureRule(testRuleDefinition()) {
            @Override
            ArchRule rule(ArchitectureContext context) {
                if (evaluations.incrementAndGet() == 1) {
                    throw new IllegalStateException(SENSITIVE_DETAIL);
                }
                return com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                        .should()
                        .bePublic();
            }
        };
        ArchitectureScanner scanner = scannerWithRules(List.of(rule));

        assertThat(scanner.scan().scan().status()).isEqualTo("ERROR");
        ArchitectureReport recovered = scanner.scan();
        assertThat(recovered.scan().status()).isEqualTo("SCANNED");
        assertThat(recovered.analysisErrors()).isEmpty();
        assertThat(recovered.results()).isEmpty();
        assertThat(recovered.rulesEvaluated()).isEqualTo(1);
        assertThat(evaluations).hasValue(2);
    }

    @Test
    void virtualMachineErrorsPropagateAndDoNotLeaveScannerBusy() {
        Supplier<List<String>> failingSupplier = () -> {
            throw new StackOverflowError(SENSITIVE_DETAIL);
        };
        ArchitectureScanner discoveryFailure =
                new ArchitectureScanner(failingSupplier, packages -> null, ArchitecturePlatform.SPRING, CLOCK);
        assertThatThrownBy(discoveryFailure::initialReport).isInstanceOf(StackOverflowError.class);
        ArchitectureScanner importFailure = new ArchitectureScanner(
                () -> List.of(FIXTURES),
                packages -> {
                    throw new StackOverflowError(SENSITIVE_DETAIL);
                },
                ArchitecturePlatform.SPRING,
                CLOCK);
        ArchitectureScanner ruleFailure = scannerWithRules(List.of(new VirtualMachineErrorRule()));

        for (ArchitectureScanner scanner : List.of(discoveryFailure, importFailure, ruleFailure)) {
            assertThatThrownBy(scanner::scan).isInstanceOf(StackOverflowError.class);
            assertThatThrownBy(scanner::scan).isInstanceOf(StackOverflowError.class);
        }
    }

    private static void assertFailedReport(ArchitectureReport report, String message) {
        assertThat(report.scan().status()).isEqualTo("ERROR");
        assertThat(report.scan().message())
                .startsWith(message)
                .doesNotContain("password", "secret", "private", "\n", "\t");
        assertThat(report.scan().message().length()).isLessThanOrEqualTo(240);
        assertThat(report.classesAnalyzed()).isZero();
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.violationsFound()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.analysisErrors()).isEmpty();
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (LinkageError) failure;
    }

    private static ArchitectureScanner scannerWithRules(List<ArchitectureRule> rules) {
        JavaClasses classes = new ClassFileImporter().importClasses(Object.class);
        return new ArchitectureScanner(
                () -> List.of(FIXTURES), packages -> classes, ArchitecturePlatform.SPRING, CLOCK, rules);
    }

    private static ArchitectureRule fixedRule(ArchitectureRuleResultDto result) {
        return new AbstractArchitectureRule(testRuleDefinition()) {
            @Override
            ArchRule rule(ArchitectureContext context) {
                return null;
            }

            @Override
            public ArchitectureRuleResultDto evaluate(ArchitectureContext context) {
                return result;
            }
        };
    }

    @Test
    void scanEvaluatesAllRulesAndReturnsOnlyViolationsOrderedByImportance() {
        ArchitectureReport report = scanner(List.of(FIXTURES)).scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.scan().scannedAt()).isEqualTo(CLOCK.millis());
        assertThat(report.rulesEvaluated())
                .isEqualTo(ArchitectureRuleRegistry.activeRules().size());
        assertThat(report.classesAnalyzed()).isPositive();
        assertThat(report.results())
                .allSatisfy(result -> assertThat(result.status()).isEqualTo("VIOLATION"));
        assertThat(report.results())
                .extracting(ArchitectureRuleResultDto::id)
                .contains("ARCH-SPRING-004", "ARCH-SPRING-001", "ARCH-CODE-001")
                .doesNotContain("ARCH-CODE-004");
        assertThat(report.results().stream()
                        .map(ArchitectureRuleResultDto::severity)
                        .toList())
                .isSortedAccordingTo(Comparator.comparingInt(SeverityOrder::rank));

        ArchitectureRuleResultDto standardStreams = report.results().stream()
                .filter(result -> result.id().equals("ARCH-CODE-001"))
                .findFirst()
                .orElseThrow();
        assertThat(standardStreams.status()).isEqualTo("VIOLATION");
        assertThat(standardStreams.violationCount()).isPositive();
        assertThat(standardStreams.sampleViolations()).isNotEmpty();
        assertThat(report.violationsFound()).isPositive();
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(report.severityCounts())
                .extracting("severity")
                .containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
    }

    @Test
    void scanWithNoBasePackagesProducesEmptyScannedReport() {
        ArchitectureReport report = scanner(List.of()).scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.basePackages()).isEmpty();
        assertThat(report.classesAnalyzed()).isZero();
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.violationsFound()).isZero();
    }

    @Test
    void duplicateScanFailsFastAndLaterRetryRuns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger imports = new AtomicInteger();
        ArchitectureClassImporter importer = basePackages -> {
            imports.incrementAndGet();
            entered.countDown();
            await(release);
            return new ClassFileArchitectureImporter().importPackages(basePackages);
        };
        ArchitectureScanner scanner =
                new ArchitectureScanner(() -> List.of(FIXTURES), importer, ArchitecturePlatform.SPRING, CLOCK);
        Thread winner = new Thread(scanner::scan);
        winner.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(scanner::scan).isInstanceOfSatisfying(ActionBusyException.class, failure -> {
            assertThat(failure.result().operation()).isEqualTo("architecture.scan");
            assertThat(failure.result().activeOperation()).isEqualTo("architecture.scan");
        });
        assertThat(imports).hasValue(1);

        release.countDown();
        winner.join(5000);
        assertThat(scanner.scan().scan().status()).isEqualTo("SCANNED");
        assertThat(imports).hasValue(2);
    }

    @Test
    void ruleEvaluationWrapsRuntimeExceptionAsErrorResult() {
        ArchitectureRuleResultDto result = new ThrowingRule().evaluate(null);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.ERROR);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations()).hasSize(1);
        assertThat(result.sampleViolations().get(0)).isEqualTo("Rule could not be evaluated (IllegalStateException).");
    }

    @Test
    void packageCycleEvaluationAlsoUsesSafeFailureDetails() {
        ArchitectureRuleResultDto result = new FreeOfPackageCyclesRule().evaluate(null);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.ERROR);
        assertThat(result.sampleViolations()).containsExactly("Rule could not be evaluated (NullPointerException).");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void ruleEvaluationWrapsLinkageErrorAsErrorResult() {
        ArchitectureRuleResultDto result = new LinkageErrorRule().evaluate(null);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.ERROR);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations()).containsExactly("Rule could not be evaluated (NoClassDefFoundError).");
    }

    @Test
    void ruleThatIsNotApplicableSurfacesSkippedStatus() {
        ArchitectureRuleResultDto result = new NotApplicableRule().evaluate(null);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.SKIPPED);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations().get(0)).contains("not applicable");
    }

    @Test
    void everyActiveRuleRoutesThroughTheFailClosedBase() {
        assertThat(ArchitectureRuleRegistry.activeRules())
                .allSatisfy(rule -> assertThat(rule).isInstanceOf(AbstractArchitectureRule.class));
    }

    private static ArchitectureRuleDefinition testRuleDefinition() {
        return new ArchitectureRuleDefinition(
                "ARCH-TEST-001",
                "Deliberately failing test rule",
                ArchitectureCategory.CODING_PRACTICES,
                "LOW",
                "Test-only rule used to exercise the fail-closed base.",
                "No action required.",
                "https://www.archunit.org/userguide/html/000_Index.html");
    }

    private static final class ThrowingRule extends AbstractArchitectureRule {

        ThrowingRule() {
            super(testRuleDefinition());
        }

        @Override
        ArchRule rule(ArchitectureContext context) {
            throw new IllegalStateException(SENSITIVE_DETAIL);
        }
    }

    private static final class LinkageErrorRule extends AbstractArchitectureRule {

        LinkageErrorRule() {
            super(testRuleDefinition());
        }

        @Override
        ArchRule rule(ArchitectureContext context) {
            throw new NoClassDefFoundError(SENSITIVE_DETAIL);
        }
    }

    private static final class VirtualMachineErrorRule extends AbstractArchitectureRule {

        VirtualMachineErrorRule() {
            super(testRuleDefinition());
        }

        @Override
        ArchRule rule(ArchitectureContext context) {
            throw new StackOverflowError(SENSITIVE_DETAIL);
        }
    }

    private static final class NotApplicableRule extends AbstractArchitectureRule {

        NotApplicableRule() {
            super(testRuleDefinition());
        }

        @Override
        ArchRule rule(ArchitectureContext context) {
            return null;
        }
    }

    @Test
    void analysisErrorsKeepsOnlyErrorResultsSortedById() {
        ArchitectureRuleResultDto pass = result("ARCH-T-001", ArchitectureRuleSupport.PASS);
        ArchitectureRuleResultDto violation = result("ARCH-T-002", ArchitectureRuleSupport.VIOLATION);
        ArchitectureRuleResultDto errorB = result("ARCH-T-004", ArchitectureRuleSupport.ERROR);
        ArchitectureRuleResultDto errorA = result("ARCH-T-003", ArchitectureRuleSupport.ERROR);
        ArchitectureRuleResultDto skipped = result("ARCH-T-005", ArchitectureRuleSupport.SKIPPED);

        List<ArchitectureRuleResultDto> errors =
                ArchitectureScanner.analysisErrors(List.of(pass, violation, errorB, errorA, skipped));

        assertThat(errors).extracting(ArchitectureRuleResultDto::id).containsExactly("ARCH-T-003", "ARCH-T-004");
        assertThat(errors).extracting(ArchitectureRuleResultDto::status).containsOnly(ArchitectureRuleSupport.ERROR);
    }

    private static ArchitectureRuleResultDto result(String id, String status) {
        return new ArchitectureRuleResultDto(
                id,
                "name",
                "Category",
                "HIGH",
                "description",
                status,
                ArchitectureRuleSupport.VIOLATION.equals(status) ? 2 : 0,
                List.of("detail"),
                "recommendation",
                "https://example.com");
    }
}
