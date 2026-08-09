package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * A foreign key column whose JDBC-reported type family (e.g. numeric vs. string) disagrees with the
 * referenced table's primary key column family — for example an {@code INT} foreign key referencing a
 * {@code BIGINT} primary key. This inspects the physical schema only (foreign keys, primary keys, and
 * column types reported by {@code DatabaseMetaData}), independent of any Hibernate mapping.
 */
final class ForeignKeyTypeMismatchRule extends AbstractDatabaseAdvisorRule {

    ForeignKeyTypeMismatchRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-004",
                "Foreign key column type mismatch with the referenced primary key",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Compares each foreign key column's JDBC-reported type family against the referenced table's "
                        + "primary key column type family.",
                "Align the foreign key column's type with the referenced primary key's type (e.g. both BIGINT). A "
                        + "coarse type-family mismatch between a child's foreign key and its parent's primary key "
                        + "can silently truncate values, defeat query planner join optimizations, or fail outright "
                        + "on stricter databases.",
                "https://use-the-index-luke.com/sql/join/foreign-keys"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : schema.tables()) {
                for (ForeignKeyModel foreignKey : table.foreignKeys()) {
                    checkForeignKey(schema, table, foreignKey, details);
                }
            }
        }
        return violation(details);
    }

    private void checkForeignKey(
            SchemaSnapshot schema, TableModel table, ForeignKeyModel foreignKey, List<String> details) {
        TableModel referenced = schema.table(foreignKey.referencedTable());
        if (referenced == null) {
            return;
        }
        List<String> referencedPrimaryKey = referenced.primaryKeyColumns();
        int columnCount = Math.min(foreignKey.columns().size(), referencedPrimaryKey.size());
        for (int i = 0; i < columnCount; i++) {
            ColumnModel fkColumn = table.column(foreignKey.columns().get(i));
            ColumnModel pkColumn = referenced.column(referencedPrimaryKey.get(i));
            if (fkColumn == null || pkColumn == null) {
                continue;
            }
            JdbcTypeFamily fkFamily = JdbcTypeFamily.ofJdbcType(fkColumn.typeName());
            JdbcTypeFamily pkFamily = JdbcTypeFamily.ofJdbcType(pkColumn.typeName());
            if (fkFamily != JdbcTypeFamily.OTHER && pkFamily != JdbcTypeFamily.OTHER && fkFamily != pkFamily) {
                details.add(schema.dataSourceName() + ": " + table.qualifiedName() + "." + fkColumn.name() + " ("
                        + fkColumn.typeName() + ") references " + referenced.qualifiedName() + "." + pkColumn.name()
                        + " (" + pkColumn.typeName() + "), a type-family mismatch.");
            }
        }
    }
}
