package io.github.jdubois.bootui.engine.restapi;

import com.tngtech.archunit.core.domain.JavaClasses;
import io.github.jdubois.bootui.core.dto.RestApiReport;
import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.core.dto.RestApiScanStatusDto;
import io.github.jdubois.bootui.core.dto.RestApiSeverityCountDto;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Bounded, on-demand REST API Advisor scanner.
 *
 * <p>The scanner imports only the host application's own classes (bounded to the detected
 * {@code @SpringBootApplication} base packages), derives a read-only handler model once, and runs a
 * fixed registry of curated, project-agnostic REST best-practice rules. Results are heuristic review
 * prompts, not verdicts. Import or model-building failures degrade to a stable "scanned, nothing to
 * analyse" report instead of throwing.</p>
 */
public final class RestApiScanner {

    private static final String ANALYZER = "BootUI REST API Advisor";
    private static final String DISCLAIMER =
            "Heuristic, project-agnostic REST API design rules run against the host application's own controllers "
                    + "only. These checks complement, but do not replace, an API design review or contract testing. "
                    + "Security concerns (CORS, authentication, authorization) are covered by the Security Advisor.";
    /** Rules tied to Spring's RFC 9457 convenience types; skipped when the model contains only JAX-RS resources. */
    private static final Set<String> SPRING_PROBLEM_DETAIL_RULE_IDS = Set.of("RAPI-ERR-003", "RAPI-ERR-006");

    private static final Set<String> SPRING_DATA_PAGINATION_RULE_IDS = Set.of("RAPI-PAGE-002");

