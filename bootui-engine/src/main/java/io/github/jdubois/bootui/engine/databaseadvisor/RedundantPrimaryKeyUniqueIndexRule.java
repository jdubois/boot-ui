package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An explicit unique index whose column list exactly matches the primary key's columns duplicates the
 * uniqueness guarantee (and storage/maintenance cost) the primary key's own backing index already
 * provides. Only fires when at least two such indexes exist on the table — the primary key's own
 * automatically-created backing index is expected to match its own columns, so a lone match is normal, not
 * redundant.
 */
final class RedundantPrimaryKeyUniqueIndexRule extends AbstractDatabaseAdvisorRule {

    RedundantPrimaryKeyUniqueIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-005",
                "Redundant unique index duplicating the primary key",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.LOW,
                "Detects explicit unique indexes whose column list exactly matches the table's primary key "
                        + "columns, in addition to the primary key's own backing index.",
                "Every additional unique index slows down INSERT/UPDATE/DELETE and consumes storage. When a "
                        + "unique index's column list exactly matches the primary key's columns, it duplicates a "
                        + "uniqueness guarantee the primary key's own backing index already enforces and can "
                        + "usually be dropped.",
                "https://use-the-index-luke.com/sql/dml"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : schema.tables()) {
                checkTable(schema, table, details);
            }
        }
        return violation(details);
    }

    private void checkTable(SchemaSnapshot schema, TableModel table, List<String> details) {
        if (table.primaryKeyColumns().isEmpty()) {
            return;
        }
        Set<String> primaryKeyColumns = normalized(table.primaryKeyColumns());
        List<IndexModel> matching = table.indexes().stream()
                .filter(index -> index.unique() && normalized(index.columns()).equals(primaryKeyColumns))
                .sorted(Comparator.comparing(IndexModel::name, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (matching.size() < 2) {
            return;
        }
        // The first match is treated as the primary key's own backing index; every additional match
        // redundantly duplicates it.
        for (IndexModel redundant : matching.subList(1, matching.size())) {
            details.add(schema.dataSourceName() + ": " + table.qualifiedName() + " index " + redundant.name() + " "
                    + redundant.columns() + " duplicates the primary key columns " + table.primaryKeyColumns()
                    + ".");
        }
    }

    private Set<String> normalized(List<String> columns) {
        return columns.stream().map(column -> column.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}
