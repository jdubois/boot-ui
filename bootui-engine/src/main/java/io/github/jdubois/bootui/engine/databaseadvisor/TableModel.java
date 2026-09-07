package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.List;
import java.util.Objects;

/**
 * One physical table read from {@code DatabaseMetaData}, carrying its qualified identity (catalog, schema,
 * name), columns, primary key (name and ordered columns), foreign keys, indexes, and how completely that
 * metadata could be read.
 *
 * <p>PostgreSQL declarative partitioning is modelled explicitly: a partitioned parent
 * ({@code partitionParent}) is the table users actually maintain, while each child partition
 * ({@code partitionChild}) inherits the parent's structure — so reporting the same missing index or missing
 * primary key once per partition would multiply one finding by the partition count.</p>
 */
record TableModel(
        String catalog,
        String schema,
        String name,
        String type,
        List<ColumnModel> columns,
        String primaryKeyName,
        List<String> primaryKeyColumns,
        List<ForeignKeyModel> foreignKeys,
        List<IndexModel> indexes,
        boolean partitionParent,
        boolean partitionChild,
        boolean extensionOwned,
        TableMetadata metadata) {

    TableModel {
        columns = List.copyOf(columns);
        primaryKeyColumns = List.copyOf(primaryKeyColumns);
        foreignKeys = List.copyOf(foreignKeys);
        indexes = List.copyOf(indexes);
    }

    /** Convenience factory for tests and callers with no vendor augmentation. */
    static TableModel of(
            String catalog,
            String schema,
            String name,
            List<ColumnModel> columns,
            List<String> primaryKeyColumns,
            List<ForeignKeyModel> foreignKeys,
            List<IndexModel> indexes) {
        return new TableModel(
                catalog,
                schema,
                name,
                "TABLE",
                columns,
                primaryKeyColumns.isEmpty() ? null : "pk_" + name,
                primaryKeyColumns,
                foreignKeys,
                indexes,
                false,
                false,
                false,
                TableMetadata.COMPLETE);
    }

    TableModel withIndexes(List<IndexModel> replacement) {
        return new TableModel(
                catalog,
                schema,
                name,
                type,
                columns,
                primaryKeyName,
                primaryKeyColumns,
                foreignKeys,
                replacement,
                partitionParent,
                partitionChild,
                extensionOwned,
                metadata);
    }

    TableModel withPlacement(boolean newPartitionParent, boolean newPartitionChild, boolean newExtensionOwned) {
        return new TableModel(
                catalog,
                schema,
                name,
                type,
                columns,
                primaryKeyName,
                primaryKeyColumns,
                foreignKeys,
                indexes,
                newPartitionParent,
                newPartitionChild,
                newExtensionOwned,
                metadata);
    }

    TableModel withMetadata(TableMetadata replacement) {
        return new TableModel(
                catalog,
                schema,
                name,
                type,
                columns,
                primaryKeyName,
                primaryKeyColumns,
                foreignKeys,
                indexes,
                partitionParent,
                partitionChild,
                extensionOwned,
                replacement);
    }

    TableModel withForeignKeys(List<ForeignKeyModel> replacement) {
        return new TableModel(
                catalog,
                schema,
                name,
                type,
                columns,
                primaryKeyName,
                primaryKeyColumns,
                replacement,
                indexes,
                partitionParent,
                partitionChild,
                extensionOwned,
                metadata);
    }

    /**
     * {@code schema.table} for a driver that reports a schema, falling back to {@code catalog.table} — MySQL
     * and MariaDB report the database in {@code TABLE_CAT} and leave {@code TABLE_SCHEM} null, so without the
     * fallback every MySQL finding would name a bare table with no indication of which database it is in.
     */
    String qualifiedName() {
        if (schema != null && !schema.isBlank()) {
            return schema + "." + name;
        }
        return catalog == null || catalog.isBlank() ? name : catalog + "." + name;
    }

    boolean matchesName(String candidate) {
        return candidate != null && name != null && name.equals(candidate);
    }

    /** True when this table matches an optionally qualified {@code catalog}/{@code schema}/{@code name}. */
    boolean matches(String candidateCatalog, String candidateSchema, String candidateName) {
        if (!matchesName(candidateName)) {
            return false;
        }
        return matchesQualifier(schema, candidateSchema) && matchesQualifier(catalog, candidateCatalog);
    }

    boolean hasColumn(String columnName) {
        return column(columnName) != null;
    }

    ColumnModel column(String columnName) {
        if (columnName == null) {
            return null;
        }
        return columns.stream()
                .filter(column -> column.name() != null && column.name().equals(columnName))
                .findFirst()
                .orElse(null);
    }

    /** True when at least one usable index supports equality lookups on {@code columns} in that order. */
    boolean hasUsableLeadingIndex(List<String> orderedColumns) {
        return indexes.stream().anyMatch(index -> index.supportsLeadingEquality(orderedColumns));
    }

    /**
     * True when at least one usable index's leading key parts are exactly {@code columns}, as a set, in any
     * order — Oracle's own documented guidance for what supports a composite foreign key, since a pure
     * multi-column equality lookup does not care which leading key part binds to which column.
     */
    boolean hasUsableLeadingIndexAnyOrder(List<String> columns) {
        return indexes.stream().anyMatch(index -> index.supportsLeadingEqualityAnyOrder(columns));
    }

    /** True when at least one usable unique index genuinely enforces uniqueness over {@code columns}. */
    boolean hasEnforcedUniqueness(List<String> uniqueColumns) {
        return uniquenessCoverage(uniqueColumns) == IndexModel.UniquenessCoverage.ENFORCED;
    }

    IndexModel.UniquenessCoverage uniquenessCoverage(List<String> uniqueColumns) {
        if (uniqueColumns.isEmpty() || uniqueColumns.stream().anyMatch(Objects::isNull)) {
            return IndexModel.UniquenessCoverage.UNKNOWN;
        }
        boolean unknown = !metadata.primaryKeyRead() || !metadata.indexesRead();
        if (!primaryKeyColumns.isEmpty() && uniqueColumns.containsAll(primaryKeyColumns)) {
            if (metadata.primaryKeyRead() && Boolean.TRUE.equals(metadata.primaryKeyEnforced())) {
                return IndexModel.UniquenessCoverage.ENFORCED;
            }
            unknown |= metadata.primaryKeyEnforced() == null;
        }
        for (IndexModel index : indexes) {
            boolean subsetColumnsNotNull = !index.keyParts().isEmpty()
                    && index.columnNames().stream().allMatch(name -> {
                        ColumnModel column = column(name);
                        return column != null && column.notNull();
                    });
            IndexModel.UniquenessCoverage coverage = index.uniquenessCoverage(uniqueColumns, subsetColumnsNotNull);
            if (coverage == IndexModel.UniquenessCoverage.ENFORCED) {
                return coverage;
            }
            unknown |= coverage == IndexModel.UniquenessCoverage.UNKNOWN;
        }
        return unknown ? IndexModel.UniquenessCoverage.UNKNOWN : IndexModel.UniquenessCoverage.NOT_ENFORCED;
    }

    /**
     * The unique index identity linked to the primary-key constraint by actual catalog evidence.
     * Neither a matching name nor matching columns alone prove backing ownership.
     */
    IndexModel primaryKeyBackingIndex() {
        if (primaryKeyColumns.isEmpty()) {
            return null;
        }
        List<IndexModel> candidates = indexes.stream()
                .filter(index -> primaryKeyName != null && primaryKeyName.equals(index.backingConstraint()))
                .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static boolean matchesQualifier(String actual, String candidate) {
        if (candidate == null) {
            return true;
        }
        return Objects.equals(actual, candidate);
    }
}
