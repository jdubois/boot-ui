package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.sql.DatabaseMetaData;
import java.util.Objects;

/**
 * A foreign key that specifies {@code ON DELETE SET NULL} or {@code ON UPDATE SET NULL} while every
 * referencing child column is {@code NOT NULL}. Executing the referential action on parent delete or update
 * is guaranteed to fail the child table's {@code NOT NULL} constraint.
 *
 * <p>Requiring <em>all</em> referencing child columns to be {@code NOT NULL} avoids false positives on
 * PostgreSQL 15+ column-subset {@code ON DELETE SET NULL (col)} constraints, where {@code getImportedKeys()}
 * reports {@code importedKeySetNull} for every column of the composite constraint even though only a
 * nullable subset is nulled.</p>
 */
final class ForeignKeySetNullOnNotNullColumnRule extends AbstractDatabaseAdvisorRule {

    ForeignKeySetNullOnNotNullColumnRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-011",
                "Foreign key specifies SET NULL on a NOT NULL column",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects foreign keys declaring ON DELETE SET NULL or ON UPDATE SET NULL where all referencing "
                        + "child columns are NOT NULL, which guarantees a NOT NULL constraint failure when the "
                        + "referential action runs.",
                "Align the foreign-key action with the column nullability: either make the referencing column(s) "
                        + "nullable so SET NULL can succeed, or change the referential action to RESTRICT, NO ACTION "
                        + "or CASCADE.",
                "https://www.postgresql.org/docs/current/ddl-constraints.html#DDL-CONSTRAINTS-FK"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        DatabaseFindings details = new DatabaseFindings();
        int eligible = 0;
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : DatabaseAdvisorContext.analyzableTables(schema)) {
                if (!table.metadata().foreignKeysRead() || !table.metadata().columnsRead()) {
                    unknown(context, table.qualifiedName() + ": foreign-key or column metadata is incomplete.");
                }
                for (ForeignKeyModel fk : table.foreignKeys()) {
                    if (Boolean.FALSE.equals(fk.enforced())) {
                        continue;
                    }
                    if (!fk.consistent()) {
                        unknown(context, table.qualifiedName() + ": foreign-key column pairing is incomplete.");
                        continue;
                    }
                    boolean deleteSetNull = Objects.equals(fk.deleteRule(), DatabaseMetaData.importedKeySetNull);
                    boolean updateSetNull = Objects.equals(fk.updateRule(), DatabaseMetaData.importedKeySetNull);
                    boolean actionsKnown =
                            fk.deleteRule() != null && (fk.updateRule() != null || schema.dialect() == Dialect.ORACLE);
                    if (!deleteSetNull && !updateSetNull) {
                        if (!actionsKnown) {
                            unknown(
                                    context,
                                    table.qualifiedName() + ": foreign-key referential action metadata is incomplete.");
                        } else {
                            eligible++;
                        }
                        continue;
                    }
                    boolean anyNullable = false;
                    boolean unknownNullability = false;
                    for (String columnName : fk.columns()) {
                        ColumnModel column = table.column(columnName);
                        if (column == null || column.nullability() == ColumnModel.Nullability.UNKNOWN) {
                            unknownNullability = true;
                        } else if (column.nullable()) {
                            anyNullable = true;
                        }
                    }
                    if (anyNullable) {
                        if (!actionsKnown) {
                            unknown(
                                    context,
                                    table.qualifiedName() + ": foreign-key referential action metadata is incomplete.");
                        } else {
                            eligible++;
                        }
                        continue;
                    }
                    if (unknownNullability) {
                        unknown(context, table.qualifiedName() + ": referencing column nullability is unknown.");
                        continue;
                    }
                    eligible++;
                    String action = deleteSetNull && updateSetNull
                            ? "ON DELETE SET NULL and ON UPDATE SET NULL"
                            : deleteSetNull ? "ON DELETE SET NULL" : "ON UPDATE SET NULL";
                    String trigger =
                            deleteSetNull && updateSetNull ? "delete or update" : deleteSetNull ? "delete" : "update";
                    String columnsText = fk.columns().size() == 1
                            ? "column " + fk.columns().get(0) + " is NOT NULL"
                            : "all referencing columns " + fk.columns() + " are NOT NULL";
                    details.add(
                            schema.dataSourceName() + ": " + table.qualifiedName() + " foreign key " + fk.name() + " "
                                    + fk.columns() + " specifies " + action + ", but " + columnsText
                                    + "; a parent " + trigger + " will fail the NOT NULL constraint.",
                            DatabaseIdentities.table(schema, table, DatabaseIdentities.foreignKey(fk)));
                }
            }
        }
        return assessed(context, eligible, details);
    }
}
