package io.github.jdubois.bootui.engine.crac;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.github.jdubois.bootui.core.dto.CracFindingDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Helpers shared by readiness checks for building the per-check finding DTO and for evaluating an
 * ArchUnit {@link ArchRule} into an {@code OK} / {@code REVIEW} / {@code ERROR} outcome.
 *
 * <p>Statuses are deliberately small and stable so the browser panel can style them directly:
 * {@code OK} (nothing to review), {@code REVIEW} (occurrences that may need checkpoint/restore
 * handling), {@code SKIPPED}, and {@code ERROR}.</p>
 */
final class CracCheckSupport {

    static final String OK = "OK";
    static final String REVIEW = "REVIEW";
    static final String SKIPPED = "SKIPPED";
    static final String ERROR = "ERROR";

    private static final int MAX_SAMPLE_OCCURRENCES = 10;
    private static final int MAX_DETAIL_CHARS = 240;

    private CracCheckSupport() {}

    static CracFindingDto ok(CracCheckDefinition definition) {
        return result(definition, OK, 0, List.of());
    }

    static CracFindingDto skipped(CracCheckDefinition definition, String reason) {
        return result(definition, SKIPPED, 0, List.of(detail(reason)));
    }

    static CracFindingDto error(CracCheckDefinition definition, String reason) {
        return result(definition, ERROR, 0, List.of(detail(reason)));
    }

    static CracFindingDto review(CracCheckDefinition definition, int occurrenceCount, List<String> samples) {
        return result(definition, REVIEW, occurrenceCount, samples);
    }

    static CracFindingDto result(
            CracCheckDefinition definition, String status, int occurrenceCount, List<String> sampleOccurrences) {
        return new CracFindingDto(
                definition.id(),
                definition.name(),
                definition.category().label(),
                definition.severity(),
                definition.description(),
                status,
                occurrenceCount,
                List.copyOf(sampleOccurrences),
                definition.recommendation(),
                definition.learnMoreUrl());
    }

    /**
     * Evaluates an ArchUnit rule against the imported classes and maps the result to a finding DTO.
     * A rule with no matching classes reports {@code OK} (nothing to review).
     */
    static CracFindingDto evaluate(CracCheckDefinition definition, ArchRule rule, CracContext context) {
        EvaluationResult evaluation = rule.allowEmptyShould(true).evaluate(context.classes());
        if (!evaluation.hasViolation()) {
            return ok(definition);
        }
        List<String> details = evaluation.getFailureReport().getDetails();
        List<String> samples = new ArrayList<>();
        for (String detail : details) {
            if (samples.size() >= MAX_SAMPLE_OCCURRENCES) {
                break;
            }
            samples.add(detail(detail));
        }
        return review(definition, details.size(), samples);
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

    static int maxSampleOccurrences() {
        return MAX_SAMPLE_OCCURRENCES;
    }
}
