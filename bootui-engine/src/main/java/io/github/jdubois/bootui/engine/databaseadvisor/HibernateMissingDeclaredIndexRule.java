package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedIndexColumn;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedIndexFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Cross-references explicit JPA {@code @Table(indexes = @Index(...))} and
 * {@code @SecondaryTable(indexes = @Index(...))} declarations against observed physical indexes.
 * Hibernate's {@code SchemaValidator} ({@code ddl-auto=validate}) ignores {@code @Index} annotations, so a
 * missing migration leaves the table unindexed without failing startup validation.
 */
final class HibernateMissingDeclaredIndexRule extends AbstractHibernateCrossReferenceRule {

    HibernateMissingDeclaredIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-011",
                "Declared JPA @Index has no matching observed index",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Cross-references explicit @Table(indexes = @Index(...)) and @SecondaryTable(indexes = ...) "
                        + "declarations against observed base-table indexes leading on the declared column list and "
                        + "direction. Hibernate schema validation (ddl-auto=validate) does not check @Index annotations.",
                "Confirm the effective physical column names and add the missing index in a schema migration, or "
                        + "remove the stale @Index declaration from the entity mapping.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    boolean hasApplicableDeclarations(MappedEntityFacts entity) {
        return entity.indexes().stream().anyMatch(index -> !index.columns().isEmpty());
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
            DatabaseFindings details) {
        int eligible = 0;
        for (MappedIndexFacts declaredIndex : entity.indexes()) {
            if (declaredIndex.columns().isEmpty() || ownedByUniqueConstraintRule(entity, declaredIndex)) {
                continue;
            }
            MappedTableResolution resolution = resolveItemTable(context, entity, primary, declaredIndex.tableName());
            if (!resolution.resolved()) {
                continue;
            }
            SchemaSnapshot schema = resolution.schema();
            TableModel table = resolution.table();
            List<String> resolvedColumns =
                    new ArrayList<>(declaredIndex.columns().size());
            boolean missingColumn = false;
            for (MappedIndexColumn col : declaredIndex.columns()) {
                ColumnModel physical = schema.declaredColumn(table, col.columnName());
                if (physical == null) {
                    unknownColumn(context, schema, table, col.columnName(), declaredIndex.description());
                    missingColumn = true;
                } else {
                    resolvedColumns.add(physical.name());
                }
            }
            if (missingColumn || resolvedColumns.isEmpty()) {
                continue;
            }
            if (declaredIndex.unique()) {
                IndexModel.UniquenessCoverage coverage = table.uniquenessCoverage(resolvedColumns);
                if (coverage == IndexModel.UniquenessCoverage.ENFORCED) {
                    eligible++;
                    continue;
                }
                if (coverage == IndexModel.UniquenessCoverage.UNKNOWN) {
                    unknown(
                            context,
                            declaredIndex.description()
                                    + ": index metadata does not establish complete uniqueness enforcement for @Index(unique = true).");
                    continue;
                }
            } else {
                if (primaryKeyCovers(table, resolvedColumns, declaredIndex.columns())
                        || table.indexes().stream()
                                .anyMatch(index -> matches(index, resolvedColumns, declaredIndex.columns()))) {
                    eligible++;
                    continue;
                }
                if (table.indexes().stream()
                        .anyMatch(index -> alreadyFlaggedBrokenOrInvisibleIndex(index, resolvedColumns))) {
                    eligible++;
                    continue;
                }
                if (table.indexes().stream()
                        .anyMatch(index -> uncertainCoverage(index, resolvedColumns, declaredIndex.columns()))) {
                    unknown(
                            context,
                            declaredIndex.description()
                                    + ": index metadata does not establish whether an existing index covers the declared column list.");
                    continue;
                }
            }
            eligible++;
            details.add(
                    schema.dataSourceName() + ": " + declaredIndex.description() + " declares "
                            + (declaredIndex.unique() ? "unique index on " : "index on ")
                            + table.qualifiedName() + " " + resolvedColumns
                            + ", with no matching physical index observed (ddl-auto=validate ignores @Index).",
                    indexIdentity(schema, table, declaredIndex, resolvedColumns));
        }
        return eligible;
    }

    private static String indexIdentity(
            SchemaSnapshot schema, TableModel table, MappedIndexFacts declaredIndex, List<String> resolvedColumns) {
        List<String> extra = new ArrayList<>();
        if (declaredIndex.indexName() != null && !declaredIndex.indexName().isBlank()) {
            extra.add("index=" + declaredIndex.indexName());
        }
        if (declaredIndex.unique()) {
            extra.add("unique=true");
        }
        for (int i = 0; i < resolvedColumns.size(); i++) {
            Boolean ascending = declaredIndex.columns().get(i).ascending();
            String dir = ascending == null ? "" : (ascending ? ":asc" : ":desc");
            extra.add("indexcolumn=" + resolvedColumns.get(i) + dir);
        }
        return DatabaseIdentities.table(schema, table, extra.toArray(String[]::new));
    }

    private static boolean ownedByUniqueConstraintRule(MappedEntityFacts entity, MappedIndexFacts declaredIndex) {
        if (!declaredIndex.unique()) {
            return false;
        }
        List<String> indexColumns = declaredIndex.columnNames();
        return entity.uniqueConstraints().stream()
                .anyMatch(uc -> Objects.equals(uc.tableName(), declaredIndex.tableName())
                        && uc.columns().equals(indexColumns));
    }

    private static boolean primaryKeyCovers(
            TableModel table, List<String> resolvedColumns, List<MappedIndexColumn> declaredColumns) {
        if (Boolean.FALSE.equals(table.metadata().primaryKeyEnforced())
                || table.primaryKeyColumns().size() < resolvedColumns.size()) {
            return false;
        }
        for (int i = 0; i < resolvedColumns.size(); i++) {
            if (!Objects.equals(table.primaryKeyColumns().get(i), resolvedColumns.get(i))
                    || Boolean.FALSE.equals(declaredColumns.get(i).ascending())) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(
            IndexModel index, List<String> resolvedColumns, List<MappedIndexColumn> declaredColumns) {
        if (!index.supportsLeadingEquality(resolvedColumns) || index.specialized() || index.methodKnownUnsupported()) {
            return false;
        }
        for (int i = 0; i < declaredColumns.size(); i++) {
            Boolean declaredAscending = declaredColumns.get(i).ascending();
            if (declaredAscending != null) {
                Boolean actualAscending = index.keyParts().get(i).ascending();
                if (!declaredAscending.equals(actualAscending)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean alreadyFlaggedBrokenOrInvisibleIndex(IndexModel index, List<String> resolvedColumns) {
        if ((!index.invalid() && !index.invisible()) || index.keyParts().size() < resolvedColumns.size()) {
            return false;
        }
        for (int i = 0; i < resolvedColumns.size(); i++) {
            IndexKeyPart part = index.keyParts().get(i);
            if (part.isExpression() || !part.matchesColumn(resolvedColumns.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean uncertainCoverage(
            IndexModel index, List<String> resolvedColumns, List<MappedIndexColumn> declaredColumns) {
        if (index.keyParts().isEmpty()) {
            return true;
        }
        int limit = Math.min(index.keyParts().size(), resolvedColumns.size());
        for (int i = 0; i < limit; i++) {
            IndexKeyPart part = index.keyParts().get(i);
            if (!part.isExpression() && !part.matchesColumn(resolvedColumns.get(i))) {
                return false;
            }
        }
        if (index.keyParts().size() < resolvedColumns.size()) {
            return index.keyParts().stream().anyMatch(IndexKeyPart::isExpression);
        }
        if (index.hasExpressionKeyPart()
                || index.hasPrefixKeyPart()
                || index.specialized()
                || index.methodKnownUnsupported()) {
            return true;
        }
        if (index.supportsLeadingEquality(resolvedColumns)) {
            for (int i = 0; i < declaredColumns.size(); i++) {
                if (declaredColumns.get(i).ascending() != null
                        && index.keyParts().get(i).ascending() == null) {
                    return true;
                }
            }
        }
        return false;
    }
}
