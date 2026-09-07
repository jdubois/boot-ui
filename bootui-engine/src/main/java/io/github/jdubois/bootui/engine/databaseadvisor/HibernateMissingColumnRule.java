package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedForeignKeyFacts;
import java.util.List;

/** Reviews annotation column names against a complete column inventory, not Hibernate's effective mapping. */
final class HibernateMissingColumnRule extends AbstractHibernateCrossReferenceRule {

    HibernateMissingColumnRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-006",
                "Declared column name not found in the observed relation",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Cross-references explicitly named @Column(name=...) attributes and @JoinColumn(s) join columns "
                        + "against DatabaseMetaData.getColumns() for the resolved physical table.",
                "Confirm the effective physical naming strategy and attribute placement, then compare the declaration "
                        + "with migrations. Annotation names may differ from runtime physical names; this review "
                        + "does not establish that queries will fail.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    boolean hasApplicableDeclarations(MappedEntityFacts entity) {
        return !entity.columns().isEmpty()
                || entity.foreignKeys().stream().anyMatch(key -> !key.columns().isEmpty());
    }

    @Override
    boolean sufficientMetadata(TableModel table) {
        return table.metadata().columnsRead() && !table.metadata().truncated();
    }

    @Override
    int checkEntity(
            DatabaseAdvisorContext context,
            MappedTableResolution primary,
            MappedEntityFacts entity,
            List<String> details) {
        int eligible = 0;
        for (MappedColumnFacts column : entity.columns()) {
            MappedTableResolution resolution = resolveItemTable(context, entity, primary, column.tableName());
            if (!resolution.resolved()) {
                continue;
            }
            TableModel table = resolution.table();
            ColumnModel physical = resolution.schema().declaredColumn(table, column.columnName());
            if (physical == null
                    && unknownColumn(
                            context, resolution.schema(), table, column.columnName(), column.attributeDescription())) {
                continue;
            }
            eligible++;
            if (physical == null) {
                details.add(resolution.schema().dataSourceName() + ": " + column.attributeDescription()
                        + " declares column " + table.qualifiedName() + "." + column.columnName()
                        + ", whose declaration name was not found in the observed relation's column inventory.");
            }
        }
        for (MappedForeignKeyFacts foreignKey : entity.foreignKeys()) {
            if (foreignKey.columns().isEmpty()) {
                continue;
            }
            MappedTableResolution resolution = resolveItemTable(context, entity, primary, foreignKey.tableName());
            if (!resolution.resolved()) {
                continue;
            }
            TableModel table = resolution.table();
            for (String column : foreignKey.columns()) {
                ColumnModel physical = resolution.schema().declaredColumn(table, column);
                if (physical == null
                        && unknownColumn(
                                context, resolution.schema(), table, column, foreignKey.attributeDescription())) {
                    continue;
                }
                eligible++;
                if (physical == null) {
                    details.add(resolution.schema().dataSourceName() + ": " + foreignKey.attributeDescription()
                            + " declares join column " + table.qualifiedName() + "." + column
                            + ", whose declaration name was not found in the observed relation's column inventory.");
                }
            }
        }
        return eligible;
    }
}
