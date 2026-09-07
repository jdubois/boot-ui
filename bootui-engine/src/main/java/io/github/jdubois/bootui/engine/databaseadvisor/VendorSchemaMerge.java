package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Folds the vendor catalog augmentation back onto the generic JDBC schema model: index semantics that
 * {@code getIndexInfo} cannot express (validity, partial predicates, expression and prefix key parts, access
 * method, visibility) and table placement (PostgreSQL partition parents/children and extension-owned tables).
 *
 * <p>Doing this once, here, is what lets every rule work against one enriched model instead of re-deriving
 * vendor semantics: {@code DB-SCHEMA-002} can ask "is there a <em>usable</em> index for these columns?"
 * without knowing that MySQL prefix indexes exist or that PostgreSQL indexes can be invalid.</p>
 */
final class VendorSchemaMerge {

    private VendorSchemaMerge() {}

    static List<TableModel> merge(List<TableModel> tables, Dialect dialect, VendorFindings findings) {
        if (tables.isEmpty()) {
            return tables;
        }
        if (dialect == Dialect.POSTGRESQL) {
            return mergePostgres(tables, findings);
        }
        if (dialect.isMySqlFamily()) {
            return mergeMySql(tables, findings);
        }
        if (dialect == Dialect.ORACLE) {
            return mergeOracle(tables, findings);
        }
        return tables;
    }

    private static List<TableModel> mergePostgres(List<TableModel> tables, VendorFindings findings) {
        Map<ObjectKey, PostgresIndexDetail> indexDetails = new HashMap<>();
        for (PostgresIndexDetail detail : findings.findings(VendorFindingKinds.POSTGRES_INDEX_DETAILS)) {
            indexDetails.put(key(detail.schema(), detail.table(), detail.index()), detail);
        }
        Map<ObjectKey, PostgresPartitionInfo> partitions = new HashMap<>();
        for (PostgresPartitionInfo partition : findings.findings(VendorFindingKinds.POSTGRES_PARTITIONS)) {
            partitions.put(key(partition.schema(), partition.table()), partition);
        }
        Set<ObjectKey> extensionTables = new HashSet<>();
        for (PostgresExtensionTable table : findings.findings(VendorFindingKinds.POSTGRES_EXTENSION_TABLES)) {
            extensionTables.add(key(table.schema(), table.table()));
        }

        List<TableModel> merged = new ArrayList<>();
        for (TableModel table : tables) {
            PostgresPartitionInfo partition = partitions.get(key(table.schema(), table.name()));
            boolean parent = table.partitionParent() || (partition != null && partition.partitionedParent());
            boolean placementComplete = findings.available(VendorFindingKinds.POSTGRES_PARTITIONS)
                    && findings.truncations().stream()
                            .noneMatch(
                                    augmentation -> augmentation.kind().equals(VendorFindingKinds.POSTGRES_PARTITIONS));
            List<IndexModel> indexes = new ArrayList<>();
            for (IndexModel index : table.indexes()) {
                PostgresIndexDetail detail = indexDetails.get(key(table.schema(), table.name(), index.name()));
                indexes.add(detail == null ? index : enrich(index, detail, placementComplete && !parent));
            }
            boolean child = partition != null && partition.partitionChild();
            TableModel enriched = table.withIndexes(indexes)
                    .withPlacement(parent, child, extensionTables.contains(key(table.schema(), table.name())));
            IndexModel backing = enriched.primaryKeyBackingIndex();
            if (backing != null && backing.unique() && backing.validity() == IndexModel.Validity.VALID) {
                enriched = enriched.withMetadata(enriched.metadata().withPrimaryKeyEnforced(true));
            }
            merged.add(enrichForeignKeys(enriched, Dialect.POSTGRESQL, findings));
        }
        return List.copyOf(merged);
    }

