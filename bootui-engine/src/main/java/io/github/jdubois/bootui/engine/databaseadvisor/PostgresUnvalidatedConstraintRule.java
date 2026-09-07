package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/** Current validation state, without inferring migration history or invalid application rows. */
final class PostgresUnvalidatedConstraintRule extends AbstractDatabaseAdvisorRule {

    PostgresUnvalidatedConstraintRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-PG-003",
                "Unvalidated PostgreSQL constraints",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Detects foreign key and check constraints with pg_constraint.convalidated = false, excluding "
                        + "system and extension-owned objects.",
                "Review whether validation is intentionally pending. For an enforced constraint, plan "
                        + "VALIDATE CONSTRAINT after checking data and operational impact; validation scans "
                        + "existing rows and takes locks, including locks on a referenced table for foreign keys. "
                        + "PostgreSQL 18 NOT ENFORCED constraints require a separate enforcement decision first.",
                "https://www.postgresql.org/docs/current/sql-altertable.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.schemasOf(Dialect.POSTGRESQL);
        String skipReason = VendorRuleSupport.skipReason(
                schemas, VendorFindingKinds.POSTGRES_UNVALIDATED_CONSTRAINTS, "No PostgreSQL datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            VendorRuleSupport.coverage(
                    context, definition().id(), schema, VendorFindingKinds.POSTGRES_UNVALIDATED_CONSTRAINTS);
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.POSTGRES_UNVALIDATED_CONSTRAINTS)) {
                continue;
            }
            for (PostgresUnvalidatedConstraint constraint :
                    schema.vendorFindings().findings(VendorFindingKinds.POSTGRES_UNVALIDATED_CONSTRAINTS)) {
                details.add(schema.dataSourceName() + ": " + constraint.describeType() + " constraint "
                        + constraint.constraint() + " on " + constraint.qualifiedTable()
                        + " is currently not validated. "
                        + (Boolean.TRUE.equals(constraint.enforced())
                                ? "New writes are checked. "
                                : Boolean.FALSE.equals(constraint.enforced())
                                        ? "It is NOT ENFORCED; new writes are not checked. "
                                        : "Enforcement state is unknown. ")
                        + "The snapshot does not establish when this state began or whether any row violates it.");
            }
        }
        int eligible = (int) schemas.stream()
                .filter(schema ->
                        VendorRuleSupport.complete(schema, VendorFindingKinds.POSTGRES_UNVALIDATED_CONSTRAINTS))
                .count();
        return VendorRuleSupport.assessed(this, context, eligible, details);
    }
}
