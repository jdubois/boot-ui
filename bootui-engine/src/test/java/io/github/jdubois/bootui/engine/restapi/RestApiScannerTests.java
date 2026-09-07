package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.RestApiReport;
import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.action.ActionBusyException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class RestApiScannerTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.engine.restapi.fixtures";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);
    private static final String SENSITIVE_FAILURE = "password=do-not-publish\n" + "sensitive".repeat(200);

    private RestApiScanner scanner(List<String> basePackages, boolean openApiAnnotationsPresent) {
        return new RestApiScanner(
                () -> basePackages,
                new ClassFileRestApiImporter(),
                () -> openApiAnnotationsPresent,
                () -> false,
                CLOCK);
    }

    @Test
    void initialReportIsNotScanned() {
        RestApiReport report = scanner(List.of(FIXTURES), false).initialReport();

        assertThat(report.localOnly()).isTrue();
        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.scan().scannedAt()).isNull();
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.controllersAnalyzed()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.basePackages()).containsExactly(FIXTURES);
    }

    @Test
    void scanEvaluatesAllRulesAndReturnsOnlyViolationsOrderedByImportance() {
        RestApiReport report = scanner(List.of(FIXTURES), false).scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.scan().scannedAt()).isEqualTo(CLOCK.millis());
        assertThat(report.rulesEvaluated())
                .isEqualTo(RestApiRuleRegistry.activeRules().size());
        assertThat(report.controllersAnalyzed()).isPositive();
        assertThat(report.handlersAnalyzed()).isPositive();
        assertThat(report.results())
                .allSatisfy(result -> assertThat(result.status()).isEqualTo("VIOLATION"));
        assertThat(report.results())
                .extracting(RestApiRuleResultDto::id)
                .contains("RAPI-DTO-001", "RAPI-VALID-001", "RAPI-MAP-001", "RAPI-NAME-001");
        assertThat(report.violationsFound()).isPositive();
        assertThat(report.severityCounts())
                .extracting("severity")
                .containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
    }

    @Test
    void scanWithNoBasePackagesCannotClaimACompleteAnalysis() {
        RestApiReport report = scanner(List.of(), false).scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.basePackages()).isEmpty();
        assertThat(report.controllersAnalyzed()).isZero();
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.violationsFound()).isZero();
    }

    @Test
    void scanWithNoControllersProducesEmptyScannedReport() {
        RestApiReport report = scanner(List.of("does.not.exist"), false).scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.controllersAnalyzed()).isZero();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void malformedPackageScopeNeverExpandsToAClasspathRootImport() {
        for (String invalid : List.of("", " ", ".", "app..api", "/app", "app/", "app.*")) {
            RestApiScanner scanner = new RestApiScanner(
                    () -> List.of(FIXTURES, invalid),
                    packages -> {
                        throw new AssertionError("Invalid scope must never reach the importer");
                    },
                    () -> false,
                    () -> false,
                    CLOCK);
            assertSafePartial(scanner.scan());
        }
    }

    @Test
    void importFailureDegradesToStableReportInsteadOfThrowing() {
        RestApiScanner scanner = new RestApiScanner(
                () -> List.of(FIXTURES),
                basePackages -> {
                    throw new NoClassDefFoundError(SENSITIVE_FAILURE);
                },
                () -> false,
                () -> false,
                CLOCK);

        RestApiReport report = scanner.scan();

        assertSafePartial(report);
        assertThat(report.scan().message()).contains("could not be imported");
        assertThat(report.results()).isEmpty();
        assertThat(report.violationsFound()).isZero();
    }

    @Test
    void springdocFlagEnablesDocumentationRules() {
        RestApiReport report = scanner(List.of(FIXTURES), true).scan();

        assertThat(report.results()).extracting(RestApiRuleResultDto::id).contains("RAPI-DOC-001", "RAPI-DOC-002");
    }

    @Test
    void unavailableBasePackageEvidenceDoesNotMasqueradeAsAnEmptySuccessfulScan() {
        RestApiScanner scanner = new RestApiScanner(
                () -> {
                    throw new IllegalStateException(SENSITIVE_FAILURE);
                },
                packages -> {
                    throw new AssertionError("Import must not run without base packages");
                },
                () -> false,
                () -> false,
                CLOCK);

        RestApiReport initial = scanner.initialReport();
        assertThat(initial.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(initial.scan().scannedAt()).isNull();
        assertThat(initial.scan().message()).contains("could not be read").doesNotContain("password");
        assertSafePartial(scanner.scan());
    }

    @Test
    void nullAndUnlinkablePackageEvidenceAreIncomplete() {
        RestApiScanner missing = new RestApiScanner(() -> null, packages -> null, () -> false, () -> false, CLOCK);
        assertSafePartial(missing.scan());
        RestApiScanner unlinkable = new RestApiScanner(
                () -> {
                    throw new NoClassDefFoundError(SENSITIVE_FAILURE);
                },
                packages -> null,
                () -> false,
                () -> false,
                CLOCK);
        assertThat(unlinkable.initialReport().scan().status()).isEqualTo("NOT_SCANNED");
        assertSafePartial(unlinkable.scan());
    }

    @Test
    void runtimeImportFailureIsIncompleteWithoutExposingExceptionDetails() {
        RestApiScanner scanner = new RestApiScanner(
                () -> List.of(FIXTURES),
                packages -> {
                    throw new IllegalStateException(SENSITIVE_FAILURE);
                },
                () -> false,
                () -> false,
                CLOCK);

        assertSafePartial(scanner.scan());
    }

    @Test
    void fatalModelBuildingFailureIsIncompleteWithoutExposingExceptionDetails() {
        JavaClasses classes = mock(JavaClasses.class);
        when(classes.iterator()).thenThrow(new IllegalStateException(SENSITIVE_FAILURE));
        RestApiScanner scanner =
                new RestApiScanner(() -> List.of(FIXTURES), packages -> classes, () -> false, () -> false, CLOCK);

        RestApiReport report = scanner.scan();
        assertSafePartial(report);
        assertThat(report.scan().message()).contains("could not be analysed");
        assertThat(report.results()).isEmpty();
    }

    @Test
    void incompleteExtractionSkipsExceptionAbsenceRulesAndRetainsReliableHandlersAndFindings() {
        JavaClasses imported = new ClassFileImporter().importClasses(ScanController.class);
        JavaClass unreadable = mock(JavaClass.class);
        when(unreadable.isAnnotatedWith(anyString())).thenThrow(new NoClassDefFoundError(SENSITIVE_FAILURE));
        List<JavaClass> types = new ArrayList<>();
        imported.forEach(types::add);
        types.add(unreadable);
        JavaClasses partial = mock(JavaClasses.class);
        when(partial.iterator()).thenAnswer(invocation -> types.iterator());
        AtomicInteger dependentEvaluations = new AtomicInteger();
        Function<RestApiRuleDefinition, RestApiRuleResultDto> absenceEvaluation = definition -> {
            dependentEvaluations.incrementAndGet();
            return RestApiRuleSupport.fromViolations(definition, List.of("Unreliable missing-handler finding"));
        };
        RestApiScanner scanner = new RestApiScanner(
                () -> List.of(FIXTURES),
                packages -> partial,
                () -> false,
                () -> false,
                CLOCK,
                List.of(
                        rule("RAPI-ERR-001", absenceEvaluation),
                        findingRule(),
                        rule("RAPI-ERR-009", absenceEvaluation)));

        RestApiReport report = scanner.scan();
        assertSafePartial(report);
        assertThat(dependentEvaluations).hasValue(0);
        assertThat(report.scan().message()).contains("metadata extraction");
        assertThat(report.controllersAnalyzed()).isEqualTo(1);
        assertThat(report.handlersAnalyzed()).isEqualTo(1);
        assertThat(report.rulesEvaluated()).isEqualTo(3);
        assertThat(report.results()).extracting(RestApiRuleResultDto::id).containsExactly("RAPI-TEST-001");

        RestApiReport dismissed = scanner.applyDismissals(report, Set.of("RAPI-TEST-001"));
        assertSafePartial(dismissed);
        assertThat(dismissed.violationsFound()).isZero();
        assertThat(dismissed.results()).hasSize(1).allMatch(RestApiRuleResultDto::dismissed);
        assertThat(dismissed.scan().message()).isEqualTo(report.scan().message());
    }

    @Test
    void completeExtractionStillEvaluatesExceptionAbsenceRules() {
        RestApiScanner scanner = fixtureScanner(
                () -> false,
                () -> false,
                List.of(findingRule("RAPI-ERR-001"), findingRule("RAPI-ERR-009"), findingRule()));

        RestApiReport report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.results())
                .extracting(RestApiRuleResultDto::id)
                .containsExactly("RAPI-ERR-001", "RAPI-ERR-009", "RAPI-TEST-001");
    }

    @Test
    void incompleteExtractionWithoutAnyReliableControllersIsStillPartial() {
        JavaClass unreadable = mock(JavaClass.class);
        when(unreadable.isAnnotatedWith(anyString())).thenThrow(new IllegalStateException(SENSITIVE_FAILURE));
        JavaClasses partial = mock(JavaClasses.class);
        when(partial.iterator()).thenReturn(List.of(unreadable).iterator());
        RestApiScanner scanner =
                new RestApiScanner(() -> List.of(FIXTURES), packages -> partial, () -> false, () -> false, CLOCK);

        RestApiReport report = scanner.scan();
        assertSafePartial(report);
        assertThat(report.controllersAnalyzed()).isZero();
    }

    @Test
    void unavailableFeatureAndConfigurationEvidenceSkipsDependentRulesButRetainsReliableFindings() {
        for (boolean featureFailure : List.of(true, false)) {
            BooleanSupplier unavailable = () -> {
                if (featureFailure) {
                    throw new NoClassDefFoundError(SENSITIVE_FAILURE);
                }
                throw new IllegalStateException(SENSITIVE_FAILURE);
            };
            String dependentId = featureFailure ? "RAPI-DOC-001" : "RAPI-VER-001";
            AtomicInteger dependentEvaluations = new AtomicInteger();
            RestApiRule dependent = rule(dependentId, definition -> {
                dependentEvaluations.incrementAndGet();
                return RestApiRuleSupport.fromViolations(definition, List.of("Unreliable absence-based finding"));
            });
            RestApiScanner scanner = fixtureScanner(
                    featureFailure ? unavailable : () -> false,
                    featureFailure ? () -> false : unavailable,
                    List.of(dependent, findingRule()));

            RestApiReport report = scanner.scan();
            assertSafePartial(report);
            assertThat(dependentEvaluations).hasValue(0);
            assertThat(report.results()).extracting(RestApiRuleResultDto::id).containsExactly("RAPI-TEST-001");
            assertThat(report.rulesEvaluated()).isEqualTo(2);
        }
    }

    @Test
    void failedRuleResultsAndThrownFailuresDoNotDiscardSuccessfulRules() {
        RestApiRule failed =
                rule("RAPI-TEST-002", definition -> RestApiRuleSupport.error(definition, SENSITIVE_FAILURE));
        RestApiRule throwing = rule("RAPI-TEST-003", definition -> {
            throw new IllegalStateException(SENSITIVE_FAILURE);
        });
        RestApiRule unlinkable = rule("RAPI-TEST-004", definition -> {
            throw new NoClassDefFoundError(SENSITIVE_FAILURE);
        });
        RestApiScanner scanner = fixtureScanner(
                () -> false,
                () -> false,
                List.of(failed, throwing, findingRule(), unlinkable, findingRule("RAPI-TEST-005")));

        RestApiReport report = scanner.scan();
        assertSafePartial(report);
        assertThat(report.scan().message()).contains("rule evaluation");
        assertThat(report.rulesEvaluated()).isEqualTo(5);
        assertThat(report.results())
                .extracting(RestApiRuleResultDto::id)
                .containsExactly("RAPI-TEST-001", "RAPI-TEST-005");
        assertThat(report.violationsFound()).isEqualTo(2);
    }

    @Test
    void intentionalUnsupportedAndRetiredRulesDoNotMakeACompleteScanPartial() {
        RestApiScanner scanner = fixtureScanner(
                () -> false,
                () -> false,
                List.of(
                        rule("RAPI-TEST-002", definition -> RestApiRuleSupport.skipped(definition, "Not applicable")),
                        new MutatingItemMethodsTargetResourceRule(),
                        findingRule()));

        RestApiReport report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.results()).extracting(RestApiRuleResultDto::id).containsExactly("RAPI-TEST-001");
    }

    @Test
    void pureJaxRsFrameworkSkipsDoNotMakeTheReportPartial() {
        JavaClasses classes = new ClassFileImporter().importClasses(ScanResource.class);
        RestApiScanner scanner = new RestApiScanner(
                () -> List.of(FIXTURES),
                packages -> classes,
                () -> false,
                () -> false,
                CLOCK,
                List.of(
                        new PreferProblemDetailRule(),
                        new ReturnPagedTypeRule(),
                        rule("RAPI-MAP-006", definition -> {
                            throw new AssertionError("Spring path bindings must not be evaluated on pure JAX-RS");
                        }),
                        rule("RAPI-MAP-009", definition -> {
                            throw new AssertionError("Spring token scoping must not be evaluated on pure JAX-RS");
                        }),
                        findingRule()));

        RestApiReport report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.rulesEvaluated()).isEqualTo(5);
        assertThat(report.results()).extracting(RestApiRuleResultDto::id).containsExactly("RAPI-TEST-001");
    }

    @Test
    void initialReportsDoNotImportOrProbeFeaturesAndScansReadEvidenceAgain() {
        AtomicInteger imports = new AtomicInteger();
        AtomicInteger features = new AtomicInteger();
        AtomicInteger configuration = new AtomicInteger();
        JavaClasses classes = new ClassFileImporter().importClasses(ScanController.class);
        RestApiScanner scanner = new RestApiScanner(
                () -> List.of(FIXTURES),
                packages -> {
                    assertThat(packages).containsExactly(FIXTURES);
                    imports.incrementAndGet();
                    return classes;
                },
                () -> features.incrementAndGet() > 0,
                () -> configuration.incrementAndGet() > 0,
                CLOCK,
                List.of(findingRule()));
        scanner.initialReport();
        scanner.initialReport();
        assertThat(imports).hasValue(0);
        assertThat(features).hasValue(0);
        assertThat(configuration).hasValue(0);

        scanner.scan();
        scanner.scan();
        assertThat(imports).hasValue(2);
        assertThat(features).hasValue(2);
        assertThat(configuration).hasValue(2);
    }

    @Test
    void overlappingScansAreRejectedBeforeImportAndAdmissionIsReleasedAfterward() {
        JavaClasses classes = new ClassFileImporter().importClasses(ScanController.class);
        AtomicInteger imports = new AtomicInteger();
        RestApiScanner[] holder = new RestApiScanner[1];
        holder[0] = new RestApiScanner(
                () -> List.of(FIXTURES),
                packages -> {
                    imports.incrementAndGet();
                    assertThatThrownBy(holder[0]::scan).isInstanceOf(ActionBusyException.class);
                    return classes;
                },
                () -> false,
                () -> false,
                CLOCK,
                List.of(findingRule()));

        assertThat(holder[0].scan().scan().status()).isEqualTo("SCANNED");
        assertThat(holder[0].scan().scan().status()).isEqualTo("SCANNED");
        assertThat(imports).hasValue(2);
    }

    @Test
    void virtualMachineErrorsAreNotSilentlyConvertedIntoReports() {
        RestApiScanner scanner = fixtureScanner(() -> false, () -> false, List.of(rule("RAPI-TEST-002", definition -> {
            throw new OutOfMemoryError("simulated VM failure");
        })));
        assertThatThrownBy(scanner::scan).isInstanceOf(OutOfMemoryError.class);
    }

    private static RestApiScanner fixtureScanner(
            BooleanSupplier feature, BooleanSupplier versioning, List<RestApiRule> rules) {
        JavaClasses classes = new ClassFileImporter().importClasses(ScanController.class);
        return new RestApiScanner(() -> List.of(FIXTURES), packages -> classes, feature, versioning, CLOCK, rules);
    }

    private static RestApiRule findingRule() {
        return findingRule("RAPI-TEST-001");
    }

    private static RestApiRule findingRule(String id) {
        return rule(id, definition -> RestApiRuleSupport.fromViolations(definition, List.of("Reliable finding")));
    }

    private static RestApiRule rule(String id, Function<RestApiRuleDefinition, RestApiRuleResultDto> evaluation) {
        RestApiRuleDefinition definition =
                new RestApiRuleDefinition(id, "Test rule", RestApiCategory.ROUTING, "LOW", "Test", "Review", "");
        return new RestApiRule() {
            @Override
            public RestApiRuleDefinition definition() {
                return definition;
            }

            @Override
            public RestApiRuleResultDto evaluate(RestApiContext context) {
                return evaluation.apply(definition);
            }
        };
    }

    private static void assertSafePartial(RestApiReport report) {
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.scan().scannedAt()).isEqualTo(CLOCK.millis());
        assertThat(report.scan().message()).hasSizeLessThan(500).doesNotContain("password", "sensitive", "\n");
        assertThat(report.toString()).doesNotContain("password", "sensitive");
        assertThat(report.results()).allMatch(result -> RestApiRuleSupport.VIOLATION.equals(result.status()));
    }

    @RestController
    static class ScanController {
        @GetMapping("/widgets")
        String widgets() {
            return "widget";
        }
    }

    @jakarta.ws.rs.Path("/widgets")
    static class ScanResource {
        @jakarta.ws.rs.GET
        public String widgets() {
            return "widget";
        }
    }
}
