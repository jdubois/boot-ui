package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedForeignKeyFacts;
import java.util.List;

/** Reviews complete association declarations against observed foreign key identities and column pairs. */
final class HibernateMissingForeignKeyConstraintRule extends AbstractHibernateCrossReferenceRule {

    HibernateMissingForeignKeyConstraintRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-007",
                "Declared association has no matching observed foreign key",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Cross-references mapped @ManyToOne/@OneToOne @JoinColumn(s) — including composite ones — "
                        + "against the foreign keys DatabaseMetaData.getImportedKeys() reports for the same table, "
                        + "verifying complete child-to-parent column pairing and the resolved referenced "
                        + "base table. Views and materialized views are not checked as constraint sources or targets. "
                        + "Observed definitions with unknown enforcement remain unknown; historical row validation is not inferred. "
                        + "Associations declaring @ForeignKey(ConstraintMode.NO_CONSTRAINT) are skipped.",
                "Review the association's effective physical mapping and intended database constraint before "
                        + "changing migrations. JPA foreign-key annotations describe schema generation; JPA cascade "
                        + "does not imply ON DELETE CASCADE, and an association alone does not require database cascades.",
                "https://vladmihalcea.com/database-table-relationships/"));
    }

    @Override
    boolean hasApplicableDeclarations(MappedEntityFacts entity) {
        return entity.foreignKeys().stream()
                .anyMatch(key -> key.constraintExpected() && !key.columns().isEmpty());
    }

    @Override
    boolean supportsRelation(TableModel table) {
        return "TABLE".equalsIgnoreCase(table.type()) || "PARTITIONED TABLE".equalsIgnoreCase(table.type());
    }

    @Override
    boolean sufficientMetadata(TableModel table) {
        return table.metadata().columnsRead()
                && table.metadata().foreignKeysRead()
                && !table.metadata().truncated();
    }

    @Override
    int checkEntity(
            DatabaseAdvisorContext context,
            MappedTableResolution primary,
            MappedEntityFacts entity,
            List<String> details) {
        int eligible = 0;
        for (MappedForeignKeyFacts foreignKey : entity.foreignKeys()) {
            if (!foreignKey.constraintExpected() || foreignKey.columns().isEmpty()) {
                continue;
            }
            MappedTableResolution resolution = resolveItemTable(context, entity, primary, foreignKey.tableName());
            if (!resolution.resolved()) {
                continue;
            }
            TableModel table = resolution.table();
            MappedEntityFacts targetDeclaration = new MappedEntityFacts(
                    foreignKey.attributeDescription(),
                    foreignKey.targetTableName(),
                    foreignKey.targetSchema(),
                    foreignKey.targetCatalog(),
                    List.of(),
                    List.of(),
                    List.of());
            MappedTableResolution target = MappedTableResolution.resolve(context, targetDeclaration);
            if (target.resolved() && !supportsRelation(target.table())) {
                continue;
            }
            ForeignKeyMatching.Assessment assessment =
                    ForeignKeyMatching.assess(context, resolution.schema(), table, foreignKey);
            if (assessment.status() == ForeignKeyMatching.Status.UNKNOWN) {
                unknown(context, foreignKey.attributeDescription() + ": " + assessment.reason());
                continue;
            }
            if (assessment.status() == ForeignKeyMatching.Status.NOT_FOUND
                    && resolution.schema().vendorFindings().findings(VendorFindingKinds.ORACLE_CONSTRAINTS).stream()
                            .anyMatch(constraint -> constraint.isForeignKey()
                                    && java.util.Objects.equals(constraint.schema(), table.schema())
                                    && java.util.Objects.equals(constraint.table(), table.name())
                                    && (!constraint.enabled() || !constraint.validatedAgainstExistingRows()))) {
                unknown(
                        context,
                        foreignKey.attributeDescription()
                                + ": Oracle foreign key state needs the constraint-status review; it is not proof of absence.");
                continue;
            }
            eligible++;
            if (assessment.status() == ForeignKeyMatching.Status.NOT_FOUND) {
                details.add(resolution.schema().dataSourceName() + ": " + foreignKey.attributeDescription()
                        + " declares " + table.qualifiedName() + " " + foreignKey.columns()
                        + " as an association, but no matching foreign key was found in the observed metadata. "
                        + "Confirm effective physical naming and intended schema-generation policy.");
            }
        }
        return eligible;
    }
}
