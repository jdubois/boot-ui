package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/** Enabled and validated are independent catalog states, including for generated NOT NULL constraints. */
final class OracleInvalidConstraintRule extends AbstractDatabaseAdvisorRule {

    OracleInvalidConstraintRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-ORACLE-002",
                "Disabled or unvalidated Oracle constraints",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Reviews explicit DISABLED or NOT VALIDATED primary-key, unique, foreign-key and check "
                        + "constraint states, including column NOT NULL checks. State does not prove bad rows.",
                "Review whether the state is intentional before a tested constraint migration. ENABLE NOVALIDATE "
                        + "checks new writes without certifying existing rows; DISABLE VALIDATE can restrict DML. "
                        + "Use the appropriate ENABLE VALIDATE transition after checking data, dependencies and locks; "
                        + "do not assume enabling or validating is lock-free.",
                "https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/constraint.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.schemasOf(Dialect.ORACLE);
        String reason = VendorRuleSupport.skipReason(
                context,
                definition().id(),
                schemas,
                VendorFindingKinds.ORACLE_CONSTRAINTS,
                "No Oracle datasource was detected.");
        if (reason != null) {
            return skipped(reason);
        }
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : schemas) {
            VendorRuleSupport.coverage(context, definition().id(), schema, VendorFindingKinds.ORACLE_CONSTRAINTS);
            for (OracleConstraintDetail constraint :
                    schema.vendorFindings().findings(VendorFindingKinds.ORACLE_CONSTRAINTS)) {
                boolean disabled = "DISABLED".equalsIgnoreCase(constraint.status());
                boolean unvalidated = "NOT VALIDATED".equalsIgnoreCase(constraint.validated());
                if ((!disabled && !constraint.enabled())
                        || (!unvalidated && !constraint.validatedAgainstExistingRows())) {
                    unknown(context, constraint.qualifiedTable() + ": constraint status/validation is unknown.");
                    if (!disabled && !unvalidated) {
                        continue;
                    }
                }
                eligible++;
                if (disabled || unvalidated) {
                    details.add(schema.dataSourceName() + ": " + constraint.describeType() + " constraint "
                            + constraint.constraintName() + " on " + constraint.qualifiedTable() + " is "
                            + constraint.status() + " / " + constraint.validated() + ". "
                            + (constraint.enabled()
                                    ? "New writes are checked; existing rows are not certified by this state."
                                    : constraint.validatedAgainstExistingRows()
                                            ? "DISABLE VALIDATE retains validated state and can restrict DML; it does not mean unrestricted writes."
                                            : "Review the disabled state and any dependent constraints.")
                            + " The snapshot does not prove invalid rows or forgotten validation.");
                }
            }
        }
        return VendorRuleSupport.assessed(this, context, eligible, details);
    }
}
