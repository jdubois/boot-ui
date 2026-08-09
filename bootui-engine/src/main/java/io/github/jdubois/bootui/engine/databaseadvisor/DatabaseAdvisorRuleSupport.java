package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.support.DetailText;
import java.util.ArrayList;
import java.util.List;

final class DatabaseAdvisorRuleSupport {

    static final String PASS = "PASS";
    static final String VIOLATION = "VIOLATION";
    static final String SKIPPED = "SKIPPED";
    static final String ERROR = "ERROR";

    static final String CRITICAL = "CRITICAL";
    static final String HIGH = "HIGH";
    static final String MEDIUM = "MEDIUM";
    static final String LOW = "LOW";
    static final String INFO = "INFO";

    private static final int MAX_SAMPLE_VIOLATIONS = 10;

    private DatabaseAdvisorRuleSupport() {}

    static DatabaseAdvisorRuleResultDto pass(DatabaseAdvisorRuleDefinition definition) {
        return result(definition, PASS, 0, List.of());
    }

    static DatabaseAdvisorRuleResultDto skipped(DatabaseAdvisorRuleDefinition definition, String reason) {
        return result(definition, SKIPPED, 0, List.of(detail(reason)));
    }

    static DatabaseAdvisorRuleResultDto error(DatabaseAdvisorRuleDefinition definition, String reason) {
        return result(definition, ERROR, 0, List.of(detail(reason)));
    }

    static DatabaseAdvisorRuleResultDto violation(DatabaseAdvisorRuleDefinition definition, List<String> details) {
        return result(definition, VIOLATION, details.size(), samples(details));
    }

    static DatabaseAdvisorRuleResultDto result(
            DatabaseAdvisorRuleDefinition definition,
            String status,
            int violationCount,
            List<String> sampleViolations) {
        return new DatabaseAdvisorRuleResultDto(
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

    private static List<String> samples(List<String> details) {
        List<String> samples = new ArrayList<>();
        for (String detail : details) {
            if (samples.size() >= MAX_SAMPLE_VIOLATIONS) {
                break;
            }
            samples.add(detail(detail));
        }
        return samples;
    }
}
