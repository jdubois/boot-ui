package io.github.jdubois.bootui.engine.restapi;

import com.tngtech.archunit.core.domain.JavaClasses;
import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.RestApiReport;
import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.core.dto.RestApiScanStatusDto;
import io.github.jdubois.bootui.core.dto.RestApiSeverityCountDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Bounded, on-demand REST API Advisor scanner.
 *
 * <p>The scanner imports only the host application's own classes (bounded to the detected
 * {@code @SpringBootApplication} base packages), derives a read-only handler model once, and runs a
 * fixed registry of curated, project-agnostic REST best-practice rules. Results are heuristic review
 * prompts, not verdicts. Import, extraction, and evaluation failures produce an incomplete report
 * retaining reliable findings instead of claiming a clean scan.</p>
 */
public final class RestApiScanner {

    private static final String ANALYZER = "BootUI REST API Advisor";
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(?:\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*");
    private static final String DISCLAIMER =
            "Heuristic, project-agnostic REST API design rules run against the host application's own controllers "
                    + "only. These checks complement, but do not replace, an API design review or contract testing. "
                    + "Security concerns (CORS, authentication, authorization) are covered by the Security Advisor.";
    private static final Comparator<RestApiRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (RestApiRuleResultDto result) -> SeverityOrder.rank(result.severity()))
            .thenComparing(Comparator.comparingInt(RestApiRuleResultDto::violationCount)
                    .reversed())
            .thenComparing(RestApiRuleResultDto::id);

    private final Supplier<List<String>> basePackagesSupplier;
    private final RestApiClassImporter importer;
    private final BooleanSupplier openApiAnnotationsPresent;
    private final BooleanSupplier globalVersioningConfigured;
    private final Clock clock;
    private final List<RestApiRule> rules;
    private final SingleFlightAction singleFlight = new SingleFlightAction();

    RestApiScanner(
            Supplier<List<String>> basePackagesSupplier,
            RestApiClassImporter importer,
            BooleanSupplier openApiAnnotationsPresent,
            BooleanSupplier globalVersioningConfigured,
            Clock clock) {
        this(
                basePackagesSupplier,
                importer,
                openApiAnnotationsPresent,
                globalVersioningConfigured,
                clock,
                RestApiRuleRegistry.activeRules());
    }

    RestApiScanner(
            Supplier<List<String>> basePackagesSupplier,
            RestApiClassImporter importer,
            BooleanSupplier openApiAnnotationsPresent,
            BooleanSupplier globalVersioningConfigured,
            Clock clock,
            List<RestApiRule> rules) {
        this.basePackagesSupplier = basePackagesSupplier;
        this.importer = importer;
        this.openApiAnnotationsPresent = openApiAnnotationsPresent;
        this.globalVersioningConfigured = globalVersioningConfigured;
        this.clock = clock;
        this.rules = List.copyOf(rules);
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
        return usingClasspath(basePackagesSupplier, openApiAnnotationsPresent, () -> false, clock);
    }

    /**
     * Variant of {@link #usingClasspath(Supplier, BooleanSupplier, Clock)} that also accepts a
     * framework-supplied global API-versioning signal (for example Spring MVC's
     * {@code spring.mvc.apiversion.*} configuration) so rules can honor runtime-wide versioning
     * strategies that are not expressible per-handler in bytecode.
     */
    public static RestApiScanner usingClasspath(
            Supplier<List<String>> basePackagesSupplier,
            BooleanSupplier openApiAnnotationsPresent,
            BooleanSupplier globalVersioningConfigured,
            Clock clock) {
        return new RestApiScanner(
                basePackagesSupplier,
                new ClassFileRestApiImporter(),
                openApiAnnotationsPresent,
                globalVersioningConfigured,
                clock);
    }

    public RestApiReport initialReport() {
        Set<String> failures = new LinkedHashSet<>();
        List<String> basePackages = basePackages(failures);
        return report(
                "NOT_SCANNED",
                failures.isEmpty()
                        ? "REST API rules have not run yet. Click Run REST API checks to analyse the application"
                                + " controllers."
                        : "REST API rules have not run yet. Application base packages could not be read; retry the"
                                + " scan.",
                null,
                basePackages,
                0,
                0,
                0,
                List.of());
    }

    public RestApiReport scan() {
        return singleFlight.run(ActionOperations.REST_API_SCAN, this::doScan);
    }

