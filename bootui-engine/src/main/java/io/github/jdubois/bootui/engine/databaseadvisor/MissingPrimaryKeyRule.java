package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/** Tables with no declared primary key make row-level replication, ORM identity, and safe updates harder. */
final class MissingPrimaryKeyRule extends AbstractDatabaseAdvisorRule {

    MissingPrimaryKeyRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-001",
                "Tables without a primary key",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Detects tables reported by DatabaseMetaData.getPrimaryKeys() with no primary key columns.",
                "Declare a primary key (a natural key or a surrogate id) on every table. Without one, ORMs cannot "
                        + "establish row identity, logical replication tools cannot target individual rows, and "
                        + "UPDATE/DELETE statements risk affecting more rows than intended.",
                "https://en.wikipedia.org/wiki/Primary_key"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : schema.tables()) {
                if (table.primaryKeyColumns().isEmpty()) {
                    details.add(schema.dataSourceName() + ": table " + table.qualifiedName() + " has no primary key.");
                }
            }
        }
        return violation(details);
    }
}
