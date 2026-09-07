package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;

/** Retired ID retained only for compatibility; mixed nullability does not establish a defect. */
final class CompositeForeignKeyPartialNullabilityRule extends AbstractDatabaseAdvisorRule {
    CompositeForeignKeyPartialNullabilityRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-008",
                "Retired: composite FK mixed nullability",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.LOW,
                "Retired: optional composite relationships can intentionally mix nullability.",
                "Preserve business integrity; mixed nullability alone is not a reason to change a column.",
                "https://www.postgresql.org/docs/current/ddl-constraints.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        return skipped("Retired: mixed FK nullability does not establish a defect or business intent.");
    }
}
