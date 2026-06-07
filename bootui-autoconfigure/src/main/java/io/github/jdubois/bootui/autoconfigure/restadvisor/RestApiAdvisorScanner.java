package io.github.jdubois.bootui.autoconfigure.restadvisor;

import com.tngtech.archunit.core.domain.JavaClasses;
import io.github.jdubois.bootui.core.dto.RestApiAdvisorReport;
import io.github.jdubois.bootui.core.dto.RestApiAdvisorRuleResultDto;
import io.github.jdubois.bootui.core.dto.RestApiAdvisorScanStatusDto;
import io.github.jdubois.bootui.core.dto.RestApiAdvisorSeverityCountDto;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
final class RestApiAdvisorScanner {

    private static final String ANALYZER = "BootUI REST API Advisor";
    private static final String DISCLAIMER =
            "Heuristic, project-agnostic REST API design rules run against the host application's own controllers "
                    + "only. These checks complement, but do not replace, an API design review or contract testing. "
                    + "Security concerns (CORS, authentication, authorization) are covered by the Security Advisor.";
    private static final List<String> SEVERITIES = List.of("HIGH", "MEDIUM", "LOW", "INFO");
    private static final Comparator<RestApiAdvisorRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (RestApiAdvisorRuleResultDto result) -> severityRank(result.severity()))
            .thenComparing(Comparator.comparingInt(RestApiAdvisorRuleResultDto::violationCount)
                    .reversed())
            .thenComparing(RestApiAdvisorRuleResultDto::id);

    private final Supplier<List<String>> basePackagesSupplier;
    private final RestApiAdvisorClassImporter importer;
    private final BooleanSupplier springdocPresent;
    private final Clock clock;

    RestApiAdvisorScanner(
            Supplier<List<String>> basePackagesSupplier,
            RestApiAdvisorClassImporter importer,
            BooleanSupplier springdocPresent,
            Clock clock) {
        this.basePackagesSupplier = basePackagesSupplier;
        this.importer = importer;
        this.springdocPresent = springdocPresent;
        this.clock = clock;
    }

    RestApiAdvisorReport initialReport() {
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

    RestApiAdvisorReport scan() {
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

        RestApiAdvisorContext context = new RestApiAdvisorContext(
                basePackages,
                model.controllers(),
                model.handlers(),
                model.exceptionHandlers(),
                safeSpringdocPresent(),
                model.hasExceptionHandling());

        List<RestApiAdvisorRuleResultDto> results = RestApiAdvisorRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluate(context))
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

    private List<String> safeBasePackages() {
        try {
            List<String> packages = basePackagesSupplier.get();
            return packages == null ? List.of() : List.copyOf(packages);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private boolean safeSpringdocPresent() {
        try {
            return springdocPresent.getAsBoolean();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private RestApiAdvisorReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> basePackages,
            int controllersAnalyzed,
            int handlersAnalyzed,
            int rulesEvaluated,
            List<RestApiAdvisorRuleResultDto> results) {
        List<RestApiAdvisorRuleResultDto> violations = violationResults(results);
        int violationsFound = violations.size();
        RestApiAdvisorScanStatusDto scan = new RestApiAdvisorScanStatusDto(
                ANALYZER,
                status,
                message,
                scannedAt,
                rulesEvaluated,
                controllersAnalyzed,
                handlersAnalyzed,
                violationsFound);
        return new RestApiAdvisorReport(
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

    private List<RestApiAdvisorSeverityCountDto> severityCounts(List<RestApiAdvisorRuleResultDto> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : SEVERITIES) {
            counts.put(severity, 0);
        }
        for (RestApiAdvisorRuleResultDto result : results) {
            if (isViolation(result)) {
                counts.computeIfPresent(result.severity(), (ignored, count) -> count + 1);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new RestApiAdvisorSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<RestApiAdvisorRuleResultDto> violationResults(List<RestApiAdvisorRuleResultDto> results) {
        return results.stream()
                .filter(RestApiAdvisorScanner::isViolation)
                .sorted(IMPORTANCE_ORDER)
                .toList();
    }

    private static int severityRank(String severity) {
        int index = SEVERITIES.indexOf(severity);
        return index >= 0 ? index : SEVERITIES.size();
    }

    private static boolean isViolation(RestApiAdvisorRuleResultDto result) {
        return RestApiAdvisorRuleSupport.VIOLATION.equals(result.status());
    }
}
