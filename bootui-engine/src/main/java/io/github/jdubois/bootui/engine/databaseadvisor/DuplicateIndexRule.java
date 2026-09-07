package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/** Exact ordinary-index definition candidates only; a longer index does not prove redundancy. */
final class DuplicateIndexRule extends AbstractDatabaseAdvisorRule {

    DuplicateIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-003",
                "Exact duplicate index candidates",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.LOW,
                "Compares fully known ordinary index keys, payload, ordering, collation, method and state. "
                        + "Prefixes, expressions, predicates, special indexes and unknown definitions are not compared.",
                "Review both definitions, constraint dependencies, index hints and workload before considering "
                        + "removal. Even an exact structural duplicate does not prove workload-neutral removal.",
                "https://use-the-index-luke.com/sql/dml"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : DatabaseAdvisorContext.analyzableTables(schema)) {
                List<IndexModel> possible = table.indexes().stream()
                        .filter(index -> !table.partitionParent() && comparisonCandidate(index))
                        .toList();
                List<IndexModel> candidates =
                        possible.stream().filter(IndexModel::comparable).toList();
                if (!table.metadata().indexesRead() || (possible.size() > 1 && candidates.size() < possible.size())) {
                    unknown(
                            context,
                            schema.dataSourceName() + ": " + table.qualifiedName()
                                    + " has incomplete or unsupported index comparison semantics.");
                }
                for (int i = 0; i < candidates.size(); i++) {
                    for (int j = i + 1; j < candidates.size(); j++) {
                        eligible++;
                        IndexModel first = candidates.get(i);
                        IndexModel second = candidates.get(j);
                        if (first.exactDuplicateOf(second)) {
                            details.add(schema.dataSourceName() + ": " + table.qualifiedName() + " indexes "
                                    + first.name() + " and " + second.name()
                                    + " have identical observed ordinary-index definitions; review dependencies.");
                        }
                    }
                }
            }
        }
        return assessed(context, eligible, details);
    }

    private static boolean comparisonCandidate(IndexModel index) {
        return !index.automatic()
                && index.backingConstraint() == null
                && !index.partial()
                && !index.partitioned()
                && !index.specialized()
                && !index.hasExpressionKeyPart()
                && !index.hasPrefixKeyPart()
                && !index.invalid();
    }
}
