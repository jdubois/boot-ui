package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-references mapped {@code @Column(name=...)} attributes against the physical column: a coarse
 * type-family mismatch (e.g. a {@code String} attribute mapped to a numeric column) or a nullability
 * mismatch where the database is stricter than the mapping (a {@code NOT NULL} column mapped to a
 * nullable attribute, or vice versa) usually surfaces at runtime as a surprising constraint violation or
 * class-cast/conversion failure rather than at compile time.
 */
final class HibernateColumnMismatchRule extends AbstractDatabaseAdvisorRule {

    HibernateColumnMismatchRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-003",
                "Mapped column type/nullability mismatch",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Cross-references mapped @Column(name=...) attributes against the physical column's reported JDBC "
                        + "type family and nullability.",
                "Align the entity mapping with the physical column: a coarse type-family mismatch (text vs. "
                        + "numeric vs. date/time) usually fails at read/conversion time, and a NOT NULL column "
                        + "mapped as nullable can throw a constraint violation only under specific code paths.",
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
                ColumnModel physical = table.column(column.columnName());
                if (physical == null) {
                    continue;
                }
                checkTypeFamily(entity, column, physical, details);
                checkNullability(entity, column, physical, details);
            }
        }
        return violation(details);
    }

    private void checkTypeFamily(
            MappedEntityFacts entity, MappedColumnFacts column, ColumnModel physical, List<String> details) {
        JdbcTypeFamily javaFamily = JdbcTypeFamily.ofJavaType(column.javaTypeSimpleName());
        JdbcTypeFamily columnFamily = JdbcTypeFamily.ofJdbcType(physical.typeName());
        if (javaFamily != JdbcTypeFamily.OTHER && columnFamily != JdbcTypeFamily.OTHER && javaFamily != columnFamily) {
            details.add(column.attributeDescription() + " (" + column.javaTypeSimpleName() + ") maps column "
                    + entity.explicitTableName() + "." + column.columnName() + " (" + physical.typeName()
                    + "), a type-family mismatch.");
        }
    }

    private void checkNullability(
            MappedEntityFacts entity, MappedColumnFacts column, ColumnModel physical, List<String> details) {
        if (!physical.nullable() && column.nullable()) {
            details.add(column.attributeDescription() + " maps column " + entity.explicitTableName() + "."
                    + column.columnName()
                    + ", which is NOT NULL in the database but is mapped as nullable in the entity.");
        } else if (physical.nullable() && !column.nullable()) {
            details.add(column.attributeDescription() + " maps column " + entity.explicitTableName() + "."
                    + column.columnName()
                    + ", which allows NULL in the database but is mapped as non-nullable in the entity.");
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
