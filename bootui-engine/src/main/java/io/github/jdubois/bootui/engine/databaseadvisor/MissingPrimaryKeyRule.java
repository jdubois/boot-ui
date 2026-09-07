package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tables with no declared primary key make row-level replication, ORM identity, and safe updates harder.
 *
 * <p>Three classes of table are deliberately excluded, because a missing primary key there is either not the
 * user's decision or not a defect: extension-owned tables (PostgreSQL {@code pg_depend deptype = 'e'}, e.g.
 * PostGIS or {@code pg_stat_statements} bookkeeping), migration bookkeeping tables owned by Flyway/Liquibase,
 * and PostgreSQL child partitions, whose structure comes from the partitioned parent that is analyzed in
 * their place. A table whose {@code getPrimaryKeys()} call failed is skipped too — an unreadable primary key
 * is not an absent one.</p>
 */
final class MissingPrimaryKeyRule extends AbstractDatabaseAdvisorRule {

    /**
     * Bookkeeping tables created by the migration tools themselves. Their shape is owned by Flyway/Liquibase
     * (Liquibase's {@code DATABASECHANGELOG} genuinely has no primary key by design), so telling the user to
     * add one is advice they cannot act on.
     */
    private static final Set<String> MIGRATION_TABLES = Set.of(
            "flyway_schema_history",
            "schema_version",
            "databasechangelog",
            "databasechangeloglock",
            "changelog",
            "changeloglock");

    MissingPrimaryKeyRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-001",
                "Tables without a primary key",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Detects application tables reported by DatabaseMetaData.getPrimaryKeys() with no primary key "
                        + "columns, excluding system, temporary, extension-owned and migration bookkeeping tables, "
                        + "and PostgreSQL child partitions.",
                "Review whether a declared primary key fits this table's identity and consumers. "
                        + "No observed primary key does not prove unsafe data or a broken ORM/replication setup; "
                        + "empty JDBC metadata can also reflect driver or privilege limitations.",
                "https://en.wikipedia.org/wiki/Primary_key"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : DatabaseAdvisorContext.analyzableTables(schema)) {
                if (isExcluded(table)) {
                    continue;
                }
                if (!table.metadata().primaryKeyRead()
                        || (table.primaryKeyColumns().isEmpty()
                                && !table.metadata().columnsRead())) {
                    unknown(context, table.qualifiedName() + ": primary-key or column metadata is incomplete.");
                    continue;
                }
                eligible++;
                if (table.primaryKeyColumns().isEmpty()) {
                    details.add(
                            schema.dataSourceName() + ": table " + table.qualifiedName()
                                    + " has no primary key reported by JDBC; verify driver/privilege coverage before a migration.");
                }
            }
        }
        return assessed(context, eligible, details);
    }

    private boolean isExcluded(TableModel table) {
        if (table.extensionOwned()) {
            return true;
        }
        String name = table.name() == null ? "" : table.name().toLowerCase(Locale.ROOT);
        return MIGRATION_TABLES.contains(name);
    }
}
