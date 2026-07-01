package io.github.jdubois.bootui.engine.architecture;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Helpers shared by architecture rules for building the per-rule result DTO and for evaluating an
 * ArchUnit {@link ArchRule} into a {@code PASS} / {@code VIOLATION} / {@code ERROR} outcome.
 *
 * <p>Statuses are deliberately small and stable so the browser panel can style them directly:
 * {@code PASS}, {@code VIOLATION}, {@code SKIPPED}, and {@code ERROR}.</p>
 */
final class ArchitectureRuleSupport {

    static final String PASS = "PASS";
    static final String VIOLATION = "VIOLATION";
    static final String SKIPPED = "SKIPPED";
    static final String ERROR = "ERROR";

    private static final int MAX_SAMPLE_VIOLATIONS = 10;
    private static final int MAX_DETAIL_CHARS = 240;

    private ArchitectureRuleSupport() {}

    static ArchitectureRuleResultDto pass(ArchitectureRuleDefinition definition) {
        return result(definition, PASS, 0, List.of());
    }

    static ArchitectureRuleResultDto skipped(ArchitectureRuleDefinition definition, String reason) {
        return result(definition, SKIPPED, 0, List.of(detail(reason)));
    }

    static ArchitectureRuleResultDto error(ArchitectureRuleDefinition definition, String reason) {
        return result(definition, ERROR, 0, List.of(detail(reason)));
    }

    static ArchitectureRuleResultDto result(
            ArchitectureRuleDefinition definition, String status, int violationCount, List<String> sampleViolations) {
        return new ArchitectureRuleResultDto(
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

    /**
     * Evaluates an ArchUnit rule against the imported classes and maps the result to a result DTO.
     * Rules that match no classes are reported as passing (ArchUnit empty-should is allowed).
     */
    static ArchitectureRuleResultDto evaluate(
            ArchitectureRuleDefinition definition, ArchRule rule, ArchitectureContext context) {
        EvaluationResult evaluation = rule.allowEmptyShould(true).evaluate(context.classes());
        if (!evaluation.hasViolation()) {
            return pass(definition);
        }
        List<String> details = evaluation.getFailureReport().getDetails();
        List<String> samples = new ArrayList<>();
        for (String detail : details) {
            if (samples.size() >= MAX_SAMPLE_VIOLATIONS) {
                break;
            }
            samples.add(detail(detail));
        }
        return result(definition, VIOLATION, details.size(), samples);
    }

    static String detail(String value) {
        if (value == null) {
            return "No additional detail.";
        }
        String sanitized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (sanitized.isBlank()) {
            return "No additional detail.";
        }
        if (sanitized.length() <= MAX_DETAIL_CHARS) {
            return sanitized;
        }
        return sanitized.substring(0, MAX_DETAIL_CHARS - 3) + "...";
    }
}
