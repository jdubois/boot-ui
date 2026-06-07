package io.github.jdubois.bootui.autoconfigure.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import java.util.ArrayList;
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
    private static final int MAX_DETAIL_CHARS = 240;

    private RestApiRuleSupport() {}

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
    static RestApiRuleResultDto fromViolations(RestApiRuleDefinition definition, List<String> violations) {
        if (violations.isEmpty()) {
            return pass(definition);
        }
        List<String> samples = new ArrayList<>();
        for (String violation : violations) {
            if (samples.size() >= MAX_SAMPLE_VIOLATIONS) {
                break;
            }
            samples.add(detail(violation));
        }
        return result(definition, VIOLATION, violations.size(), samples);
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
