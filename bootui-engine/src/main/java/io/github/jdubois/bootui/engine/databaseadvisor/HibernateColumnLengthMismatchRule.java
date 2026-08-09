package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-references a mapped {@code @Column(length=...)} against the physical column's reported size: when
 * the entity permits a longer string than the physical column can hold, an insert/update either silently
 * truncates the value (on a lenient database) or fails outright with a data-truncation/constraint-violation
 * error — a surprise the compile-time entity mapping gives no hint of. Only string/char-family physical
 * columns are compared, since {@code length} is not meaningful for other JDBC type families.
 */
final class HibernateColumnLengthMismatchRule extends AbstractDatabaseAdvisorRule {

    HibernateColumnLengthMismatchRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-004",
                "Mapped column length longer than the physical column size",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Cross-references mapped @Column(length=...) attributes (default 255) against the physical "
                        + "string/char column's reported size.",
                "Align the entity's @Column(length=...) with the physical column size, or widen the physical "
                        + "column via a migration. A mapping that permits more characters than the database column "
                        + "can hold either silently truncates input or fails with a data-truncation error, "
                        + "depending on the database's strictness.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        if (!context.hibernateAvailable()) {
            return skipped("No EntityManagerFactory/Hibernate metamodel is available to cross-reference.");
        }
        if (context.availableSchemas().isEmpty()) {
            return skipped("No physical schema could be read to cross-reference against.");
        }
        List<String> details = new ArrayList<>();
        for (MappedEntityFacts entity : context.hibernateEntities()) {
            if (entity.explicitTableName() == null) {
                continue;
            }
            TableModel table = findTable(context, entity.explicitTableName());
            if (table == null) {
                continue;
            }
            for (MappedColumnFacts column : entity.columns()) {
                checkColumn(entity, column, table, details);
            }
        }
        return violation(details);
    }

    private void checkColumn(
            MappedEntityFacts entity, MappedColumnFacts column, TableModel table, List<String> details) {
        ColumnModel physical = table.column(column.columnName());
        if (physical == null || JdbcTypeFamily.ofJdbcType(physical.typeName()) != JdbcTypeFamily.STRING) {
            return;
        }
        if (physical.size() >= 0 && physical.size() < column.columnLength()) {
            details.add(column.attributeDescription() + " declares @Column(length=" + column.columnLength()
                    + "), which is longer than physical column " + entity.explicitTableName() + "."
                    + column.columnName() + " (" + physical.typeName() + "(" + physical.size()
                    + ")), a truncation risk.");
        }
    }

    private TableModel findTable(DatabaseAdvisorContext context, String tableName) {
        for (SchemaSnapshot schema : context.availableSchemas()) {
            TableModel table = schema.table(tableName);
            if (table != null) {
                return table;
            }
        }
        return null;
    }
}
