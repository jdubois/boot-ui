package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.List;

/**
 * A MySQL 8.0.16+ {@code CHECK} constraint declared {@code NOT ENFORCED}
 * ({@code information_schema.table_constraints.ENFORCED = 'NO'}) on a base table. It remains in DDL metadata
 * without validating writes or existing rows.
 */
final class MySqlUnenforcedCheckConstraintRule extends AbstractDatabaseAdvisorRule {

    MySqlUnenforcedCheckConstraintRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-MYSQL-004",
                "Unenforced MySQL check constraints",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.LOW,
                "Detects MySQL 8.0.16+ check constraints on base tables with "
                        + "information_schema.table_constraints.ENFORCED = 'NO'. MariaDB does not expose ENFORCED "
                        + "check constraints and is not evaluated.",
                "Review whether NOT ENFORCED is intentional. Enforce the check constraint with "
                        + "ALTER TABLE ... ALTER CHECK ... ENFORCED after verifying that existing rows satisfy it, "
                        + "or drop an obsolete constraint.",
                "https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> mysql = context.schemasOf(Dialect.MYSQL);
        List<SchemaSnapshot> schemas = mysql.stream()
                .filter(schema -> !schema.version().known() || schema.version().atLeast(8, 0, 16))
                .toList();
        if (!mysql.isEmpty() && schemas.isEmpty()) {
            return skipped("Not applicable: check constraints require MySQL 8.0.16 or later.");
        }
        String skipReason = VendorRuleSupport.skipReason(
                context,
                definition().id(),
                schemas,
                VendorFindingKinds.MYSQL_UNENFORCED_CHECK_CONSTRAINTS,
                context.schemasOf(Dialect.MARIADB).isEmpty()
                        ? "No MySQL datasource was detected."
                        : "MariaDB does not support NOT ENFORCED check constraints; only MySQL 8.0.16 or later is evaluated.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        DatabaseFindings details = new DatabaseFindings();
        for (SchemaSnapshot schema : schemas) {
            VendorRuleSupport.coverage(
                    context, definition().id(), schema, VendorFindingKinds.MYSQL_UNENFORCED_CHECK_CONSTRAINTS);
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.MYSQL_UNENFORCED_CHECK_CONSTRAINTS)) {
                continue;
            }
            for (MySqlUnenforcedCheckConstraint constraint :
                    schema.vendorFindings().findings(VendorFindingKinds.MYSQL_UNENFORCED_CHECK_CONSTRAINTS)) {
                details.add(
                        schema.dataSourceName() + ": check constraint " + constraint.constraint() + " on "
                                + constraint.qualifiedTable()
                                + " is NOT ENFORCED (information_schema.table_constraints.ENFORCED = 'NO'); "
                                + "writes and existing rows are not checked.",
                        DatabaseIdentities.table(
                                schema.dataSourceName(),
                                null,
                                constraint.schema(),
                                constraint.table(),
                                "constraint=" + constraint.constraint()));
            }
        }
        int eligible = (int) schemas.stream()
                .filter(schema ->
                        VendorRuleSupport.complete(schema, VendorFindingKinds.MYSQL_UNENFORCED_CHECK_CONSTRAINTS))
                .count();
        return VendorRuleSupport.assessed(this, context, eligible, details);
    }
}
