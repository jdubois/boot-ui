package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.util.List;

/** Reviews nondefault nullability declarations; raw Java types do not establish effective JDBC mappings. */
final class HibernateColumnMismatchRule extends AbstractHibernateCrossReferenceRule {

    HibernateColumnMismatchRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-003",
                "Declared column nullability differs from observed metadata",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Compares @Column(nullable=false) with known nullable physical columns resolved by declaration name. "
                        + "Default-valued nullable=true is unknown, and Java type families are not JDBC mapping evidence.",
                "Review the nondefault DDL declaration against the observed column and intended constraint. "
                        + "Annotation names may be transformed by a physical naming strategy; confirm the effective "
                        + "mapping before changing a migration or annotation.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    boolean hasApplicableDeclarations(MappedEntityFacts entity) {
        return entity.columns().stream().anyMatch(column -> Boolean.FALSE.equals(column.nullable()));
    }

    @Override
    boolean sufficientMetadata(TableModel table) {
        return true;
    }

    @Override
    int checkEntity(
            DatabaseAdvisorContext context,
            MappedTableResolution primary,
            MappedEntityFacts entity,
            List<String> details) {
        int eligible = 0;
        for (MappedColumnFacts column : entity.columns()) {
            if (!Boolean.FALSE.equals(column.nullable())) {
                continue;
            }
            MappedTableResolution resolution = resolveItemTable(context, entity, primary, column.tableName());
            if (!resolution.resolved()) {
                continue;
            }
            SchemaSnapshot schema = resolution.schema();
            TableModel table = resolution.table();
            ColumnModel physical = schema.declaredColumn(table, column.columnName());
            if (physical == null) {
                unknownColumn(context, schema, table, column.columnName(), column.attributeDescription());
                // A mapped column with no physical counterpart is DB-HIB-006's finding.
                continue;
            }
            if (physical.nullability() == null || physical.nullability() == ColumnModel.Nullability.UNKNOWN) {
                unknown(context, column.attributeDescription() + ": physical nullability is unknown.");
                continue;
            }
            eligible++;
            checkNullability(schema, table, column, physical, details);
        }
        return eligible;
    }

    private void checkNullability(
            SchemaSnapshot schema,
            TableModel table,
            MappedColumnFacts column,
            ColumnModel physical,
            List<String> details) {
        if (physical.nullable() && Boolean.FALSE.equals(column.nullable())) {
            details.add(
                    schema.dataSourceName() + ": " + column.attributeDescription() + " declares column "
                            + table.qualifiedName() + "." + column.columnName()
                            + " with nullable=false, while the observed column allows NULL. Review declaration-name correspondence.");
        }
    }
}