    private static IndexModel enrich(IndexModel index, PostgresIndexDetail detail, boolean ordinaryPlacementKnown) {
        if (detail.definitionComplete()) {
            boolean valid = detail.valid() && Boolean.TRUE.equals(detail.ready()) && Boolean.TRUE.equals(detail.live());
            IndexModel.Validity validity = detail.ready() == null || detail.live() == null
                    ? IndexModel.Validity.UNKNOWN
                    : valid ? IndexModel.Validity.VALID : IndexModel.Validity.INVALID;
            boolean linkageKnown = detail.primary() != null
                    && (detail.constraintName() == null) == (detail.constraintType() == null)
                    && Boolean.TRUE.equals(detail.primary()) == "p".equals(detail.constraintType());
            return new IndexModel(
                    index.name(),
                    detail.keyParts(),
                    Boolean.TRUE.equals(detail.unique()),
                    detail.method(),
                    detail.predicate(),
                    IndexModel.Visibility.VISIBLE,
                    validity,
                    detail.nullsNotDistinct(),
                    false,
                    false,
                    false,
                    detail.includedColumns(),
                    ordinaryPlacementKnown
                            && detail.unique() != null
                            && detail.ready() != null
                            && detail.live() != null
                            && linkageKnown,
                    detail.unique() != null,
                    linkageKnown ? detail.constraintName() : null,
                    detail.comparisonSemantics());
        }
        List<IndexKeyPart> keyParts = index.keyParts();
        List<String> included = index.includedColumns();
        Integer keyColumnCount = detail.keyColumnCount();
        if (keyColumnCount != null && keyColumnCount > 0 && keyColumnCount < keyParts.size()) {
            // pgjdbc's getIndexInfo() reports a covering index's INCLUDE (non-key) columns as if they were
            // ordinary trailing key parts (confirmed pgjdbc issue #3430) — pg_index.indnkeyatts is the only
            // reliable signal for how many leading key parts are genuine keys, so anything past it is trimmed
            // before it can inflate a composite key or defeat a uniqueness/leading-equality check.
            included = keyParts.subList(keyColumnCount, keyParts.size()).stream()
                    .map(IndexKeyPart::columnName)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            keyParts = keyParts.subList(0, keyColumnCount);
        }
        if (detail.expression() && keyParts.stream().noneMatch(IndexKeyPart::isExpression)) {
            // pgjdbc reports the expression's rendered text in COLUMN_NAME, which is indistinguishable from a
            // real column name; pg_index.indexprs is the only reliable signal that a key part is an
            // expression, and an expression index cannot answer a plain column lookup.
            keyParts =
                    keyParts.stream().map(part -> IndexKeyPart.expression(null)).toList();
        }
        return new IndexModel(
                index.name(),
                keyParts,
                index.unique(),
                detail.method() == null ? index.method() : detail.method(),
                detail.partial()
                        ? (detail.predicate() == null ? "partial index" : detail.predicate())
                        : index.filterCondition(),
                index.visibility(),
                detail.valid() ? IndexModel.Validity.VALID : IndexModel.Validity.INVALID,
                detail.nullsNotDistinct(),
                false,
                false,
                false,
                included,
                false,
                index.uniquenessKnown(),
                index.backingConstraint());
    }

