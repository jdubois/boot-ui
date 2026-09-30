package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorFindings;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolation;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
import io.github.jdubois.bootui.engine.support.DetailText;
import java.util.List;

/**
 * Helpers shared by REST API Advisor rules for building the per-rule result DTO.
 *
 * <p>Statuses are deliberately small and stable so the browser panel can style them directly:
 * {@code PASS}, {@code VIOLATION}, {@code SKIPPED}, and {@code ERROR}. Sample violations are capped
 * and truncated so a noisy application cannot produce an unbounded payload.</p>
 */
final class RestApiRuleSupport {

    static final String PASS = "PASS";
    static final String VIOLATION = "VIOLATION";
    static final String SKIPPED = "SKIPPED";
    static final String ERROR = "ERROR";

    private static final int MAX_SAMPLE_VIOLATIONS = 10;

    private RestApiRuleSupport() {}

    static RestApiRuleResultDto springProblemDetails(RestApiRuleDefinition definition) {
        return skipped(
                definition,
                "Not applicable on JAX-RS: RFC 9457 is framework-neutral, but this rule specifically detects"
                        + " Spring ProblemDetail/ErrorResponse return types; the current model cannot reliably"
                        + " identify equivalent JAX-RS problem-details payloads.");
    }

    static RestApiRuleResultDto springPathBinding(RestApiRuleDefinition definition) {
        return skipped(
                definition,
                "Not applicable on JAX-RS: this rule checks Spring @PathVariable bindings or unique path-template"
                        + " token names. Jakarta REST uses different parameter binding and token scoping semantics.");
    }

    static RestApiRuleResultDto springDataPagination(RestApiRuleDefinition definition) {
        return skipped(
                definition,
                "Not applicable on JAX-RS: this rule specifically compares Spring Data Pageable inputs with"
                        + " Page/Slice outputs.");
    }

    static RestApiRuleResultDto pass(RestApiRuleDefinition definition) {
        return result(definition, PASS, 0, List.of());
    }

    static RestApiRuleResultDto skipped(RestApiRuleDefinition definition, String reason) {
        return result(definition, SKIPPED, 0, List.of(detail(reason)));
    }

    static RestApiRuleResultDto error(RestApiRuleDefinition definition, String reason) {
        return result(definition, ERROR, 0, List.of(detail(reason)));
    }

    /**
     * Builds a result from a list of violation detail strings: {@code PASS} when empty, otherwise a
     * {@code VIOLATION} carrying the (capped, truncated) samples and the total count.
     */
    static RestApiRuleResultDto fromViolations(
            RestApiContext context, RestApiRuleDefinition definition, List<String> violations) {
        return fromRecords(context, definition, AdvisorViolation.withoutLocations(violations));
    }

    /**
     * Builds a result from findings that may carry locations. The samples are cut from the very records the
     * collector retains, so each sample text keeps its location and matches its retained detail exactly.
     */
    static RestApiRuleResultDto fromViolations(
            RestApiContext context, RestApiRuleDefinition definition, AdvisorFindings findings) {
        return fromRecords(context, definition, findings.list());
    }

    private static RestApiRuleResultDto fromRecords(
            RestApiContext context, RestApiRuleDefinition definition, List<AdvisorViolation> violations) {
        if (violations.isEmpty()) {
            return pass(definition);
        }
        AdvisorViolationCollector collector = context.violationCollector() != null
                ? context.violationCollector()
                : new AdvisorViolationCollector(MAX_SAMPLE_VIOLATIONS);
        List<AdvisorViolation> samples = collector.record(
                definition.id(), violations.size(), violations, RestApiRuleSupport::detail, MAX_SAMPLE_VIOLATIONS);
        return result(definition, VIOLATION, violations.size(), AdvisorViolation.texts(samples))
                .withSampleLocations(AdvisorViolation.locations(samples));
    }

    static RestApiRuleResultDto result(
            RestApiRuleDefinition definition, String status, int violationCount, List<String> sampleViolations) {
        return new RestApiRuleResultDto(
                definition.id(),
                definition.name(),
                definition.category().label(),
                definition.severity(),
                definition.description(),
                status,
                violationCount,
                List.copyOf(sampleViolations),
                definition.recommendation(),
                definition.learnMoreUrl());
    }

    static String detail(String value) {
        return DetailText.sanitize(value);
    }
}
