package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/** Two indexes on the same table sharing the same leading column redundantly duplicate maintenance cost. */
final class DuplicateIndexRule extends AbstractDatabaseAdvisorRule {

    DuplicateIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-003",
                "Duplicate/redundant indexes",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.LOW,
                "Detects two or more indexes on the same table whose column lists share the same leading column, "
                        + "one of which is a strict prefix of the other (or an exact duplicate).",
                "Every additional index slows down INSERT/UPDATE/DELETE and consumes storage. When one index's "
                        + "column list is a prefix of another's, the shorter one is usually redundant and can be "
                        + "dropped; review both definitions before removing either.",
                "https://use-the-index-luke.com/sql/dml"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : schema.tables()) {
                List<IndexModel> indexes = table.indexes();
                for (int i = 0; i < indexes.size(); i++) {
                    for (int j = i + 1; j < indexes.size(); j++) {
                        IndexModel first = indexes.get(i);
                        IndexModel second = indexes.get(j);
                        if (isPrefixOf(first.columns(), second.columns())
                                || isPrefixOf(second.columns(), first.columns())) {
                            details.add(schema.dataSourceName() + ": " + table.qualifiedName() + " indexes "
                                    + first.name() + " " + first.columns() + " and " + second.name() + " "
                                    + second.columns() + " overlap.");
                        }
                    }
                }
            }
        }
        return violation(details);
    }

    private boolean isPrefixOf(List<String> prefix, List<String> candidate) {
        if (prefix.isEmpty() || prefix.size() > candidate.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equalsIgnoreCase(candidate.get(i))) {
                return false;
            }
        }
        return true;
    }
}
