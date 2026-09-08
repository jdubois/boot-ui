package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SchemaIntrospectorTests {

    private static DatabaseAdvisorLimits limits() {
        return DatabaseAdvisorLimits.DEFAULTS;
    }

    private static ScanBudget budget() {
        return ScanBudget.of(Duration.ofSeconds(30));
    }

    @Test
    void readForeignKeysGroupsAnUnnamedCompositeForeignKeyIntoOneConstraint() throws Exception {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet rs = mock(ResultSet.class);
        when(metaData.getImportedKeys(any(), any(), any())).thenReturn(rs);
        when(rs.getString("FKTABLE_CAT")).thenReturn("cat");
        when(rs.getString("FKTABLE_SCHEM")).thenReturn("schema");
        when(rs.getString("FKTABLE_NAME")).thenReturn("child");
        // Simulates a driver reporting an unnamed composite foreign key (child.a, child.b) -> parent(a, b)
        // followed by a second, unrelated unnamed single-column foreign key on the same table.
        when(rs.next()).thenReturn(true, true, true, false);
        when(rs.getString("FK_NAME")).thenReturn(null, null, null);
        when(rs.getInt("KEY_SEQ")).thenReturn(1, 2, 1);
        when(rs.getString("FKCOLUMN_NAME")).thenReturn("A", "B", "C");
        when(rs.getString("PKCOLUMN_NAME")).thenReturn("A", "B", "ID");
        when(rs.getString("PKTABLE_NAME")).thenReturn("parent", "parent", "other");
        when(rs.getString("PKTABLE_SCHEM")).thenReturn("public", "public", "public");

        List<ForeignKeyModel> foreignKeys = SchemaIntrospector.readForeignKeys(metaData, "cat", "schema", "child");

        assertThat(foreignKeys).hasSize(2);
        assertThat(foreignKeys.get(0).columns()).containsExactly("A", "B");
        assertThat(foreignKeys.get(0).referencedColumns()).containsExactly("A", "B");
        assertThat(foreignKeys.get(0).referencedTable()).isEqualTo("parent");
        assertThat(foreignKeys.get(0).referencedSchema()).isEqualTo("public");
        assertThat(foreignKeys.get(1).columns()).containsExactly("C");
        assertThat(foreignKeys.get(1).referencedColumns()).containsExactly("ID");
        assertThat(foreignKeys.get(1).referencedTable()).isEqualTo("other");
    }

    @Test
    void readForeignKeysKeepsNamedForeignKeysGroupedByNameAndCarriesTheReferencedColumns() throws Exception {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet rs = mock(ResultSet.class);
        when(metaData.getImportedKeys(any(), any(), any())).thenReturn(rs);
        when(rs.getString("FKTABLE_CAT")).thenReturn("cat");
        when(rs.getString("FKTABLE_SCHEM")).thenReturn("schema");
        when(rs.getString("FKTABLE_NAME")).thenReturn("child");
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("FK_NAME")).thenReturn("fk_child_parent", "fk_child_parent");
        when(rs.getInt("KEY_SEQ")).thenReturn(1, 2);
        when(rs.getString("FKCOLUMN_NAME")).thenReturn("A", "B");
        when(rs.getString("PKCOLUMN_NAME")).thenReturn("TENANT_ID", "ID");
        when(rs.getString("PKTABLE_NAME")).thenReturn("parent", "parent");

        List<ForeignKeyModel> foreignKeys = SchemaIntrospector.readForeignKeys(metaData, "cat", "schema", "child");

        assertThat(foreignKeys).hasSize(1);
        assertThat(foreignKeys.get(0).name()).isEqualTo("fk_child_parent");
        assertThat(foreignKeys.get(0).columns()).containsExactly("A", "B");
        assertThat(foreignKeys.get(0).referencedColumns()).containsExactly("TENANT_ID", "ID");
        assertThat(foreignKeys.get(0).consistent()).isTrue();
    }

    @Test
    void introspectRestoresTheConnectionsOriginalReadOnlyState() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isReadOnly()).thenReturn(false);
        when(connection.getMetaData()).thenThrow(new SQLException("metadata unavailable"));

        SchemaSnapshot snapshot = SchemaIntrospector.introspect("primary", () -> connection, budget(), limits());

        assertThat(snapshot.available()).isFalse();
        verify(connection).setReadOnly(true);
        verify(connection).setReadOnly(false);
    }

    @Test
    void introspectLeavesAnAlreadyReadOnlyConnectionAlone() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.getMetaData()).thenThrow(new SQLException("metadata unavailable"));

        SchemaIntrospector.introspect("primary", () -> connection, budget(), limits());

        verify(connection, never()).setReadOnly(false);
    }

    @Test
    void introspectRedactsCredentialsFromAConnectionFailure() {
        SchemaSnapshot snapshot = SchemaIntrospector.introspect(
                "primary",
                () -> {
                    throw new SQLException("FATAL: password authentication failed for "
                            + "jdbc:postgresql://app:sup3rs3cret@db.internal:5432/orders");
                },
                budget(),
                limits());

        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.error()).doesNotContain("sup3rs3cret").contains("******@db.internal");
        assertThat(snapshot.diagnostics()).hasSize(1);
        assertThat(snapshot.diagnostics().get(0).level()).isEqualTo(SchemaDiagnostic.ERROR);
    }

    @Test
    void introspectFailsFastWhenTheScanBudgetIsAlreadySpent() {
        ScanBudget spent = ScanBudget.of(Duration.ZERO, () -> 0L);
        SchemaSnapshot snapshot = SchemaIntrospector.introspect(
                "primary",
                () -> {
                    throw new IllegalStateException("the datasource must never be touched");
                },
                spent,
                limits());

        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.error()).contains("scan budget ran out");
    }

    @Test
    void introspectReportsAMissingDataSourceRatherThanThrowing() {
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("primary", (javax.sql.DataSource) null);
        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.error()).contains("not available");
    }

    // --- Oracle dialect confirmation: a driver-reported "Oracle" product name is not proof by itself ---

    @Test
    void resolveOracleConfirmsAGenuineOracleServerViaVVersion() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any())).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("Oracle Database 19c Enterprise Edition Release 19.0.0.0.0 - Production");

        List<SchemaDiagnostic> diagnostics = new ArrayList<>();
        Dialect resolved = SchemaIntrospector.resolveOracle(connection, Dialect.ORACLE, "primary", diagnostics);

        assertThat(resolved).isEqualTo(Dialect.ORACLE);
        assertThat(diagnostics).isEmpty();
        verify(statement).setQueryTimeout(5);
    }

    @Test
    void resolveOracleConfirmsANewerOracleAiDatabaseRebrandedBanner() throws Exception {
        // Oracle rebranded v$version.banner from "Oracle Database ..." to "Oracle AI Database ..." starting
        // with the 23ai/26ai line; the confirmation must not be a rigid "Oracle Database" prefix match.
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any())).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1))
                .thenReturn("Oracle AI Database 26ai Free Release 23.26.2.0.0 - Develop, Learn, and Run for Free");

        Dialect resolved = SchemaIntrospector.resolveOracle(connection, Dialect.ORACLE, "primary", new ArrayList<>());

        assertThat(resolved).isEqualTo(Dialect.ORACLE);
        verify(statement).setQueryTimeout(5);
    }

    @Test
    void resolveOracleRejectsAnOracleCompatibleLookalikeThatDoesNotConfirmInVVersion() throws Exception {
        // OceanBase's driver can report getDatabaseProductName() == "Oracle" for an Oracle-mode tenant, but its
        // own v$version.banner self-identifies as "OceanBase Database ... (Oracle Compatible Mode)": "Database"
        // appears before "Oracle" there, so the order-sensitive check correctly does not confirm it.
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet versionResult = mock(ResultSet.class);
        ResultSet productResult = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(contains("v$version"))).thenReturn(versionResult);
        when(versionResult.next()).thenReturn(true, false);
        when(versionResult.getString(1)).thenReturn("OceanBase Database 4.2.0.0 (Oracle Compatible Mode)");
        when(statement.executeQuery(contains("product_component_version"))).thenReturn(productResult);
        when(productResult.next()).thenReturn(false);

        List<SchemaDiagnostic> diagnostics = new ArrayList<>();
        Dialect resolved = SchemaIntrospector.resolveOracle(connection, Dialect.ORACLE, "primary", diagnostics);

        assertThat(resolved).isEqualTo(Dialect.GENERIC);
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).message()).contains("neither v$version nor product_component_version");
    }

    @Test
    void resolveOracleFallsBackToProductComponentVersionWhenVVersionCannotBeRead() throws Exception {
        // A locked-down role may not have SELECT on v$ views at all, unlike product_component_version.
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet productResult = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(contains("v$version")))
                .thenThrow(new SQLException("ORA-00942: table or view does not exist"));
        when(statement.executeQuery(contains("product_component_version"))).thenReturn(productResult);
        when(productResult.next()).thenReturn(true, false);
        when(productResult.getString(1)).thenReturn("Oracle Database 19c Enterprise Edition");

        Dialect resolved = SchemaIntrospector.resolveOracle(connection, Dialect.ORACLE, "primary", new ArrayList<>());

        assertThat(resolved).isEqualTo(Dialect.ORACLE);
    }

    @Test
    void resolveOracleFallsBackToGenericWhenNeitherSourceCanBeRead() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any())).thenThrow(new SQLException("ORA-00942: table or view does not exist"));

        List<SchemaDiagnostic> diagnostics = new ArrayList<>();
        Dialect resolved = SchemaIntrospector.resolveOracle(connection, Dialect.ORACLE, "primary", diagnostics);

        assertThat(resolved).isEqualTo(Dialect.GENERIC);
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).message()).contains("neither v$version nor product_component_version");
    }

    @Test
    void resolveOracleLeavesEveryOtherDialectUntouched() {
        List<SchemaDiagnostic> diagnostics = new ArrayList<>();
        Dialect resolved =
                SchemaIntrospector.resolveOracle(mock(Connection.class), Dialect.POSTGRESQL, "primary", diagnostics);
        assertThat(resolved).isEqualTo(Dialect.POSTGRESQL);
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void oracleScopeFailureNeverFallsBackToAnUnscopedTableScan() throws Exception {
        Connection connection = connection();
        when(connection.getMetaData().getDatabaseProductName()).thenReturn("Oracle");
        Statement statement = mock(Statement.class);
        ResultSet banner = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(contains("v$version"))).thenReturn(banner);
        when(banner.next()).thenReturn(true, false);
        when(banner.getString(1)).thenReturn("Oracle Database 19c");
        when(statement.executeQuery(contains("CURRENT_SCHEMA"))).thenThrow(new SQLException("scope unavailable"));
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());
        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.error()).contains("not widened");
        verify(connection.getMetaData(), never()).getTables(any(), any(), any(), any());
    }

    @Test
    void namedForeignKeysAreSortedByPositionDespiteInterleaving() throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(metadata.getImportedKeys(any(), any(), any()))
                .thenReturn(rows(fkRow("left", 2, "b", "y"), fkRow("right", 1, "c", "z"), fkRow("left", 1, "a", "x")));
        List<ForeignKeyModel> keys = SchemaIntrospector.readForeignKeys(metadata, "app", "public", "t");
        assertThat(keys.get(0).columns()).containsExactly("a", "b");
        assertThat(keys.get(0).referencedColumns()).containsExactly("x", "y");
        assertThat(keys.get(0).deleteRule()).isEqualTo(DatabaseMetaData.importedKeyCascade);
        assertThat(keys.get(0).deferrability()).isEqualTo(DatabaseMetaData.importedKeyNotDeferrable);
    }

    @Test
    void anonymousInterleavingAndMissingPositionsAreUnknownNotInventedRelationships() throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(metadata.getImportedKeys(any(), any(), any()))
                .thenReturn(rows(
                        fkRow(null, 1, "a", "x"), fkRow(null, 1, "c", "x"),
                        fkRow(null, 2, "b", "y"), fkRow(null, 2, "d", "y")));
        assertThatThrownBy(() -> SchemaIntrospector.readForeignKeys(metadata, "app", "public", "t"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ambiguous");
        when(metadata.getImportedKeys(any(), any(), any())).thenReturn(rows(fkRow("fk", 2, "a", "x")));
        assertThatThrownBy(() -> SchemaIntrospector.readForeignKeys(metadata, "app", "public", "t"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("consecutive");
    }

    @Test
    void nullForeignKeyFlagsStayUnknown() throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Map<String, Object> row = fkRow("fk", 1, "a", "x");
        row.remove("UPDATE_RULE");
        row.remove("DELETE_RULE");
        row.remove("DEFERRABILITY");
        when(metadata.getImportedKeys(any(), any(), any())).thenReturn(rows(row));
        ForeignKeyModel key = SchemaIntrospector.readForeignKeys(metadata, "app", "public", "t")
                .get(0);
        assertThat(key.updateRule()).isNull();
        assertThat(key.deleteRule()).isNull();
        assertThat(key.deferrability()).isNull();
    }

    @Test
    void ambiguousAnonymousRelationshipsDoNotDiscardIndependentNamedEvidence() throws Exception {
        Connection connection = connection();
        when(connection.getMetaData().getImportedKeys(any(), any(), any()))
                .thenReturn(rows(
                        fkRow("trusted", 1, "a", "x"), fkRow(null, 1, "b", "x"),
                        fkRow(null, 1, "c", "x"), fkRow(null, 2, "d", "y")));
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());
        TableModel table = snapshot.tables().get(0);
        assertThat(table.metadata().foreignKeysRead()).isFalse();
        assertThat(table.foreignKeys()).extracting(ForeignKeyModel::name).containsExactly("trusted");
        assertThat(snapshot.complete()).isFalse();
    }

    @Test
    void unknownOriginalReadOnlyStateIsNeverMutated() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isReadOnly()).thenThrow(new SQLException("unsupported"));
        when(connection.getMetaData()).thenThrow(new SQLException("metadata unavailable"));
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());
        verify(connection, never()).setReadOnly(anyBoolean());
        assertThat(snapshot.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("was not changed"));
    }

    @Test
    void restorationFailurePreservesCollectedFactsAndBecomesADiagnostic() throws Exception {
        Connection connection = connection();
        doThrow(new SQLException("restore refused")).when(connection).setReadOnly(false);
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());
        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.tables()).hasSize(1);
        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.diagnostics())
                .anySatisfy(diagnostic ->
                        assertThat(diagnostic.message()).contains("could not be restored", "restore refused"));
    }

    @Test
    void nullPrimitivesAndIndexOrdinalOrderArePreserved() throws Exception {
        Connection connection = connection();
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, Object> column = columnRow("app", "public", "t", "value");
        column.remove("NULLABLE");
        when(metadata.getColumns(any(), any(), any(), any())).thenReturn(rows(column));
        Map<String, Object> second = indexRow("idx", "second", 2);
        Map<String, Object> first = indexRow("idx", "first", 1);
        second.remove("NON_UNIQUE");
        first.remove("NON_UNIQUE");
        when(metadata.getIndexInfo(any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(rows(second, first));
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());
        TableModel table = snapshot.tables().get(0);
        assertThat(table.columns().get(0).nullability()).isEqualTo(ColumnModel.Nullability.UNKNOWN);
        assertThat(table.indexes().get(0).columnNames()).containsExactly("first", "second");
        assertThat(table.indexes().get(0).uniquenessKnown()).isFalse();
        assertThat(table.indexes().get(0).enforcesUniquenessOver(List.of("first", "second")))
                .isFalse();
    }

    @Test
    void columnPatternsCannotMixCaseDistinctOrCrossSchemaObjects() throws Exception {
        Connection connection = connection();
        DatabaseMetaData metadata = connection.getMetaData();
        when(metadata.getSearchStringEscape()).thenReturn("\\");
        when(metadata.getTables(any(), any(), any(), any())).thenReturn(rows(tableRow("a_%")));
        when(metadata.getColumns(any(), any(), any(), any()))
                .thenReturn(rows(
                        columnRow("app", "public", "a_%", "exact"),
                        columnRow("app", "other", "a_%", "other_schema"),
                        columnRow("elsewhere", "public", "a_%", "other_catalog"),
                        columnRow("app", "public", "A_%", "other_case"),
                        columnRow("app", "public", "aXanything", "pattern_sibling")));
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());
        assertThat(snapshot.tables().get(0).columns())
                .extracting(ColumnModel::name)
                .containsExactly("exact");
        verify(metadata).getColumns("app", "public", "a\\_\\%", "%");
    }

    @Test
    void nullQualifiersAreNotCollapsedIntoEmptyQualifiers() throws Exception {
        Connection connection = connection();
        when(connection.getCatalog()).thenReturn(null);
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, Object> table = new HashMap<>(tableRow("t"));
        table.put("TABLE_CAT", null);
        table.put("TABLE_SCHEM", null);
        when(metadata.getTables(any(), any(), any(), any())).thenReturn(rows(table));
        when(metadata.getColumns(any(), any(), any(), any()))
                .thenReturn(rows(columnRow(null, null, "t", "exact"), columnRow("", "", "t", "empty")));
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());
        assertThat(snapshot.tables().get(0).columns())
                .extracting(ColumnModel::name)
                .containsExactly("exact");
        verify(metadata).getColumns(null, null, "t", "%");
    }

    @Test
    void rejectedTableRowsStillConsumeTheRawBound() throws Exception {
        Connection connection = connection();
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, Object> system = new HashMap<>(tableRow("hidden"));
        system.put("TABLE_SCHEM", "information_schema");
        when(metadata.getTables(any(), any(), any(), any())).thenReturn(rows(system, tableRow("t")));
        DatabaseAdvisorLimits limits =
                new DatabaseAdvisorLimits(1, 10, 10, 10, Duration.ofSeconds(30), Duration.ofSeconds(5));
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits);
        assertThat(snapshot.truncated()).isTrue();
        assertThat(snapshot.tables()).isEmpty();
    }

    @Test
    void rawColumnBoundsReachTheSnapshotAndDisableAbsenceConclusions() throws Exception {
        Connection connection = connection();
        DatabaseMetaData metadata = connection.getMetaData();
        when(metadata.getColumns(any(), any(), any(), any()))
                .thenReturn(rows(columnRow("app", "other", "t", "sibling"), columnRow("app", "public", "t", "real")));
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), smallLimits());
        assertThat(snapshot.truncated()).isTrue();
        assertThat(snapshot.tables().get(0).metadata().columnsRead()).isFalse();
    }

    @Test
    void primaryKeyRowsOrderedByColumnNameRetainKeySequence() throws Exception {
        Connection connection = connection();
        when(connection.getMetaData().getPrimaryKeys(any(), any(), any()))
                .thenReturn(rows(
                        Map.of(
                                "KEY_SEQ",
                                2,
                                "COLUMN_NAME",
                                "id",
                                "PK_NAME",
                                "pk",
                                "TABLE_CAT",
                                "app",
                                "TABLE_SCHEM",
                                "public",
                                "TABLE_NAME",
                                "t"),
                        Map.of(
                                "KEY_SEQ",
                                1,
                                "COLUMN_NAME",
                                "tenant_id",
                                "PK_NAME",
                                "pk",
                                "TABLE_CAT",
                                "app",
                                "TABLE_SCHEM",
                                "public",
                                "TABLE_NAME",
                                "t")));
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());
        assertThat(snapshot.tables().get(0).metadata().primaryKeyRead()).isTrue();
        assertThat(snapshot.tables().get(0).primaryKeyColumns()).containsExactly("tenant_id", "id");
    }

    @Test
    void primaryAndForeignKeyRowsAreBounded() throws Exception {
        Connection connection = connection();
        DatabaseMetaData metadata = connection.getMetaData();
        when(metadata.getPrimaryKeys(any(), any(), any()))
                .thenReturn(rows(
                        Map.of(
                                "KEY_SEQ",
                                1,
                                "COLUMN_NAME",
                                "a",
                                "PK_NAME",
                                "pk",
                                "TABLE_CAT",
                                "app",
                                "TABLE_SCHEM",
                                "public",
                                "TABLE_NAME",
                                "t"),
                        Map.of(
                                "KEY_SEQ",
                                2,
                                "COLUMN_NAME",
                                "b",
                                "PK_NAME",
                                "pk",
                                "TABLE_CAT",
                                "app",
                                "TABLE_SCHEM",
                                "public",
                                "TABLE_NAME",
                                "t")));
        when(metadata.getImportedKeys(any(), any(), any()))
                .thenReturn(rows(fkRow("fk", 1, "a", "x"), fkRow("fk", 2, "b", "y")));
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), smallLimits());
        assertThat(snapshot.truncated()).isTrue();
        assertThat(snapshot.tables().get(0).metadata().primaryKeyRead()).isFalse();
        assertThat(snapshot.tables().get(0).metadata().foreignKeysRead()).isFalse();
    }

    @Test
    void truncatedIndexGroupsNeverBecomeCompletePrefixes() throws Exception {
        Connection connection = connection();
        DatabaseMetaData metadata = connection.getMetaData();
        when(metadata.getIndexInfo(any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(rows(indexRow("idx", "a", 1), indexRow("idx", "b", 2)));
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), smallLimits());
        assertThat(snapshot.truncated()).isTrue();
        assertThat(snapshot.tables().get(0).metadata().indexesRead()).isFalse();
        assertThat(snapshot.tables().get(0).indexes()).isEmpty();
    }

    @Test
    void advertisedViewsAreInventoriedButUnknownKindsDoNotProveAbsence() throws Exception {
        Connection connection = connection();
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());
        assertThat(snapshot.relationInventoryComplete()).isTrue();
        when(connection.getMetaData().getTableTypes()).thenThrow(new SQLException("unsupported"));
        when(connection.getMetaData().getTables(any(), any(), any(), any())).thenReturn(rows());
        snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());
        assertThat(snapshot.relationInventoryComplete()).isFalse();
    }

    private static DatabaseAdvisorLimits smallLimits() {
        return new DatabaseAdvisorLimits(10, 1, 1, 1, Duration.ofSeconds(30), Duration.ofSeconds(5));
    }

    @Test
    void failedProductIdentificationLeavesVendorApplicabilityUnknown() throws Exception {
        Connection connection = connection();
        when(connection.getMetaData().getDatabaseProductName()).thenThrow(new SQLException("unreadable product"));
        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());
        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.diagnostics())
                .anyMatch(diagnostic -> diagnostic.level().equals("WARNING")
                        && diagnostic.message().contains("vendor-check applicability is unknown"));
    }

    private static Connection connection() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("app");
        when(metadata.getDatabaseProductName()).thenReturn("generic");
        when(metadata.getTableTypes()).thenReturn(rows(Map.of("TABLE_TYPE", "TABLE"), Map.of("TABLE_TYPE", "VIEW")));
        when(metadata.getTables(any(), any(), any(), any())).thenReturn(rows(tableRow("t")));
        when(metadata.getColumns(any(), any(), any(), any())).thenReturn(rows(columnRow("app", "public", "t", "a")));
        when(metadata.getPrimaryKeys(any(), any(), any())).thenReturn(rows());
        when(metadata.getImportedKeys(any(), any(), any())).thenReturn(rows());
        when(metadata.getIndexInfo(any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(rows());
        return connection;
    }

    private static Map<String, Object> tableRow(String name) {
        return Map.of("TABLE_CAT", "app", "TABLE_SCHEM", "public", "TABLE_NAME", name, "TABLE_TYPE", "TABLE");
    }

    private static Map<String, Object> columnRow(String catalog, String schema, String table, String column) {
        Map<String, Object> row = new HashMap<>();
        row.put("TABLE_CAT", catalog);
        row.put("TABLE_SCHEM", schema);
        row.put("TABLE_NAME", table);
        row.put("COLUMN_NAME", column);
        row.put("DATA_TYPE", java.sql.Types.INTEGER);
        row.put("TYPE_NAME", "integer");
        row.put("NULLABLE", DatabaseMetaData.columnNullable);
        return row;
    }

    private static Map<String, Object> indexRow(String name, String column, int position) {
        return new HashMap<>(Map.of(
                "INDEX_NAME",
                name,
                "COLUMN_NAME",
                column,
                "ORDINAL_POSITION",
                position,
                "TYPE",
                DatabaseMetaData.tableIndexOther,
                "NON_UNIQUE",
                true,
                "ASC_OR_DESC",
                "A",
                "TABLE_CAT",
                "app",
                "TABLE_SCHEM",
                "public",
                "TABLE_NAME",
                "t"));
    }

    private static Map<String, Object> fkRow(String name, int position, String child, String parent) {
        Map<String, Object> row = new HashMap<>();
        row.put("FK_NAME", name);
        row.put("KEY_SEQ", position);
        row.put("FKCOLUMN_NAME", child);
        row.put("PKCOLUMN_NAME", parent);
        row.put("PKTABLE_NAME", "parent");
        row.put("FKTABLE_CAT", "app");
        row.put("FKTABLE_SCHEM", "public");
        row.put("FKTABLE_NAME", "t");
        row.put("UPDATE_RULE", DatabaseMetaData.importedKeyCascade);
        row.put("DELETE_RULE", DatabaseMetaData.importedKeyCascade);
        row.put("DEFERRABILITY", DatabaseMetaData.importedKeyNotDeferrable);
        return row;
    }

    @SafeVarargs
    private static ResultSet rows(Map<String, Object>... rows) throws SQLException {
        AtomicInteger position = new AtomicInteger(-1);
        AtomicBoolean wasNull = new AtomicBoolean();
        return ResultSet.class.cast(Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "next" -> position.incrementAndGet() < rows.length;
                        case "close" -> null;
                        case "wasNull" -> wasNull.get();
                        case "getString", "getInt", "getBoolean" -> {
                            Object value = rows[position.get()].get(arguments[0]);
                            wasNull.set(value == null);
                            yield switch (method.getName()) {
                                case "getString" -> value == null ? null : value.toString();
                                case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                                default -> Boolean.TRUE.equals(value);
                            };
                        }
                        default -> throw new java.sql.SQLFeatureNotSupportedException(method.getName());
                    };
                }));
    }
}
