package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedUniqueConstraintFacts;
import java.util.List;

/** Reviews declared uniqueness separately from optimizer index visibility and access-path usability. */
final class HibernateMissingUniqueIndexRule extends AbstractHibernateCrossReferenceRule {

    HibernateMissingUniqueIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-005",
                "Declared uniqueness has no observed enforcing key",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.HIGH,
                "Reviews @Column(unique=true) and table unique declarations against observed enforcing base-table keys. "
                        + "Views and materialized views are outside this constraint check. "
                        + "Optimizer visibility is independent of uniqueness enforcement. Stronger subset or "
                        + "prefix uniqueness can cover the declaration only when dialect null semantics preserve "
                        + "the guarantee; unknown enforcement is not absence.",
                "Confirm the effective physical mapping and intended uniqueness, including null semantics, "
                        + "before adding a migration. A stronger existing constraint may reject more values than "
                        + "the declaration requires and should not be duplicated.",
                "https://www.postgresql.org/docs/current/indexes-unique.html"));
    }

    @Override
    boolean hasApplicableDeclarations(MappedEntityFacts entity) {
        return entity.uniqueConstraints().stream()
                .anyMatch(constraint -> !constraint.columns().isEmpty());
    }

    @Override
    boolean supportsRelation(TableModel table) {
        return "TABLE".equalsIgnoreCase(table.type()) || "PARTITIONED TABLE".equalsIgnoreCase(table.type());
    }

    @Override
    boolean sufficientMetadata(TableModel table) {
        return table.metadata().columnsRead()
                && table.metadata().primaryKeyRead()
                && table.metadata().indexesRead()
                && !table.metadata().truncated();
    }

    @Override
    int checkEntity(
            DatabaseAdvisorContext context,
            MappedTableResolution primary,
            MappedEntityFacts entity,
            List<String> details) {
        int eligible = 0;
        for (MappedUniqueConstraintFacts uniqueConstraint : entity.uniqueConstraints()) {
            if (uniqueConstraint.columns().isEmpty()) {
                continue;
            }
            MappedTableResolution resolution = resolveItemTable(context, entity, primary, uniqueConstraint.tableName());
            if (!resolution.resolved()) {
                continue;
            }
            TableModel table = resolution.table();
            List<String> columns = uniqueConstraint.columns().stream()
                    .map(column -> resolution.schema().declaredColumn(table, column))
                    .map(column -> column == null ? null : column.name())
                    .toList();
            if (columns.stream().anyMatch(java.util.Objects::isNull)) {
                for (String column : uniqueConstraint.columns()) {
                    if (resolution.schema().declaredColumn(table, column) == null) {
                        unknownColumn(context, resolution.schema(), table, column, uniqueConstraint.description());
                    }
                }
                continue;
            }
            if (columns.isEmpty() || !columns.stream().allMatch(table::hasColumn)) {
                continue;
            }
            if ((isPrimaryKey(table, columns) && primaryKeyEnforced(resolution.schema(), table))
                    || table.indexes().stream()
                            .anyMatch(index -> enforces(index, columns, resolution.schema(), table))) {
                eligible++;
                continue;
            }
            if (resolution.schema().dialect() == Dialect.ORACLE
                    || (isPrimaryKey(table, columns)
                            && table.metadata().primaryKeyEnforced() == null
                            && !primaryKeyEnforced(resolution.schema(), table))
                    || table.indexes().stream().anyMatch(this::uncertainEnforcement)
                    || table.indexes().stream()
                            .anyMatch(index -> structurallyCovers(index, columns)
                                    && !enforces(index, columns, resolution.schema(), table))) {
                unknown(
                        context,
                        uniqueConstraint.description()
                                + ": index metadata does not establish complete uniqueness enforcement; "
                                + "constraint-backed, nullable-subset, partial, expression or unknown-state keys may require additional evidence.");
                continue;
            }
            eligible++;
            details.add(resolution.schema().dataSourceName() + ": " + uniqueConstraint.description()
                    + " declares a unique constraint on " + table.qualifiedName() + " " + columns
                    + ", for which no enforcing key was found in the observed metadata. "
                    + "Confirm declaration-name correspondence and intended null semantics.");
        }
        return eligible;
    }

    private boolean primaryKeyEnforced(SchemaSnapshot schema, TableModel table) {
        if (table.metadata().primaryKeyEnforced() != null) {
            return Boolean.TRUE.equals(table.metadata().primaryKeyEnforced());
        }
        if (schema.dialect() != Dialect.ORACLE) {
            return false;
        }
        return schema.vendorFindings().findings(VendorFindingKinds.ORACLE_CONSTRAINTS).stream()
                .anyMatch(constraint -> "P".equals(constraint.constraintType())
                        && java.util.Objects.equals(constraint.schema(), table.schema())
                        && java.util.Objects.equals(constraint.table(), table.name())
                        && table.primaryKeyName() != null
                        && table.primaryKeyName().equals(constraint.constraintName())
                        && constraint.enabled()
                        && constraint.validatedAgainstExistingRows());
    }

    private boolean enforces(IndexModel index, List<String> columns, SchemaSnapshot schema, TableModel table) {
        if (!structurallyCovers(index, columns)) {
            return false;
        }
        boolean sameColumnSet = columns.stream().allMatch(index.columnNames()::contains);
        if (sameColumnSet
                || schema.dialect() == Dialect.POSTGRESQL
                || schema.dialect().isMySqlFamily()
                || index.nullsNotDistinct()) {
            return true;
        }
        // Oracle's UNIQUE(a) permits repeated (NULL, 1), unlike UNIQUE(a, b).
        return index.columnNames().stream().allMatch(name -> {
            ColumnModel column = table.column(name);
            return column != null && column.notNull();
        });
    }

    private boolean structurallyCovers(IndexModel index, List<String> columns) {
        return index.unique()
                && Boolean.TRUE.equals(index.uniquenessKnown())
                && index.validity() == IndexModel.Validity.VALID
                && !index.partial()
                && !index.hasExpressionKeyPart()
                && !index.specialized()
                && !index.partitioned()
                && !index.keyParts().isEmpty()
                && index.keyParts().stream()
                        .allMatch(part -> part.columnName() != null
                                && columns.contains(part.columnName())
                                && part.collation() == null
                                && (part.prefixLength() == null || part.prefixLength() > 0));
    }

    private boolean uncertainEnforcement(IndexModel index) {
        return !Boolean.TRUE.equals(index.uniquenessKnown())
                || index.backingConstraint() != null
                || (index.unique()
                        && (index.validity() != IndexModel.Validity.VALID
                                || index.partial()
                                || index.hasExpressionKeyPart()
                                || index.specialized()
                                || index.partitioned()
                                || index.keyParts().isEmpty()
                                || index.keyParts().stream()
                                        .anyMatch(part -> part.collation() != null
                                                || (part.prefixLength() != null && part.prefixLength() <= 0))));
    }

    /** The primary key already enforces uniqueness over its own columns, whatever the index catalog shows. */
    private boolean isPrimaryKey(TableModel table, List<String> columns) {
        List<String> primaryKeyColumns = table.primaryKeyColumns();
        if (primaryKeyColumns.size() > columns.size() || primaryKeyColumns.isEmpty()) {
            return false;
        }
        return primaryKeyColumns.stream()
                .allMatch(primaryKeyColumn -> columns.stream()
                        .anyMatch(column ->
                                primaryKeyColumn != null && column != null && primaryKeyColumn.equals(column)));
    }
}
