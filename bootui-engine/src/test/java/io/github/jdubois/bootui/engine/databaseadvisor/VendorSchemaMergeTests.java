package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link VendorSchemaMerge} folds vendor catalog augmentation onto the generic JDBC model; these tests target
 * the merge logic directly rather than through a rule, since a wrong merge would silently corrupt every rule
 * built on top of it.
 */
class VendorSchemaMergeTests {

    @Test
    void mergePostgresTrimsPgjdbcsMisreportedIncludeColumnsFromTheIndexKey() {
        // pgjdbc's getIndexInfo() reports a covering index's INCLUDE (non-key) column as an ordinary trailing
        // key part (confirmed pgjdbc issue #3430): a unique index declared "ON t (a) INCLUDE (b)" is read back
        // with two key parts, when only "a" is a genuine key column.
        IndexModel misreported = IndexModel.of("uq_a_include_b", List.of("a", "b"), true);
        TableModel table = TableModel.of(
                "app",
                "public",
                "t",
                List.of(
                        DatabaseAdvisorFixtures.column("a", "int8", java.sql.Types.BIGINT),
                        DatabaseAdvisorFixtures.column("b", "int8", java.sql.Types.BIGINT)),
                List.of(),
                List.of(),
                List.of(misreported));
        PostgresIndexDetail detail =
                new PostgresIndexDetail("public", "t", "uq_a_include_b", true, false, null, false, "btree", 1, false);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.POSTGRES_INDEX_DETAILS, List.of(detail), false))
                .build();

        List<TableModel> merged = VendorSchemaMerge.merge(List.of(table), Dialect.POSTGRESQL, findings);

        IndexModel mergedIndex = merged.get(0).indexes().get(0);
        assertThat(mergedIndex.keyParts()).hasSize(1);
        assertThat(mergedIndex.columnNames()).containsExactly("a");
        assertThat(mergedIndex.includedColumns()).containsExactly("b");
        // The truncated index now genuinely enforces uniqueness over (a) alone, matching what INCLUDE means.
        assertThat(mergedIndex.enforcesUniquenessOver(List.of("a"))).isTrue();
    }

    @Test
    void mergePostgresLeavesAnOrdinaryIndexesKeyPartsUntouchedWhenKeyColumnCountMatches() {
        IndexModel plain = IndexModel.of("ix_plain", List.of("a", "b"), false);
        TableModel table = TableModel.of(
                "app",
                "public",
                "t",
                List.of(
                        DatabaseAdvisorFixtures.column("a", "int8", java.sql.Types.BIGINT),
                        DatabaseAdvisorFixtures.column("b", "int8", java.sql.Types.BIGINT)),
                List.of(),
                List.of(),
                List.of(plain));
        PostgresIndexDetail detail =
                new PostgresIndexDetail("public", "t", "ix_plain", true, false, null, false, "btree", 2, false);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.POSTGRES_INDEX_DETAILS, List.of(detail), false))
                .build();

        List<TableModel> merged = VendorSchemaMerge.merge(List.of(table), Dialect.POSTGRESQL, findings);

        assertThat(merged.get(0).indexes().get(0).keyParts()).hasSize(2);
    }

    @Test
    void mergeOracleDoesNotConfuseAGeneratedNameWithConstraintOwnership() {
        IndexModel index = IndexModel.of("SYS_C007", List.of("ID"), true);
        TableModel table = TableModel.of(
                "APP",
                "APP",
                "ORDERS",
                List.of(DatabaseAdvisorFixtures.column("ID", "number", java.sql.Types.NUMERIC)),
                List.of(),
                List.of(),
                List.of(index));
        OracleIndexDetail detail =
                new OracleIndexDetail("APP", "ORDERS", "SYS_C007", "NORMAL", true, "UNUSABLE", "VISIBLE", true, false);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(detail), false))
                .build();

        List<TableModel> merged = VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, findings);

        IndexModel mergedIndex = merged.get(0).indexes().get(0);
        assertThat(mergedIndex.automatic()).isFalse();
        assertThat(mergedIndex.invalid()).isTrue();
    }

    @Test
    void mergeOracleUsesPartitionStatusInsteadOfTheIndexsOwnNaStatus() {
        IndexModel index = IndexModel.of("IX_PARTITIONED", List.of("ID"), false);
        TableModel table = TableModel.of(
                "APP",
                "APP",
                "EVENTS",
                List.of(DatabaseAdvisorFixtures.column("ID", "number", java.sql.Types.NUMERIC)),
                List.of(),
                List.of(),
                List.of(index));
        OracleIndexDetail detail = new OracleIndexDetail(
                "APP", "EVENTS", "IX_PARTITIONED", "NORMAL", false, "N/A", "VISIBLE", false, true);
        OracleIndexPartitionStatus unusablePartition =
                new OracleIndexPartitionStatus("APP", "EVENTS", "IX_PARTITIONED", "P_2024_01", false, "UNUSABLE");
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(detail), false))
                .add(VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS, List.of(unusablePartition), false))
                .build();

        List<TableModel> merged = VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, findings);

        assertThat(merged.get(0).indexes().get(0).invalid()).isTrue();
    }

    @Test
    void mergeOracleTreatsAPartitionedIndexWithNoUnusablePartitionAsValid() {
        IndexModel index = IndexModel.of("IX_PARTITIONED", List.of("ID"), false);
        TableModel table = TableModel.of(
                "APP",
                "APP",
                "EVENTS",
                List.of(DatabaseAdvisorFixtures.column("ID", "number", java.sql.Types.NUMERIC)),
                List.of(),
                List.of(),
                List.of(index));
        OracleIndexDetail detail = new OracleIndexDetail(
                "APP", "EVENTS", "IX_PARTITIONED", "NORMAL", false, "N/A", "VISIBLE", false, true);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(detail), false))
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS, List.of(), false))
                .build();

        List<TableModel> merged = VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, findings);

        assertThat(merged.get(0).indexes().get(0).invalid()).isFalse();
    }

    @Test
    void mergeOracleMarksANonNormalIndexTypeAsSpecialized() {
        IndexModel index = IndexModel.of("IX_BITMAP", List.of("STATUS"), false);
        TableModel table = TableModel.of(
                "APP",
                "APP",
                "ORDERS",
                List.of(DatabaseAdvisorFixtures.column("STATUS", "number", java.sql.Types.NUMERIC)),
                List.of(),
                List.of(),
                List.of(index));
        OracleIndexDetail detail =
                new OracleIndexDetail("APP", "ORDERS", "IX_BITMAP", "BITMAP", false, "VALID", "VISIBLE", false, false);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(detail), false))
                .build();

        List<TableModel> merged = VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, findings);

        assertThat(merged.get(0).indexes().get(0).specialized()).isTrue();
    }

    @Test
    void mergeOracleUsesExactCaseWhenMatchingIndexNamesAcrossReads() {
        // Oracle is case-sensitive for genuinely distinct quoted identifiers, unlike PostgreSQL/MySQL, so an
        // index named differently only by case must not be merged as if it were the same object.
        IndexModel index = IndexModel.of("MixedCaseIndex", List.of("ID"), false);
        TableModel table = TableModel.of(
                "APP",
                "APP",
                "ORDERS",
                List.of(DatabaseAdvisorFixtures.column("ID", "number", java.sql.Types.NUMERIC)),
                List.of(),
                List.of(),
                List.of(index));
        OracleIndexDetail detail = new OracleIndexDetail(
                "APP", "ORDERS", "mixedcaseindex", "NORMAL", false, "UNUSABLE", "VISIBLE", false, false);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(detail), false))
                .build();

        List<TableModel> merged = VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, findings);

        // No match by exact case, so the index is left as read generically (unenriched, unknown validity).
        assertThat(merged.get(0).indexes().get(0).invalid()).isFalse();
    }

    @Test
    void oracleIndexOwnerCannotStandInForADifferentTableOwner() {
        IndexModel index = IndexModel.of("IX", List.of("ID"), false);
        TableModel table = TableModel.of("APP", "APP", "T", List.of(), List.of(), List.of(), List.of(index));
        OracleIndexDetail detail =
                new OracleIndexDetail("APP", "T", "IX", "NORMAL", false, "UNUSABLE", "VISIBLE", false, false, "OTHER");
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(detail), false))
                .build();
        assertThat(VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, findings)
                        .get(0)
                        .indexes()
                        .get(0))
                .isSameAs(index);
    }

    @Test
    void oraclePartitionOwnershipMismatchIsUnknownRatherThanUsableOrUnusable() {
        TableModel table = TableModel.of(
                "APP", "APP", "T", List.of(), List.of(), List.of(), List.of(IndexModel.of("IX", List.of("ID"), false)));
        OracleIndexDetail detail =
                new OracleIndexDetail("APP", "T", "IX", "NORMAL", false, "N/A", "VISIBLE", false, true);
        OracleIndexPartitionStatus partition =
                new OracleIndexPartitionStatus("APP", "T", "IX", "P", false, "UNUSABLE", "OTHER");
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(detail), false))
                .add(VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS, List.of(partition), false))
                .build();
        assertThat(VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, findings)
                        .get(0)
                        .indexes()
                        .get(0)
                        .validity())
                .isEqualTo(IndexModel.Validity.UNKNOWN);
    }

    @Test
    void postgresExactIdentityPreservesCaseAndComponentBoundaries() {
        TableModel table = TableModel.of(
                "app",
                "a.b",
                "c",
                List.of(),
                List.of(),
                List.of(),
                List.of(IndexModel.of("Mixed", List.of("id"), false)));
        for (PostgresIndexDetail wrong : List.of(
                new PostgresIndexDetail("a", "b.c", "Mixed", false, false, null, false, "btree", 1, false),
                new PostgresIndexDetail("a.b", "c", "mixed", false, false, null, false, "btree", 1, false))) {
            VendorFindings findings = VendorFindings.builder()
                    .add(VendorAugmentation.available(VendorFindingKinds.POSTGRES_INDEX_DETAILS, List.of(wrong), false))
                    .build();
            assertThat(VendorSchemaMerge.merge(List.of(table), Dialect.POSTGRESQL, findings)
                            .get(0)
                            .indexes()
                            .get(0))
                    .isSameAs(table.indexes().get(0));
        }
    }

    @Test
    void postgresPreservesPositionSpecificExpressionsAndIncludedPayload() {
        TableModel table = TableModel.of(
                "app",
                "public",
                "t",
                List.of(),
                List.of(),
                List.of(),
                List.of(IndexModel.of("ix", List.of("id", "lower(note)", "payload"), false)));
        PostgresIndexDetail detail = new PostgresIndexDetail(
                "public",
                "t",
                "ix",
                true,
                false,
                null,
                true,
                "btree",
                2,
                false,
                true,
                true,
                false,
                false,
                null,
                null,
                List.of(IndexKeyPart.column("id", true), IndexKeyPart.expression("lower(note)")),
                List.of("payload"),
                List.of("int8_ops", "text_ops"),
                true);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.POSTGRES_INDEX_DETAILS, List.of(detail), false))
                .build();
        IndexModel merged = VendorSchemaMerge.merge(List.of(table), Dialect.POSTGRESQL, findings)
                .get(0)
                .indexes()
                .get(0);
        assertThat(merged.supportsLeadingEquality(List.of("id"))).isTrue();
        assertThat(merged.keyParts().get(1).expression()).isEqualTo("lower(note)");
        assertThat(merged.includedColumns()).containsExactly("payload");
        assertThat(merged.comparable()).isFalse();
    }

    @Test
    void postgresActualConstraintLinkageAndOperatorSemanticsSurviveMerge() {
        TableModel table = TableModel.of(
                "app",
                "public",
                "t",
                List.of(),
                List.of("id"),
                List.of(),
                List.of(IndexModel.of("index_name", List.of("id"), true)));
        PostgresIndexDetail detail = new PostgresIndexDetail(
                "public",
                "t",
                "index_name",
                true,
                false,
                null,
                false,
                "btree",
                1,
                false,
                true,
                true,
                true,
                true,
                "pk_t",
                "p",
                List.of(IndexKeyPart.column("id", true)),
                List.of(),
                List.of("opclass=123;collation=0"),
                true);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.POSTGRES_INDEX_DETAILS, List.of(detail), false))
                .add(VendorAugmentation.available(VendorFindingKinds.POSTGRES_PARTITIONS, List.of(), false))
                .build();
        TableModel merged = VendorSchemaMerge.merge(List.of(table), Dialect.POSTGRESQL, findings)
                .get(0);
        assertThat(merged.primaryKeyBackingIndex().name()).isEqualTo("index_name");
        assertThat(merged.primaryKeyBackingIndex().comparisonSemantics()).containsExactly("opclass=123;collation=0");
        assertThat(merged.primaryKeyBackingIndex().comparable()).isTrue();
    }

    @Test
    void postgresUnknownPrimaryOwnershipCannotBecomeAnEquivalentOrdinaryIndex() {
        TableModel table = TableModel.of(
                "app",
                "public",
                "t",
                List.of(),
                List.of("id"),
                List.of(),
                List.of(IndexModel.of("ix", List.of("id"), true)));
        PostgresIndexDetail detail = new PostgresIndexDetail(
                "public",
                "t",
                "ix",
                true,
                false,
                null,
                false,
                "btree",
                1,
                false,
                true,
                true,
                true,
                null,
                "pk_t",
                "p",
                List.of(IndexKeyPart.column("id", true)),
                List.of(),
                List.of("0:0:123"),
                true);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.POSTGRES_INDEX_DETAILS, List.of(detail), false))
                .add(VendorAugmentation.available(VendorFindingKinds.POSTGRES_PARTITIONS, List.of(), false))
                .build();
        TableModel merged = VendorSchemaMerge.merge(List.of(table), Dialect.POSTGRESQL, findings)
                .get(0);
        assertThat(merged.primaryKeyBackingIndex()).isNull();
        assertThat(merged.indexes().get(0).comparable()).isFalse();
    }

    @Test
    void missingOrTruncatedOraclePartitionEvidenceLeavesValidityUnknown() {
        TableModel table = TableModel.of(
                "APP", "APP", "T", List.of(), List.of(), List.of(), List.of(IndexModel.of("IX", List.of("ID"), false)));
        OracleIndexDetail detail =
                new OracleIndexDetail("APP", "T", "IX", "NORMAL", false, "N/A", "VISIBLE", false, true);
        for (VendorAugmentation<OracleIndexPartitionStatus> partitions : List.of(
                VendorAugmentation.<OracleIndexPartitionStatus>failed(
                        VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS, "permission denied"),
                VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS, List.of(), true))) {
            VendorFindings findings = VendorFindings.builder()
                    .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(detail), false))
                    .add(partitions)
                    .build();
            assertThat(VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, findings)
                            .get(0)
                            .indexes()
                            .get(0)
                            .validity())
                    .isEqualTo(IndexModel.Validity.UNKNOWN);
        }
    }

    @Test
    void partialMysqlCatalogGroupCannotReplaceACompleteJdbcDefinition() {
        IndexModel index = IndexModel.of("ix", List.of("a", "b"), false);
        TableModel table = TableModel.of("app", null, "t", List.of(), List.of(), List.of(), List.of(index));
        MySqlIndexDetail detail = new MySqlIndexDetail("app", "t", "ix", 1, "a", null, "A", "BTREE", false, true, null);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.MYSQL_INDEX_DETAILS, List.of(detail), true))
                .build();
        assertThat(VendorSchemaMerge.merge(List.of(table), Dialect.MYSQL, findings)
                        .get(0)
                        .indexes()
                        .get(0))
                .isSameAs(index);
    }

    @Test
    void oraclePrimaryKeyAndForeignKeyEnforcementAreSeparateFromIndexVisibility() {
        ForeignKeyModel foreignKey = new ForeignKeyModel(
                "FK",
                List.of("PARENT_ID"),
                "APP",
                "APP",
                "PARENT",
                List.of("ID"),
                0,
                0,
                java.sql.DatabaseMetaData.importedKeyNotDeferrable);
        TableModel table = TableModel.of(
                "APP",
                "APP",
                "T",
                List.of(),
                List.of("ID"),
                List.of(foreignKey),
                List.of(IndexModel.of("IX", List.of("ID"), false)));
        OracleIndexDetail index =
                new OracleIndexDetail("APP", "T", "IX", "NORMAL", false, "VALID", "INVISIBLE", false, false);
        OracleConstraintDetail primary = new OracleConstraintDetail(
                "APP",
                "T",
                "pk_T",
                "P",
                "ENABLED",
                "VALIDATED",
                false,
                null,
                "APP",
                "IX",
                "DEFERRABLE",
                "IMMEDIATE",
                null);
        OracleConstraintDetail foreign =
                new OracleConstraintDetail("APP", "T", "FK", "R", "DISABLED", "NOT VALIDATED", false, null);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(index), false))
                .add(VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_CONSTRAINTS, List.of(primary, foreign), false))
                .build();
        TableModel merged = VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, findings)
                .get(0);
        assertThat(merged.metadata().primaryKeyEnforced()).isTrue();
        assertThat(merged.uniquenessCoverage(List.of("ID", "PARENT_ID")))
                .isEqualTo(IndexModel.UniquenessCoverage.ENFORCED);
        assertThat(merged.foreignKeys().get(0).enforced()).isFalse();
        assertThat(merged.foreignKeys().get(0).validated()).isFalse();
    }

    @Test
    void mysqlUnknownOrContradictoryUniquenessCannotProveAbsenceOfEnforcement() {
        IndexModel index = new IndexModel(
                "ix",
                List.of(IndexKeyPart.column("a", true)),
                false,
                "btree",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID);
        TableModel table = TableModel.of("app", null, "t", List.of(), List.of(), List.of(), List.of(index));
        for (MySqlIndexDetail detail : List.of(
                new MySqlIndexDetail("app", "t", "ix", 1, "a", null, "A", "BTREE", false, true, null, false, false),
                new MySqlIndexDetail("app", "t", "ix", 1, "a", null, "A", "BTREE", true, true, null, false, true))) {
            VendorFindings findings = VendorFindings.builder()
                    .add(VendorAugmentation.available(VendorFindingKinds.MYSQL_INDEX_DETAILS, List.of(detail), false))
                    .build();
            IndexModel merged = VendorSchemaMerge.merge(List.of(table), Dialect.MYSQL, findings)
                    .get(0)
                    .indexes()
                    .get(0);
            assertThat(merged.uniquenessKnown()).isFalse();
            assertThat(merged.uniquenessCoverage(List.of("a"))).isEqualTo(IndexModel.UniquenessCoverage.UNKNOWN);
        }
    }

    @Test
    void oracleUnknownUniquenessAndNonuniqueConstraintBackingRemainUnknown() {
        IndexModel index = new IndexModel(
                "IX",
                List.of(IndexKeyPart.column("A", true)),
                false,
                "NORMAL",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID);
        TableModel table = TableModel.of("APP", "APP", "T", List.of(), List.of(), List.of(), List.of(index));
        OracleIndexDetail unknown = new OracleIndexDetail(
                "APP", "T", "IX", "NORMAL", false, "VALID", "VISIBLE", false, false, "APP", false);
        VendorFindings unknownFindings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(unknown), false))
                .build();
        IndexModel merged = VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, unknownFindings)
                .get(0)
                .indexes()
                .get(0);
        assertThat(merged.uniquenessKnown()).isFalse();
        assertThat(merged.uniquenessCoverage(List.of("A"))).isEqualTo(IndexModel.UniquenessCoverage.UNKNOWN);

        OracleIndexDetail nonunique =
                new OracleIndexDetail("APP", "T", "IX", "NORMAL", false, "VALID", "VISIBLE", false, false);
        OracleConstraintDetail unique = new OracleConstraintDetail(
                "APP",
                "T",
                "UQ",
                "U",
                "ENABLED",
                "VALIDATED",
                false,
                null,
                "APP",
                "IX",
                "DEFERRABLE",
                "IMMEDIATE",
                null);
        VendorFindings constraintFindings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(nonunique), false))
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_CONSTRAINTS, List.of(unique), false))
                .build();
        merged = VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, constraintFindings)
                .get(0)
                .indexes()
                .get(0);
        assertThat(merged.backingConstraint()).isEqualTo("UQ");
        assertThat(merged.uniquenessCoverage(List.of("A"))).isEqualTo(IndexModel.UniquenessCoverage.UNKNOWN);
    }
}
