package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;

/** Retired ID retained only for compatibility; NULL uniqueness semantics can be intentional. */
final class NullableColumnCompositeUniquenessRule extends AbstractDatabaseAdvisorRule {
    NullableColumnCompositeUniquenessRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-009",
                "Retired: composite unique mixed nullability",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.LOW,
                "Retired: mixed nullability does not establish defective unique-constraint semantics.",
                "Review business requirements rather than inferring them from nullable key components.",
                "https://www.postgresql.org/docs/current/ddl-constraints.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        return skipped("Retired: mixed unique-key nullability does not establish a defect.");
    }
}