    private static final Comparator<RestApiRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (RestApiRuleResultDto result) -> SeverityOrder.rank(result.severity()))
            .thenComparing(Comparator.comparingInt(RestApiRuleResultDto::violationCount)
                    .reversed())
            .thenComparing(RestApiRuleResultDto::id);

    private final Supplier<List<String>> basePackagesSupplier;
    private final RestApiClassImporter importer;
    private final BooleanSupplier openApiAnnotationsPresent;
    private final Clock clock;

    RestApiScanner(
            Supplier<List<String>> basePackagesSupplier,
            RestApiClassImporter importer,
            BooleanSupplier openApiAnnotationsPresent,
            Clock clock) {
        this.basePackagesSupplier = basePackagesSupplier;
        this.importer = importer;
        this.openApiAnnotationsPresent = openApiAnnotationsPresent;
        this.clock = clock;
    }

    /**
     * Builds a scanner that imports the host application's compiled classes from the classpath, bounded
     * to the supplied base packages. This is the entry point adapters wire: the base packages are read
     * <em>live</em> on every scan (the supplier is typically backed by a {@code BasePackageProvider} SPI),
     * the OpenAPI annotation presence (Swagger or MicroProfile OpenAPI) is probed live via
     * {@code openApiAnnotationsPresent}, and the ArchUnit import runs only on demand, never at construction.
     */
    public static RestApiScanner usingClasspath(
            Supplier<List<String>> basePackagesSupplier, BooleanSupplier openApiAnnotationsPresent, Clock clock) {
        return new RestApiScanner(
                basePackagesSupplier, new ClassFileRestApiImporter(), openApiAnnotationsPresent, clock);
    }

    public RestApiReport initialReport() {
        List<String> basePackages = safeBasePackages();
        return report(
                "NOT_SCANNED",
                "REST API rules have not run yet. Click Run REST API checks to analyse the application controllers.",
                null,
                basePackages,
                0,
                0,
                0,
                List.of());
    }

    public RestApiReport scan() {
        List<String> basePackages = safeBasePackages();
        if (basePackages.isEmpty()) {
            return report(
                    "SCANNED",
                    "No application base package was detected, so there were no controllers to analyse.",
                    clock.millis(),
                    basePackages,
                    0,
                    0,
                    0,
                    List.of());
        }

        JavaClasses classes;
        try {
            classes = importer.importPackages(basePackages);
            // Catch LinkageError (e.g. NoClassDefFoundError/ClassFormatError) as well as RuntimeException so a
            // malformed or unresolvable class on the host classpath degrades to a stable report instead of failing.
            // VirtualMachineError (OutOfMemoryError, StackOverflowError) is deliberately not caught here.
        } catch (RuntimeException | LinkageError ex) {
            return report(
                    "SCANNED",
                    "Application classes could not be imported for analysis: " + ex.getMessage(),
                    clock.millis(),
                    basePackages,
                    0,
                    0,
                    0,
                    List.of());
        }

        RestApiHandlerModelBuilder model;
        try {
            model = RestApiHandlerModelBuilder.build(classes);
        } catch (RuntimeException | LinkageError ex) {
            return report(
                    "SCANNED",
                    "Application controllers could not be analysed: " + ex.getMessage(),
                    clock.millis(),
                    basePackages,
                    0,
                    0,
                    0,
                    List.of());
        }

        if (model.controllers().isEmpty()) {
            return report(
                    "SCANNED",
                    "No @Controller/@RestController classes were found under the detected base package(s) to analyse.",
                    clock.millis(),
                    basePackages,
                    0,
                    0,
                    0,
                    List.of());
        }

        RestApiContext context = new RestApiContext(
                basePackages,
                model.controllers(),
                model.handlers(),
                model.exceptionHandlers(),
                safeOpenApiAnnotationsPresent(),
                model.hasExceptionHandling(),
                model.responseStatusExceptionClasses(),
                model.framework());

        List<RestApiRuleResultDto> results = RestApiRuleRegistry.activeRules().stream()
                .map(rule -> evaluate(rule, context))
                .toList();

        return report(
                "SCANNED",
                "REST API rules completed against " + model.controllers().size() + " controller(s) and "
                        + model.handlers().size() + " handler method(s) under the detected base package(s).",
                clock.millis(),
                basePackages,
                model.controllers().size(),
                model.handlers().size(),
                results.size(),
                results);
    }

    static RestApiRuleResultDto evaluate(RestApiRule rule, RestApiContext context) {
        RestApiRuleDefinition definition = rule.definition();
        if (context.jaxRs() && SPRING_PROBLEM_DETAIL_RULE_IDS.contains(definition.id())) {
            return RestApiRuleSupport.skipped(
                    definition,
                    "Not applicable on JAX-RS: RFC 9457 is framework-neutral, but this rule specifically detects"
                            + " Spring ProblemDetail/ErrorResponse return types; the current model cannot reliably"
                            + " identify equivalent JAX-RS problem-details payloads.");
        }
        if (context.jaxRs() && SPRING_DATA_PAGINATION_RULE_IDS.contains(definition.id())) {
            return RestApiRuleSupport.skipped(
                    definition,
                    "Not applicable on JAX-RS: this rule specifically compares Spring Data Pageable inputs with"
                            + " Page/Slice outputs.");
        }
        return rule.evaluate(context);
    }

    private List<String> safeBasePackages() {
        try {
            List<String> packages = basePackagesSupplier.get();
            return packages == null ? List.of() : List.copyOf(packages);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private boolean safeOpenApiAnnotationsPresent() {
        try {
            return openApiAnnotationsPresent.getAsBoolean();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private RestApiReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> basePackages,
            int controllersAnalyzed,
            int handlersAnalyzed,
            int rulesEvaluated,
            List<RestApiRuleResultDto> results) {
        List<RestApiRuleResultDto> violations = violationResults(results);
        int violationsFound = violations.size();
        RestApiScanStatusDto scan = new RestApiScanStatusDto(
                ANALYZER,
                status,
                message,
                scannedAt,
                rulesEvaluated,
                controllersAnalyzed,
                handlersAnalyzed,
                violationsFound);
        return new RestApiReport(
                true,
                DISCLAIMER,
                basePackages,
                controllersAnalyzed,
                handlersAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts(violations),
                scan,
                violations);
    }

    public RestApiReport applyDismissals(RestApiReport report, Set<String> dismissedIds) {
        if (report == null || dismissedIds == null || dismissedIds.isEmpty()) {
            return report;
        }
        List<RestApiRuleResultDto> marked = report.results().stream()
                .map(result -> result.withDismissed(dismissedIds.contains(result.id())))
                .toList();
        List<RestApiRuleResultDto> active =
                marked.stream().filter(result -> !result.dismissed()).toList();
        int violationsFound = active.size();
        RestApiScanStatusDto scan = report.scan();
        RestApiScanStatusDto updatedScan = new RestApiScanStatusDto(
                scan.analyzer(),
                scan.status(),
                scan.message(),
                scan.scannedAt(),
                scan.rulesEvaluated(),
                scan.controllersAnalyzed(),
                scan.handlersAnalyzed(),
                violationsFound);
        return new RestApiReport(
                report.localOnly(),
                report.disclaimer(),
                report.basePackages(),
                report.controllersAnalyzed(),
                report.handlersAnalyzed(),
                report.rulesEvaluated(),
                violationsFound,
                severityCounts(active),
                updatedScan,
                marked);
    }

    private List<RestApiSeverityCountDto> severityCounts(List<RestApiRuleResultDto> results) {
        Map<String, Integer> counts =
                SeverityOrder.counts(results, RestApiScanner::isViolation, RestApiRuleResultDto::severity);
        return counts.entrySet().stream()
                .map(entry -> new RestApiSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<RestApiRuleResultDto> violationResults(List<RestApiRuleResultDto> results) {
        return results.stream()
                .filter(RestApiScanner::isViolation)
                .sorted(IMPORTANCE_ORDER)
                .toList();
    }

    private static boolean isViolation(RestApiRuleResultDto result) {
        return RestApiRuleSupport.VIOLATION.equals(result.status());
    }
}