    private static List<TableModel> mergeOracle(List<TableModel> tables, VendorFindings findings) {
        Map<ObjectKey, OracleIndexDetail> indexDetails = new HashMap<>();
        for (OracleIndexDetail detail : findings.findings(VendorFindingKinds.ORACLE_INDEX_DETAILS)) {
            indexDetails.put(exactKey(detail.schema(), detail.table(), detail.index()), detail);
        }
        Set<ObjectKey> unusablePartitionedIndexes = new HashSet<>();
        Set<ObjectKey> unresolvedPartitionOwners = new HashSet<>();
        for (OracleIndexPartitionStatus partition :
                findings.findings(VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS)) {
            if (!java.util.Objects.equals(partition.schema(), partition.tableOwner())) {
                unresolvedPartitionOwners.add(exactKey(partition.schema(), partition.table(), partition.index()));
            } else if (partition.unusable()) {
                unusablePartitionedIndexes.add(exactKey(partition.schema(), partition.table(), partition.index()));
            }
        }
        List<TableModel> merged = new ArrayList<>();
        for (TableModel table : tables) {
            List<IndexModel> indexes = new ArrayList<>();
            for (IndexModel index : table.indexes()) {
                ObjectKey indexKey = exactKey(table.schema(), table.name(), index.name());
                OracleIndexDetail detail = indexDetails.get(indexKey);
                if (detail != null && !java.util.Objects.equals(table.schema(), detail.tableOwner())) {
                    detail = null;
                }
                List<String> linkedConstraints = findings.findings(VendorFindingKinds.ORACLE_CONSTRAINTS).stream()
                        .filter(constraint -> java.util.Objects.equals(table.schema(), constraint.schema())
                                && java.util.Objects.equals(table.name(), constraint.table())
                                && java.util.Objects.equals(table.schema(), constraint.indexOwner())
                                && java.util.Objects.equals(index.name(), constraint.indexName())
                                && ("P".equals(constraint.constraintType()) || "U".equals(constraint.constraintType()))
                                && constraint.constraintName() != null)
                        .map(OracleConstraintDetail::constraintName)
                        .distinct()
                        .sorted()
                        .toList();
                String backingConstraint =
                        table.primaryKeyName() != null && linkedConstraints.contains(table.primaryKeyName())
                                ? table.primaryKeyName()
                                : linkedConstraints.isEmpty() ? null : linkedConstraints.get(0);
                indexes.add(
                        detail == null
                                ? index
                                : enrichOracle(
                                                index,
                                                detail,
                                                unusablePartitionedIndexes.contains(indexKey),
                                                findings.available(VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS)
                                                        && !unresolvedPartitionOwners.contains(indexKey)
                                                        && findings.truncations().stream()
                                                                .noneMatch(
                                                                        augmentation -> augmentation
                                                                                .kind()
                                                                                .equals(
                                                                                        VendorFindingKinds
                                                                                                .ORACLE_INDEX_PARTITION_STATUS)))
                                        .withBackingConstraint(backingConstraint));
            }
            TableModel enriched = table.withIndexes(indexes);
            List<OracleConstraintDetail> primaryKeys = findings.findings(VendorFindingKinds.ORACLE_CONSTRAINTS).stream()
                    .filter(constraint -> java.util.Objects.equals(table.schema(), constraint.schema())
                            && java.util.Objects.equals(table.name(), constraint.table())
                            && java.util.Objects.equals(table.primaryKeyName(), constraint.constraintName())
                            && "P".equals(constraint.constraintType()))
                    .toList();
            if (primaryKeys.size() == 1) {
                OracleConstraintDetail constraint = primaryKeys.get(0);
                Boolean enforced = "DISABLED".equals(constraint.status())
                        ? Boolean.FALSE
                        : constraint.enabled() && constraint.validatedAgainstExistingRows() ? Boolean.TRUE : null;
                enriched = enriched.withMetadata(enriched.metadata().withPrimaryKeyEnforced(enforced));
            }
            merged.add(enrichForeignKeys(enriched, Dialect.ORACLE, findings));
        }
        return List.copyOf(merged);
    }