    private RestApiReport doScan() {
        Set<String> failures = new LinkedHashSet<>();
        List<String> basePackages = basePackages(failures);
        if (basePackages.isEmpty()) {
            return report(
                    "PARTIAL",
                    failures.isEmpty()
                            ? "No application base package was detected. REST API analysis could not run."
                            : incompleteMessage(failures),
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
                    "PARTIAL",
                    "Application classes could not be imported. REST API analysis is incomplete.",
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
                    "PARTIAL",
                    "Application controllers could not be analysed. REST API analysis is incomplete.",
                    clock.millis(),
                    basePackages,
                    0,
                    0,
                    0,
                    List.of());
        }

        if (model.incomplete()) {
            failures.add("controller metadata extraction");
        }
        if (model.controllers().isEmpty()) {
            return report(
                    failures.isEmpty() ? "SCANNED" : "PARTIAL",
                    failures.isEmpty()
                            ? "No supported controller or JAX-RS resource declarations were found under the detected"
                                    + " base package(s)."
                            : incompleteMessage(failures),
                    clock.millis(),
                    basePackages,
                    0,
                    0,
                    0,
                    List.of(),
                    evidence(false, false, failures));
        }

        Boolean openApi = readEvidence(
                openApiAnnotationsPresent::getAsBoolean, null, failures, "OpenAPI annotation availability");
        Boolean versioning = readEvidence(
                globalVersioningConfigured::getAsBoolean, null, failures, "global API versioning configuration");
        RestApiContext context = new RestApiContext(
                basePackages,
                model.controllers(),
                model.handlers(),
                model.exceptionHandlers(),
                Boolean.TRUE.equals(openApi),
                Boolean.TRUE.equals(versioning),
                model.hasExceptionHandling(),
                model.responseStatusExceptionClasses(),
                model.thrownExceptions(),
                model.framework());
        context.evidence().observations(!model.incomplete(), openApi != null, versioning != null);

        List<RestApiRuleResultDto> results = new ArrayList<>();
        boolean usable = false;
        boolean requiredUnknown = false;
        for (RestApiRule rule : rules) {
            context.evidence().reset();
            RestApiRuleResultDto result = evaluate(rule, context);
            if (RestApiRuleSupport.ERROR.equals(result.status())) {
                failures.add("rule evaluation");
            }
            usable |= context.evidence().usable();
            requiredUnknown |= context.evidence().requiredUnknown()
                    || !context.evidence().evaluated() && !RestApiRuleSupport.ERROR.equals(result.status());
            results.add(result);
        }

        return report(
                failures.isEmpty() ? "SCANNED" : "PARTIAL",
                failures.isEmpty()
                        ? "REST API rules completed against "
                                + model.controllers().size() + " controller(s) and "
                                + model.handlers().size() + " handler method(s) under the detected base package(s)."
                        : incompleteMessage(failures),
                clock.millis(),
                basePackages,
                model.controllers().size(),
                model.handlers().size(),
                results.size(),
                results,
                evidence(usable, requiredUnknown, failures));
    }

    private static AdvisorEvidenceDto evidence(boolean usable, boolean requiredUnknown, Set<String> failures) {
        List<String> limitations = failures.stream()
                .map(failure -> failure + ": required REST API evidence was unavailable.")
                .limit(20)
                .toList();
        if (requiredUnknown && limitations.isEmpty())
            limitations = List.of("Required REST API rule observations were unavailable.");
        return new AdvisorEvidenceDto(usable, failures.isEmpty() && !requiredUnknown, limitations);
    }

    static RestApiRuleResultDto evaluate(RestApiRule rule, RestApiContext context) {
        RestApiRuleDefinition definition = rule.definition();
        try {
            RestApiRuleResultDto result = rule.evaluate(context);
            return result == null || RestApiRuleSupport.ERROR.equals(result.status())
                    ? RestApiRuleSupport.error(definition, "Rule evaluation failed; no conclusion was reached.")
                    : result;
        } catch (RuntimeException | LinkageError ex) {
            return RestApiRuleSupport.error(definition, "Rule evaluation failed; no conclusion was reached.");
        }
    }

    private List<String> basePackages(Set<String> failures) {
        return readEvidence(
                () -> {
                    List<String> packages = List.copyOf(basePackagesSupplier.get());
                    if (packages.stream()
                            .anyMatch(name -> !PACKAGE_NAME.matcher(name).matches())) {
                        throw new IllegalArgumentException("Invalid REST analysis package scope");
                    }
                    return packages;
                },
                List.of(),
                failures,
                "application base package discovery");
    }

    private static String incompleteMessage(Set<String> failures) {
        return "REST API analysis is incomplete: " + String.join(", ", failures)
                + " failed. Reliable findings are retained; missing findings do not establish a clean API.";
    }

    private static <T> T readEvidence(Supplier<T> supplier, T fallback, Set<String> failures, String failureCategory) {
        try {
            return supplier.get();
        } catch (RuntimeException | LinkageError ex) {
            failures.add(failureCategory);
            return fallback;
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
        return report(
                status,
                message,
                scannedAt,
                basePackages,
                controllersAnalyzed,
                handlersAnalyzed,
                rulesEvaluated,
                results,
                AdvisorEvidenceDto.unknown());
    }

    private RestApiReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> basePackages,
            int controllersAnalyzed,
            int handlersAnalyzed,
            int rulesEvaluated,
            List<RestApiRuleResultDto> results,
            AdvisorEvidenceDto evidence) {
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
                violations,
                evidence);
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
                marked,
                report.evidence());
    }

    private List<RestApiSeverityCountDto> severityCounts(List<RestApiRuleResultDto> results) {
        Map<String, Integer> counts = SeverityOrder.occurrenceCounts(
                results,
                RestApiScanner::isViolation,
                RestApiRuleResultDto::severity,
                RestApiRuleResultDto::violationCount);
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
