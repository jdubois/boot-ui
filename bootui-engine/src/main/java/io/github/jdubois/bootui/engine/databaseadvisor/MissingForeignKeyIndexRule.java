package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreign key columns with no supporting index force full table scans on joins and on the parent side of
 * cascading deletes/updates. This inspects the physical schema only (foreign keys and indexes reported by
 * {@code DatabaseMetaData}), independent of any Hibernate mapping.
 */
final class MissingForeignKeyIndexRule extends AbstractDatabaseAdvisorRule {

    MissingForeignKeyIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-002",
                "Foreign key columns without a supporting index",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects foreign key columns (from DatabaseMetaData.getImportedKeys()) that are not the leading "
                        + "column of any index on the same table.",
                "Create an index leading on the foreign key column(s). Most databases do not automatically index "
                        + "foreign keys, so joins against the referenced table and cascading deletes/updates on the "
                        + "parent row can force a full table scan on the child table.",
                "https://use-the-index-luke.com/sql/join/foreign-keys"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : schema.tables()) {
                for (ForeignKeyModel foreignKey : table.foreignKeys()) {
                    if (foreignKey.columns().isEmpty()) {
                        continue;
                    }
                    String leading = foreignKey.columns().get(0);
                    if (!table.hasLeadingIndexOn(leading)) {
                        details.add(schema.dataSourceName() + ": " + table.qualifiedName() + "." + leading
                                + " is a foreign key column with no leading index (referencing "
                                + foreignKey.referencedTable() + ").");
                    }
                }
            }
        }
        return violation(details);
    }
}