    /**
     * A partitioned index's own {@code all_indexes.status} reads {@code N/A} — its real usability lives on
     * its partitions/subpartitions instead, so {@code hasUnusablePartition} (already resolved against
     * {@code all_ind_partitions}/{@code all_ind_subpartitions}) decides validity for those, and the plain
     * {@code status} column decides it for every other index.
     */
    private static IndexModel enrichOracle(
            IndexModel index, OracleIndexDetail detail, boolean hasUnusablePartition, boolean partitionsComplete) {
        IndexModel.Validity validity = detail.partitioned()
                ? (hasUnusablePartition
                        ? IndexModel.Validity.INVALID
                        : partitionsComplete ? IndexModel.Validity.VALID : IndexModel.Validity.UNKNOWN)
                : (detail.usable()
                        ? IndexModel.Validity.VALID
                        : detail.unusable() ? IndexModel.Validity.INVALID : IndexModel.Validity.UNKNOWN);
        IndexModel.Visibility visibility = detail.invisible()
                ? IndexModel.Visibility.INVISIBLE
                : "VISIBLE".equalsIgnoreCase(detail.visibility())
                        ? IndexModel.Visibility.VISIBLE
                        : IndexModel.Visibility.UNKNOWN;
        boolean uniquenessKnown = detail.uniquenessKnown()
                && (!Boolean.TRUE.equals(index.uniquenessKnown()) || index.unique() == detail.unique());
        return new IndexModel(
                index.name(),
                index.keyParts(),
                uniquenessKnown ? detail.unique() : index.unique(),
                detail.indexType() == null ? index.method() : detail.indexType().toLowerCase(Locale.ROOT),
                index.filterCondition(),
                visibility,
                validity,
                false,
                false,
                detail.partitioned(),
                !detail.normal(),
                index.includedColumns(),
                false,
                uniquenessKnown,
                index.backingConstraint());
    }

    private static TableModel enrichForeignKeys(TableModel table, Dialect dialect, VendorFindings findings) {
        List<ForeignKeyModel> foreignKeys = new ArrayList<>();
        for (ForeignKeyModel foreignKey : table.foreignKeys()) {
            ForeignKeyModel enriched = foreignKey;
            if (dialect == Dialect.POSTGRESQL) {
                List<PostgresUnvalidatedConstraint> matches =
                        findings.findings(VendorFindingKinds.POSTGRES_UNVALIDATED_CONSTRAINTS).stream()
                                .filter(constraint -> java.util.Objects.equals(table.schema(), constraint.schema())
                                        && java.util.Objects.equals(table.name(), constraint.table())
                                        && java.util.Objects.equals(foreignKey.name(), constraint.constraint())
                                        && "f".equals(constraint.type()))
                                .toList();
                if (matches.size() == 1) {
                    enriched = foreignKey.withEnforcement(matches.get(0).enforced(), false, foreignKey.matchType());
                }
            } else if (dialect == Dialect.ORACLE) {
                List<OracleConstraintDetail> matches = findings.findings(VendorFindingKinds.ORACLE_CONSTRAINTS).stream()
                        .filter(constraint -> java.util.Objects.equals(table.schema(), constraint.schema())
                                && java.util.Objects.equals(table.name(), constraint.table())
                                && java.util.Objects.equals(foreignKey.name(), constraint.constraintName())
                                && constraint.isForeignKey())
                        .toList();
                if (matches.size() == 1) {
                    OracleConstraintDetail constraint = matches.get(0);
                    Boolean enforced = "ENABLED".equals(constraint.status())
                            ? Boolean.TRUE
                            : "DISABLED".equals(constraint.status()) ? Boolean.FALSE : null;
                    Boolean validated = "VALIDATED".equals(constraint.validated())
                            ? Boolean.TRUE
                            : "NOT VALIDATED".equals(constraint.validated()) ? Boolean.FALSE : null;
                    enriched = foreignKey.withEnforcement(enforced, validated, foreignKey.matchType());
                }
            }
            foreignKeys.add(enriched);
        }
        return table.withForeignKeys(foreignKeys);
    }

    private static List<TableModel> mergeMySql(List<TableModel> tables, VendorFindings findings) {
        if (!findings.available(VendorFindingKinds.MYSQL_INDEX_DETAILS)) {
            return tables;
        }
        Map<ObjectKey, List<MySqlIndexDetail>> detailsByIndex = new HashMap<>();
        for (MySqlIndexDetail detail : findings.findings(VendorFindingKinds.MYSQL_INDEX_DETAILS)) {
            detailsByIndex
                    .computeIfAbsent(key(detail.schema(), detail.table(), detail.index()), ignored -> new ArrayList<>())
                    .add(detail);
        }
        List<TableModel> merged = new ArrayList<>();
        for (TableModel table : tables) {
            List<IndexModel> indexes = new ArrayList<>();
            for (IndexModel index : table.indexes()) {
                List<MySqlIndexDetail> details = detailsByIndex.get(indexKey(table, index));
                indexes.add(
                        details == null
                                        || details.isEmpty()
                                        || details.size() != index.keyParts().size()
                                ? index
                                : enrich(index, details));
            }
            merged.add(table.withIndexes(indexes));
        }
        return List.copyOf(merged);
    }

