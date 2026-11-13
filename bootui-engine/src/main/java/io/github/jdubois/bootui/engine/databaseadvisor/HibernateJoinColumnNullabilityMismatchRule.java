package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedForeignKeyFacts;

/**
 * Cross-references owning {@code @ManyToOne} and {@code @OneToOne} associations that explicitly declare
 * {@code @JoinColumn(nullable = false)} or {@code optional = false} on a non-inheritance base table against
 * physical column nullability. When Hibernate sees {@code optional = false}, it can emit an {@code INNER JOIN}
 * that silently drops parent rows whose foreign-key column is {@code NULL} in the database.
 */
final class HibernateJoinColumnNullabilityMismatchRule extends AbstractHibernateCrossReferenceRule {

    HibernateJoinColumnNullabilityMismatchRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-012",
                "Non-nullable association join column allows NULL in the observed schema",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Compares explicit @JoinColumn(nullable = false) and @ManyToOne/@OneToOne(optional = false) "
                        + "declarations on non-inheritance base tables with observed physical column nullability. "
                        + "Views, materialized views and inheritance hierarchies are excluded.",
                "Align the association mapping and physical schema: either add a NOT NULL constraint to the join "
                        + "column after verifying that no NULL rows exist, or relax optional = false / nullable = false "
                        + "so Hibernate does not assume an INNER JOIN is safe.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    boolean hasApplicableDeclarations(MappedEntityFacts entity) {
        return entity.foreignKeys().stream().anyMatch(fk -> !fk.nonNullColumns().isEmpty());
    }

    @Override
    boolean supportsRelation(TableModel table) {
        return !"VIEW".equalsIgnoreCase(table.type()) && !"MATERIALIZED VIEW".equalsIgnoreCase(table.type());
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
            DatabaseFindings details) {
        int eligible = 0;
        for (MappedForeignKeyFacts fk : entity.foreignKeys()) {
            if (fk.nonNullColumns().isEmpty()) {
                continue;
            }
            MappedTableResolution resolution = resolveItemTable(context, entity, primary, fk.tableName());
            if (!resolution.resolved()) {
                continue;
            }
            SchemaSnapshot schema = resolution.schema();
            TableModel table = resolution.table();
            for (String declaredColumn : fk.nonNullColumns()) {
                ColumnModel physical = schema.declaredColumn(table, declaredColumn);
                if (physical == null) {
                    unknownColumn(context, schema, table, declaredColumn, fk.attributeDescription());
                    continue;
                }
                if (physical.nullability() == null || physical.nullability() == ColumnModel.Nullability.UNKNOWN) {
                    unknown(context, fk.attributeDescription() + ": physical join-column nullability is unknown.");
                    continue;
                }
                eligible++;
                if (physical.nullable()) {
                    details.add(
                            schema.dataSourceName() + ": " + fk.attributeDescription() + " declares join column "
                                    + table.qualifiedName() + "." + declaredColumn
                                    + " as non-nullable (nullable = false or optional = false), while the observed "
                                    + "column allows NULL.",
                            DatabaseIdentities.mapped(schema, table, fk.attributeDescription(), declaredColumn));
                }
            }
        }
        return eligible;
    }
}
