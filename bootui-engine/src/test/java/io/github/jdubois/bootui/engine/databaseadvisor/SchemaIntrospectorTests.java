package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
    void realH2CatalogDistinguishesOrdinaryForeignKeyIndexesFromSpatialTableIndexOther() throws Exception {
        String url = "jdbc:h2:mem:index-methods-" + System.nanoTime();
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("create table parent (a int, b int, primary key (a, b))");
            statement.execute("create table child (id int primary key, a int, b int, shape geometry)");
            statement.execute("create index ordinary_fk on child (a desc, b asc)");
            statement.execute("create unique index ordinary_unique on child (a desc, b asc)");
            statement.execute("alter table child add constraint fk_child foreign key (a, b) references parent (a, b)");
            statement.execute("create spatial index spatial_shape on child (shape)");
            boolean spatialObserved = false;
            try (ResultSet rs =
                    connection.getMetaData().getIndexInfo(connection.getCatalog(), "PUBLIC", "CHILD", false, true)) {
                while (rs.next()) {
                    if ("SPATIAL_SHAPE".equals(rs.getString("INDEX_NAME"))) {
                        spatialObserved = true;
                        assertThat(rs.getInt("TYPE")).isEqualTo(DatabaseMetaData.tableIndexOther);
                        assertThat(rs.getString("COLUMN_NAME")).isEqualTo("SHAPE");
                        assertThat(rs.getString("FILTER_CONDITION")).isNull();
                    }
                }
            }
            assertThat(spatialObserved).isTrue();

            SchemaSnapshot snapshot =
                    SchemaIntrospector.introspect("h2", () -> DriverManager.getConnection(url), budget(), limits());

            assertThat(snapshot.available()).isTrue();
            assertThat(snapshot.complete()).isTrue();
            TableModel child = snapshot.table("CHILD");
            assertThat(child).isNotNull();
            assertThat(child.indexes())
                    .filteredOn(index -> index.name().equals("ORDINARY_UNIQUE"))
                    .singleElement()
                    .satisfies(index -> {
                        assertThat(index.method()).isEqualTo("btree");
                        assertThat(index.unique()).isTrue();
                        assertThat(index.comparisonComplete()).isFalse();
                    });
            assertThat(child.indexes())
                    .filteredOn(index -> index.columnNames().equals(List.of("ID")))
                    .singleElement()
                    .satisfies(index -> assertThat(index.method()).isNull());
            assertThat(child.indexes())
                    .filteredOn(index -> index.name().equals("ORDINARY_FK"))
                    .singleElement()
                    .satisfies(index -> {
                        assertThat(index.method()).isEqualTo("btree");
                        assertThat(index.keyParts())
                                .containsExactly(IndexKeyPart.column("A", false), IndexKeyPart.column("B", true));
                        assertThat(index.comparisonComplete()).isFalse();
                        assertThat(index.validity()).isEqualTo(IndexModel.Validity.UNKNOWN);
                        assertThat(index.visibility()).isEqualTo(IndexModel.Visibility.UNKNOWN);
                    });
            assertThat(child.indexes())
                    .filteredOn(index -> index.name().equals("SPATIAL_SHAPE"))
                    .singleElement()
                    .satisfies(index -> assertThat(index.method()).isNull());
            DatabaseAdvisorContext context = DatabaseAdvisorFixtures.context(snapshot);
            assertThat(new MissingForeignKeyIndexRule().evaluate(context).status())
                    .isEqualTo("PASS");
            assertThat(context.evaluationDiagnostics()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "spatial",
                "hash",
                "custom",
                "filtered",
                "expression",
                "wrong-scope",
                "wrong-catalog",
                "wrong-table",
                "wrong-index-schema",
                "blank-name",
                "duplicate-name",
                "case-name",
                "other-product",
                "other-driver",
                "unknown-version"
            })
    void h2RefinementDoesNotPromoteUnsupportedOrUnidentifiedIndexEvidence(String shape) throws Exception {
        Connection connection = h2Connection();
        Map<String, Object> jdbc = indexRow("idx", "a", 1);
        Map<String, Object> catalog = h2IndexRow("idx");
        switch (shape) {
            case "spatial" -> catalog.put("INDEX_TYPE_NAME", "SPATIAL INDEX");
            case "hash" -> jdbc.put("TYPE", DatabaseMetaData.tableIndexHashed);
            case "custom" -> catalog.put("INDEX_CLASS", "example.CustomIndex");
            case "filtered" -> jdbc.put("FILTER_CONDITION", "a > 0");
            case "expression" -> jdbc.remove("COLUMN_NAME");
            case "wrong-scope" -> catalog.put("TABLE_SCHEMA", "another");
            case "wrong-catalog" -> catalog.put("TABLE_CATALOG", "another");
            case "wrong-table" -> catalog.put("TABLE_NAME", "another");
            case "wrong-index-schema" -> catalog.put("INDEX_SCHEMA", "another");
            case "blank-name" -> catalog.put("INDEX_NAME", " ");
            case "case-name" -> catalog.put("INDEX_NAME", "IDX");
            case "duplicate-name" -> {}
            case "other-product" ->
                when(connection.getMetaData().getDatabaseProductName()).thenReturn("Other");
            case "other-driver" ->
                when(connection.getMetaData().getDriverName()).thenReturn("Other JDBC Driver");
            case "unknown-version" ->
                when(connection.getMetaData().getDatabaseMajorVersion()).thenThrow(new SQLException("unknown"));
            default -> throw new AssertionError(shape);
        }
        when(connection.getMetaData().getIndexInfo(any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(rows(jdbc));
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("FROM INFORMATION_SCHEMA.INDEXES")))
                .thenReturn(statement);
        when(statement.executeQuery())
                .thenReturn(shape.equals("duplicate-name") ? rows(catalog, catalog) : rows(catalog));

        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());

        assertThat(snapshot.available()).isTrue();
        DatabaseAdvisorContext context = DatabaseAdvisorFixtures.context(snapshot);
        assertThat(new MissingForeignKeyIndexRule().evaluate(context).status())
                .as(shape)
                .isEqualTo("SKIPPED");
        assertThat(context.evaluationDiagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.source()).isEqualTo("DB-SCHEMA-002"));
        if (List.of("other-product", "other-driver", "unknown-version", "filtered", "expression", "hash")
                .contains(shape)) {
            verify(connection, never()).prepareStatement(any());
        }
    }

    @Test
    void failedH2CatalogDiscardsItsRowsButRetainsJdbcIndexesAndRequiredUnknown() throws Exception {
        Connection connection = h2Connection();
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("FROM INFORMATION_SCHEMA.INDEXES")))
                .thenReturn(statement);
        ResultSet failing = mock(ResultSet.class);
        when(failing.next()).thenReturn(true).thenThrow(new SQLException("password=hunter2"));
        when(failing.getString(anyString()))
                .thenAnswer(invocation -> h2IndexRow("idx").get(invocation.getArgument(0)));
        when(statement.executeQuery()).thenReturn(failing);

        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());

        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.tables()).singleElement().satisfies(table -> {
            assertThat(table.metadata().indexesRead()).isTrue();
            assertThat(table.indexes())
                    .singleElement()
                    .satisfies(index -> assertThat(index.method()).isNull());
        });
        assertThat(snapshot.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("H2 index access methods", "could not be read")
                        .doesNotContain("hunter2"));
        DatabaseAdvisorContext context = DatabaseAdvisorFixtures.context(snapshot);
        assertThat(new MissingForeignKeyIndexRule().evaluate(context).status()).isEqualTo("SKIPPED");
        assertThat(context.evaluationDiagnostics()).isNotEmpty();
        verify(statement, never()).setQueryTimeout(anyInt());
        verify(statement).setMaxRows(limits().maxIndexesPerTable() + 1);
        verify(statement).setString(1, "app");
        verify(statement).setString(2, "public");
        verify(statement).setString(3, "t");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void boundedH2CatalogRetainsOnlyObservedMethodsWithoutClaimingCompleteCoverage(boolean observed) throws Exception {
        Connection connection = h2Connection();
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("FROM INFORMATION_SCHEMA.INDEXES")))
                .thenReturn(statement);
        when(statement.executeQuery())
                .thenReturn(rows(h2IndexRow(observed ? "idx" : "another"), h2IndexRow("overflow")));
        DatabaseAdvisorLimits limits =
                new DatabaseAdvisorLimits(10, 10, 1, 10, Duration.ofSeconds(30), Duration.ofSeconds(5));

        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits);

        assertThat(snapshot.truncated()).isTrue();
        assertThat(snapshot.complete()).isFalse();
        DatabaseAdvisorContext context = DatabaseAdvisorFixtures.context(snapshot);
        assertThat(new MissingForeignKeyIndexRule().evaluate(context).status())
                .isEqualTo(observed ? "PASS" : "SKIPPED");
        assertThat(context.evaluationDiagnostics().isEmpty()).isEqualTo(observed);
        assertThat(snapshot.tables().get(0).indexes().get(0).method()).isEqualTo(observed ? "btree" : null);
        verify(statement).setMaxRows(2);
        verify(statement).setInt(4, 2);
    }

    private static Connection h2Connection() throws Exception {
        Connection connection = connection();
        DatabaseMetaData metadata = connection.getMetaData();
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(metadata.getDriverName()).thenReturn("H2 JDBC Driver");
        when(metadata.getDatabaseMajorVersion()).thenReturn(2);
        when(metadata.getDatabaseMinorVersion()).thenReturn(5);
        when(metadata.getImportedKeys(any(), any(), any())).thenReturn(rows(fkRow("fk", 1, "a", "x")));
        when(metadata.getIndexInfo(any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(rows(indexRow("idx", "a", 1)));
        when(connection.unwrap(Connection.class)).thenReturn(mock(org.h2.jdbc.JdbcConnection.class));
        PreparedStatement timeoutRead = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("FROM INFORMATION_SCHEMA.SETTINGS")))
                .thenReturn(timeoutRead);
        when(timeoutRead.executeQuery()).thenAnswer(ignored -> rows(Map.of("SETTING_VALUE", 0)));
        when(connection.prepareStatement("SET QUERY_TIMEOUT ?")).thenReturn(mock(PreparedStatement.class));
        return connection;
    }

    @Test
    void unavailableDriverEvidenceRetainsTheSchemaWithoutAttemptingH2Refinement() throws Exception {
        Connection connection = h2Connection();
        try (var evidence = mockStatic(DriverEvidenceReader.class)) {
            evidence.when(() -> DriverEvidenceReader.read(any(), any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("unavailable"));

            SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());

            assertThat(snapshot.available()).isTrue();
            assertThat(snapshot.driver()).isNull();
            assertThat(snapshot.complete()).isFalse();
            assertThat(snapshot.tables()).singleElement().satisfies(table -> {
                assertThat(table.metadata().indexesRead()).isTrue();
                assertThat(table.indexes())
                        .singleElement()
                        .satisfies(index -> assertThat(index.method()).isNull());
            });
            assertThat(snapshot.diagnostics())
                    .anySatisfy(diagnostic ->
                            assertThat(diagnostic.message()).contains("JDBC driver settings could not be evaluated"));
            verify(connection, never()).prepareStatement(any());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "0, false, false", "0, false, true", "0, true, false", "0, true, true",
        "1234, false, false", "1234, false, true", "1234, true, false", "1234, true, true",
        "7501, false, false", "7501, false, true", "7501, true, false", "7501, true, true"
    })
    void h2RefinementPreservesExactSessionTimeoutAndJdbcCacheOnTheSamePhysicalConnection(
            int originalTimeout, boolean cached, boolean catalogFailure) throws Exception {
        try (Connection physical = DriverManager.getConnection("jdbc:h2:mem:timeout-" + System.nanoTime())) {
            createH2TimeoutSchema(physical);
            if (cached) {
                try (Statement statement = physical.createStatement()) {
                    statement.setQueryTimeout(7);
                }
            }
            setNativeH2Timeout(physical, originalTimeout);
            AtomicInteger duringCatalog = new AtomicInteger(-1);
            AtomicInteger releases = new AtomicInteger();
            Connection borrowed = borrowedH2Connection(
                    physical, originalTimeout, catalogFailure, false, true, duringCatalog, releases);

            SchemaSnapshot snapshot = SchemaIntrospector.introspect("h2", () -> borrowed, budget(), limits());

            assertThat(snapshot.available()).isTrue();
            assertThat(snapshot.complete()).isEqualTo(!catalogFailure);
            assertThat(nativeH2Timeout(physical)).isEqualTo(originalTimeout);
            try (Statement applicationStatement = physical.createStatement()) {
                assertThat(applicationStatement.getQueryTimeout())
                        .isEqualTo(cached ? 7 : (originalTimeout + 999) / 1_000);
            }
            assertThat(duringCatalog.get()).isEqualTo(originalTimeout > 0 ? Math.min(originalTimeout, 5_000) : 5_000);
            assertThat(releases.get()).isEqualTo(1);
            assertThat(physical.isClosed()).isFalse();
            assertThat(snapshot.table("CHILD").indexes())
                    .filteredOn(index -> index.columnNames().equals(List.of("PARENT_ID")))
                    .singleElement()
                    .satisfies(index -> assertThat(index.method()).isEqualTo(catalogFailure ? null : "btree"));
        }
    }

    @Test
    void unverifiablePhysicalH2ConnectionSkipsRefinementWithoutMutatingOrLeakingTheBorrowedHandle() throws Exception {
        try (Connection physical = DriverManager.getConnection("jdbc:h2:mem:opaque-" + System.nanoTime())) {
            createH2TimeoutSchema(physical);
            setNativeH2Timeout(physical, 7_501);
            AtomicInteger duringCatalog = new AtomicInteger(-1);
            AtomicInteger releases = new AtomicInteger();
            Connection borrowed = borrowedH2Connection(physical, 7_501, false, false, false, duringCatalog, releases);

            SchemaSnapshot snapshot = SchemaIntrospector.introspect("h2", () -> borrowed, budget(), limits());

            assertThat(snapshot.available()).isTrue();
            assertThat(snapshot.complete()).isFalse();
            assertThat(nativeH2Timeout(physical)).isEqualTo(7_501);
            assertThat(duringCatalog.get()).isEqualTo(-1);
            assertThat(releases.get()).isEqualTo(1);
            assertThat(physical.isClosed()).isFalse();
            assertThat(snapshot.diagnostics())
                    .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                            .contains("verifiable physical H2 connection", "not changed"));
            DatabaseAdvisorContext context = DatabaseAdvisorFixtures.context(snapshot);
            assertThat(new MissingForeignKeyIndexRule().evaluate(context).status())
                    .isEqualTo("SKIPPED");
            assertThat(context.evaluationDiagnostics()).isNotEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void h2TimeoutRestorationFailureClosesTheVerifiedPhysicalConnectionAndDiscardsPromotedEvidence(
            boolean catalogFailure) throws Exception {
        try (Connection physical = DriverManager.getConnection("jdbc:h2:mem:restore-failure-" + System.nanoTime())) {
            createH2TimeoutSchema(physical);
            AtomicInteger duringCatalog = new AtomicInteger(-1);
            AtomicInteger releases = new AtomicInteger();
            Connection borrowed =
                    borrowedH2Connection(physical, 0, catalogFailure, true, true, duringCatalog, releases);

            SchemaSnapshot snapshot = SchemaIntrospector.introspect("h2", () -> borrowed, budget(), limits());

            assertThat(snapshot.available()).isFalse();
            assertThat(snapshot.tables()).isEmpty();
            assertThat(snapshot.error())
                    .contains("H2 session query timeout", "could not be restored")
                    .doesNotContain("hunter2");
            assertThat(physical.isClosed()).isTrue();
            assertThat(releases.get()).isEqualTo(1);
        }
    }

    @Test
    void h2PhysicalCloseFailureReportsSafeCleanupFailureAndReleasesTheLogicalHandleOnce() throws Exception {
        String secret = "synthetic-h2-physical-close-secret";
        try (Connection physical = DriverManager.getConnection("jdbc:h2:mem:close-failure-" + System.nanoTime())) {
            createH2TimeoutSchema(physical);
            AtomicInteger releases = new AtomicInteger();
            Connection borrowed = borrowedH2Connection(physical, 0, false, true, true, new AtomicInteger(-1), releases);
            Connection closeTarget = mock(org.h2.jdbc.JdbcConnection.class);
            doThrow(new SQLException(secret)).when(closeTarget).close();
            Connection failingClose = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("unwrap") && args[0] == Connection.class) {
                            return closeTarget;
                        }
                        return invokeJdbc(borrowed, method, args);
                    });

            SchemaSnapshot snapshot = SchemaIntrospector.introspect("h2", () -> failingClose, budget(), limits());

            assertThat(snapshot.available()).isFalse();
            assertThat(snapshot.tables()).isEmpty();
            assertThat(snapshot.error())
                    .contains("H2 session query timeout", "could not be restored", "cleanup also failed")
                    .doesNotContain(secret);
            assertThat(snapshot.diagnostics())
                    .allSatisfy(diagnostic -> assertThat(diagnostic.message()).doesNotContain(secret));
            verify(closeTarget).close();
            assertThat(releases.get()).isEqualTo(1);
        }
    }

    private static void createH2TimeoutSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table parent (id int primary key)");
            statement.execute("create table child (id int primary key, parent_id int references parent(id))");
        }
    }

    private static int nativeH2Timeout(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME='QUERY_TIMEOUT'")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static void setNativeH2Timeout(Connection connection, int milliseconds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SET QUERY_TIMEOUT ?")) {
            statement.setInt(1, milliseconds);
            statement.executeUpdate();
        }
    }

    private static Connection borrowedH2Connection(
            Connection physical,
            int originalTimeout,
            boolean catalogFailure,
            boolean restorationFailure,
            boolean exposePhysical,
            AtomicInteger duringCatalog,
            AtomicInteger releases) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        releases.incrementAndGet();
                        return null;
                    }
                    if (method.getName().equals("unwrap") && args[0] == Connection.class) {
                        return exposePhysical ? physical : proxy;
                    }
                    Object result = invokeJdbc(physical, method, args);
                    if (!method.getName().equals("prepareStatement")) {
                        return result;
                    }
                    String sql = (String) args[0];
                    if (!sql.contains("FROM INFORMATION_SCHEMA.INDEXES") && !sql.equals("SET QUERY_TIMEOUT ?")) {
                        return result;
                    }
                    PreparedStatement statement = (PreparedStatement) result;
                    AtomicInteger value = new AtomicInteger(-1);
                    return Proxy.newProxyInstance(
                            PreparedStatement.class.getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (inner, call, arguments) -> {
                                if (call.getName().equals("setInt")) {
                                    value.set((int) arguments[1]);
                                }
                                if (sql.contains("FROM INFORMATION_SCHEMA.INDEXES")
                                        && call.getName().equals("executeQuery")) {
                                    duringCatalog.set(nativeH2Timeout(physical));
                                    if (catalogFailure) {
                                        throw new SQLException("password=hunter2");
                                    }
                                }
                                if (sql.equals("SET QUERY_TIMEOUT ?")
                                        && call.getName().equals("executeUpdate")
                                        && restorationFailure
                                        && value.get() == originalTimeout) {
                                    throw new SQLException("password=hunter2");
                                }
                                return invokeJdbc(statement, call, arguments);
                            });
                });
    }

    private static Object invokeJdbc(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    private static Map<String, Object> h2IndexRow(String name) {
        return new HashMap<>(Map.of(
                "TABLE_CATALOG",
                "app",
                "TABLE_SCHEMA",
                "public",
                "TABLE_NAME",
                "t",
                "INDEX_SCHEMA",
                "public",
                "INDEX_NAME",
                name,
                "INDEX_TYPE_NAME",
                "INDEX",
                "INDEX_CLASS",
                "org.h2.mvstore.db.MVSecondaryIndex"));
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

    @Test
    void completeEmptyRelationInventoryDoesNotMakeAnOtherwiseCompleteDatasourcePartial() throws Exception {
        Connection connection = connection();
        when(connection.getMetaData().getTables(any(), any(), any(), any())).thenReturn(rows());

        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());

        assertThat(snapshot.tables()).isEmpty();
        assertThat(snapshot.relationInventoryComplete()).isTrue();
        assertThat(snapshot.complete()).isTrue();
    }

    @Test
    void incompletePostgresRelationInventoryIsNotCompleteEvidence() {
        SchemaSnapshot snapshot = new SchemaSnapshot(
                "ds",
                Dialect.POSTGRESQL,
                "PostgreSQL",
                DatabaseVersion.UNKNOWN,
                null,
                List.of(),
                VendorFindings.EMPTY,
                List.of(),
                false,
                null,
                false);
        assertThat(snapshot.complete()).isFalse();
    }

    @Test
    void missingDriverMetadataIsReportedAsIncompleteEvidence() throws Exception {
        Connection connection = connection();
        when(connection.getMetaData().getDriverVersion()).thenThrow(new SQLException("unsupported"));
        when(connection.getMetaData().getURL()).thenReturn(null);

        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());

        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.level()).isEqualTo("WARNING");
            assertThat(diagnostic.message()).contains("driver version");
        });
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.level()).isEqualTo("WARNING");
            assertThat(diagnostic.message()).contains("no JDBC URL");
        });
    }

    @Test
    void nullDriverNameIsReportedAsIncompleteEvidence() throws Exception {
        Connection connection = connection();
        when(connection.getMetaData().getDriverName()).thenReturn(null);

        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());

        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.level()).isEqualTo("WARNING");
            assertThat(diagnostic.message()).contains("driver name");
        });
    }

    @Test
    void nullDriverVersionIsReportedAsIncompleteEvidence() throws Exception {
        Connection connection = connection();
        when(connection.getMetaData().getDriverVersion()).thenReturn(null);

        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());

        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.level()).isEqualTo("WARNING");
            assertThat(diagnostic.message()).contains("driver version");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIEW", "MATERIALIZED VIEW"})
    void introspectionPreservesViewTypesAndNullableColumns(String type) throws Exception {
        Connection connection = connection();
        DatabaseMetaData metadata = connection.getMetaData();
        when(metadata.getTableTypes())
                .thenReturn(rows(
                        Map.of("TABLE_TYPE", "TABLE"),
                        Map.of("TABLE_TYPE", "VIEW"),
                        Map.of("TABLE_TYPE", "MATERIALIZED VIEW")));
        when(metadata.getTables(any(), any(), any(), any()))
                .thenReturn(rows(
                        Map.of("TABLE_CAT", "app", "TABLE_SCHEM", "public", "TABLE_NAME", "t", "TABLE_TYPE", type)));

        SchemaSnapshot snapshot = SchemaIntrospector.introspect("ds", () -> connection, budget(), limits());

        assertThat(snapshot.relationInventoryComplete()).isTrue();
        assertThat(snapshot.tables()).singleElement().satisfies(view -> {
            assertThat(view.type()).isEqualTo(type);
            assertThat(view.metadata().columnsRead()).isTrue();
            assertThat(view.columns()).singleElement().satisfies(column -> {
                assertThat(column.name()).isEqualTo("a");
                assertThat(column.nullability()).isEqualTo(ColumnModel.Nullability.NULLABLE);
            });
        });
        assertThat(DatabaseAdvisorContext.physicalTables(snapshot)).isEmpty();
        verify(metadata)
                .getTables(any(), any(), any(), argThat(types -> List.of(types).contains(type)));
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
        when(metadata.getDriverName()).thenReturn("Test JDBC Driver");
        when(metadata.getDriverVersion()).thenReturn("1.0");
        when(metadata.getURL()).thenReturn("jdbc:h2:mem:test");
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