    /** MySQL reports the schema in {@code TABLE_CAT}; the JDBC {@code TABLE_SCHEM} is null there. */
    private static ObjectKey indexKey(TableModel table, IndexModel index) {
        String schema = table.schema() == null || table.schema().isBlank() ? table.catalog() : table.schema();
        return key(schema, table.name(), index.name());
    }

    private static IndexModel enrich(IndexModel index, List<MySqlIndexDetail> details) {
        List<MySqlIndexDetail> ordered = new ArrayList<>(details);
        ordered.sort((left, right) -> Integer.compare(left.position(), right.position()));
        List<IndexKeyPart> keyParts = new ArrayList<>();
        Boolean visible = null;
        String indexType = null;
        int expected = 1;
        for (MySqlIndexDetail detail : ordered) {
            if (detail.position() != expected++) {
                return index;
            }
            if (visible == null) {
                visible = detail.visible();
            }
            if (indexType == null) {
                indexType = detail.indexType();
            }
            if (detail.column() == null) {
                keyParts.add(IndexKeyPart.expression(detail.expression()));
                continue;
            }
            Boolean ascending = "A".equalsIgnoreCase(detail.collation())
                    ? Boolean.TRUE
                    : "D".equalsIgnoreCase(detail.collation()) ? Boolean.FALSE : null;
            keyParts.add(new IndexKeyPart(detail.column(), null, ascending, detail.subPart(), null));
        }
        if (keyParts.isEmpty()) {
            keyParts = index.keyParts();
        }
        MySqlIndexDetail first = ordered.get(0);
        boolean uniquenessKnown = ordered.stream().allMatch(MySqlIndexDetail::definitionComplete)
                && ordered.stream().allMatch(detail -> detail.unique() == first.unique())
                && (!Boolean.TRUE.equals(index.uniquenessKnown()) || index.unique() == first.unique());
        if (ordered.stream().anyMatch(detail -> !java.util.Objects.equals(detail.visible(), first.visible()))) {
            visible = null;
        }
        boolean consistentMethod =
                ordered.stream().allMatch(detail -> java.util.Objects.equals(detail.indexType(), first.indexType()));
        IndexModel.Visibility visibility = visible == null
                ? IndexModel.Visibility.UNKNOWN
                : (visible ? IndexModel.Visibility.VISIBLE : IndexModel.Visibility.INVISIBLE);
        return new IndexModel(
                index.name(),
                keyParts,
                uniquenessKnown ? first.unique() : index.unique(),
                !consistentMethod ? null : indexType == null ? index.method() : indexType.toLowerCase(Locale.ROOT),
                index.filterCondition(),
                visibility,
                index.validity(),
                index.nullsNotDistinct(),
                index.automatic(),
                index.partitioned(),
                index.specialized() || ordered.stream().anyMatch(MySqlIndexDetail::generatedColumn),
                index.includedColumns(),
                false,
                uniquenessKnown,
                index.backingConstraint());
    }

    private record ObjectKey(String schema, String table, String index) {}

    private static ObjectKey key(String schema, String table) {
        return new ObjectKey(schema, table, null);
    }

    private static ObjectKey key(String schema, String table, String index) {
        return new ObjectKey(schema, table, index);
    }

    /** Resolved catalog identifiers preserve case and component boundaries on every dialect. */
    private static ObjectKey exactKey(String schema, String table, String index) {
        return key(schema, table, index);
    }
}
