package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

final class RedundantPrimaryKeyUniqueIndexRule extends AbstractDatabaseAdvisorRule {

    RedundantPrimaryKeyUniqueIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-005",
                "Unique index duplicating a proven primary-key backing index",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.LOW,
                "Requires actual constraint/index linkage and completely known equivalent ordinary-index definitions.",
                "Review definitions, payload, constraint ownership and dependencies before considering removal. "
                        + "Matching primary-key columns alone is not evidence that an index is redundant.",
                "https://use-the-index-luke.com/sql/dml"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : DatabaseAdvisorContext.analyzableTables(schema)) {
                if (table.primaryKeyColumns().isEmpty()) {
                    continue;
                }
                if (!table.metadata().indexesRead() || !table.metadata().primaryKeyRead()) {
                    unknown(context, table.qualifiedName() + ": primary-key or index inventory is incomplete.");
                }
                if (table.partitionParent() || table.indexes().size() < 2) {
                    continue;
                }
                IndexModel backing = table.primaryKeyBackingIndex();
                if (backing == null || !backing.comparable()) {
                    unknown(
                            context,
                            schema.dataSourceName() + ": " + table.qualifiedName()
                                    + " primary-key backing identity or full index semantics are unknown.");
                    continue;
                }
                for (IndexModel index : table.indexes()) {
                    if (index == backing || !index.unique() || index.backingConstraint() != null || index.automatic()) {
                        continue;
                    }
                    if (!index.comparable()) {
                        unknown(context, "An extra unique index has unknown comparison semantics.");
                        continue;
                    }
                    eligible++;
                    if (index.exactDuplicateOf(backing)) {
                        details.add(schema.dataSourceName() + ": " + table.qualifiedName() + " unique index "
                                + index.name() + " has the same observed definition as primary-key backing index "
                                + backing.name() + "; review ownership and dependencies.");
                    }
                }
            }
        }
        return assessed(context, eligible, details);
    }
}
