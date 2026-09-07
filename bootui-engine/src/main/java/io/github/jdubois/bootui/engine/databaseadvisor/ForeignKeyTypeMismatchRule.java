package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * A foreign key column whose type disagrees with the column it actually references — a narrower integer, a
 * different signedness, a smaller numeric precision/scale, a shorter declared length, or an outright
 * different type family.
 *
 * <p>Two corrections matter here. First, the comparison targets {@code PKCOLUMN_NAME} — the column the
 * constraint really points at, which may be an alternate unique key rather than the primary key — instead of
 * the referenced table's primary key by position, which reported mismatches that did not exist for any
 * foreign key not referencing the full primary key. Second, matching type families is not enough: an
 * {@code INT} referencing a {@code BIGINT} is the classic silent failure this rule exists to catch, and both
 * are "numeric".</p>
 */
final class ForeignKeyTypeMismatchRule extends AbstractDatabaseAdvisorRule {

    ForeignKeyTypeMismatchRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        "DB-SCHEMA-004",
                        "Foreign key column type mismatch with the referenced column",
                        DatabaseAdvisorCategory.SCHEMA,
                        DatabaseAdvisorRuleSupport.MEDIUM,
                        "Compares each foreign key column against the column it actually references "
                                + "(getImportedKeys().PKCOLUMN_NAME, which may be an alternate unique key), including type "
                                + "family, integer width and signedness, numeric precision/scale, and declared length.",
                        "Review whether the narrower child domain is intentional. It cannot represent every value "
                                + "the referenced declaration permits, but this does not prove invalid existing rows, "
                                + "a broken foreign key, or incompatible vendor coercions.",
                        "https://vladmihalcea.com/how-to-fix-wrong-column-type-encountered-schema-validation-errors-with-jpa-and-hibernate/"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : DatabaseAdvisorContext.analyzableTables(schema)) {
                if (!table.metadata().foreignKeysRead() || !table.metadata().columnsRead()) {
                    unknown(context, table.qualifiedName() + ": foreign-key or column metadata is incomplete.");
                }
                for (ForeignKeyModel foreignKey : table.foreignKeys()) {
                    TableModel parent = schema.exactTable(
                            foreignKey.referencedCatalog(),
                            foreignKey.referencedSchema(),
                            foreignKey.referencedTable());
                    if (!foreignKey.consistent() || parent == null) {
                        unknown(context, table.qualifiedName() + ": referenced column domain cannot be resolved.");
                        continue;
                    }
                    boolean comparable = true;
                    for (int i = 0; i < foreignKey.columns().size(); i++) {
                        comparable &= ColumnTypeCompatibility.comparable(
                                table.column(foreignKey.columns().get(i)),
                                parent.column(foreignKey.referencedColumns().get(i)));
                    }
                    if (!comparable) {
                        unknown(
                                context,
                                table.qualifiedName() + ": at least one referenced domain comparison is unknown.");
                    } else {
                        eligible++;
                    }
                    checkForeignKey(schema, table, foreignKey, details);
                }
            }
        }
        return assessed(context, eligible, details);
    }

    private void checkForeignKey(
            SchemaSnapshot schema, TableModel table, ForeignKeyModel foreignKey, List<String> details) {
        if (!foreignKey.consistent()) {
            return;
        }
        TableModel referenced = schema.exactTable(
                foreignKey.referencedCatalog(), foreignKey.referencedSchema(), foreignKey.referencedTable());
        if (referenced == null) {
            return;
        }
        for (int i = 0; i < foreignKey.columns().size(); i++) {
            ColumnModel childColumn = table.column(foreignKey.columns().get(i));
            ColumnModel parentColumn =
                    referenced.column(foreignKey.referencedColumns().get(i));
            String mismatch = ColumnTypeCompatibility.mismatch(childColumn, parentColumn);
            if (mismatch == null) {
                continue;
            }
            details.add(schema.dataSourceName() + ": " + table.qualifiedName() + "." + childColumn.name() + " ("
                    + childColumn.describeType() + ") references " + referenced.qualifiedName() + "."
                    + parentColumn.name() + " (" + parentColumn.describeType() + ") with " + mismatch + ".");
        }
    }
}
