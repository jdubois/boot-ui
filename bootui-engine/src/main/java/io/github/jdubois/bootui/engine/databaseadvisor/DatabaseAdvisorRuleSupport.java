package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorFindingIdentity;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolation;
import io.github.jdubois.bootui.engine.support.CredentialRedaction;
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

    /** The reason of a rule that ran over the readable schemas and found nothing eligible to assess. */
    static final String NO_TARGETS = "No applicable targets were available for this check.";

    private static final int MAX_SAMPLE_VIOLATIONS = 10;

    private DatabaseAdvisorRuleSupport() {}

    /** True for a rule that found no target left to assess: a finding it reported earlier has no target any more. */
    static boolean noTargets(DatabaseAdvisorRuleResultDto result) {
        return SKIPPED.equals(result.status()) && result.sampleViolations().contains(detail(NO_TARGETS));
    }

    static DatabaseAdvisorRuleResultDto pass(DatabaseAdvisorRuleDefinition definition) {
        return result(definition, PASS, 0, List.of());
    }

    static DatabaseAdvisorRuleResultDto skipped(DatabaseAdvisorRuleDefinition definition, String reason) {
        return result(definition, SKIPPED, 0, List.of(detail(reason)));
    }

    static DatabaseAdvisorRuleResultDto error(DatabaseAdvisorRuleDefinition definition, String reason) {
        return result(definition, ERROR, 0, List.of(detail(reason)));
    }

    static DatabaseAdvisorRuleResultDto violation(
            DatabaseAdvisorContext context, DatabaseAdvisorRuleDefinition definition, DatabaseFindings findings) {
        List<AdvisorViolation> details = findings.list();
        assert details.stream()
                        .allMatch(detail -> AdvisorFindingIdentity.VALID
                                .matcher(detail.identity())
                                .matches())
                : definition.id() + " reported a finding without a valid canonical identity";
        if (context.violationCollector() != null) {
            context.violationCollector()
                    .record(definition.id(), details.size(), details, DatabaseAdvisorRuleSupport::detail, 0);
        }
        return result(definition, VIOLATION, details.size(), samples(AdvisorViolation.texts(details)));
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
        return DetailText.sanitize(CredentialRedaction.redact(value));
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
