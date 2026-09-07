package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class VendorCatalogReaderTests {

    @Test
    void postgresPublicationQueryFiltersActionsAndPreservesUnknownIdentityEvidence() throws Exception {
        List<String> queries = new ArrayList<>();
        VendorFindings findings = postgres(
                18,
                queries,
                sql -> sql.contains("pg_publication_tables")
                        ? List.of(Map.of("schema_name", "public", "table_name", "published"))
                        : List.of());
        String publication = queries.stream()
                .filter(sql -> sql.contains("pg_publication_tables"))
                .findFirst()
                .orElseThrow();
        assertThat(publication)
                .contains(
                        "p.pubupdate or p.pubdelete",
                        "p.pubname = pt.pubname",
                        "n.nspname = pt.schemaname",
                        "t.relnamespace = n.oid",
                        "t.relname = pt.tablename",
                        "t.relkind in ('r', 'p')",
                        "limit ?");
        assertThat(findings.available(VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES))
                .isTrue();
        assertThat(findings.findings(VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES))
                .containsExactly(new PostgresReplicaIdentityCandidate("public", "published", null, null));
        queries.clear();
        VendorFindings old = postgres(9, queries, sql -> List.of());
        assertThat(queries).noneMatch(sql -> sql.contains("pg_publication_tables"));
        assertThat(old.available(VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES))
                .isFalse();
    }

    @Test
    void postgresRetainsHiddenCounterAndReadsDefinitionBounds() throws Exception {
        List<String> queries = new ArrayList<>();
        VendorFindings findings = postgres(
                18,
                queries,
                sql -> sql.contains("from pg_sequences")
                        ? List.of(Map.of(
                                "schema_name",
                                "public",
                                "sequence_name",
                                "hidden",
                                "min_value",
                                BigDecimal.ONE,
                                "max_value",
                                BigDecimal.valueOf(1000),
                                "start_value",
                                BigDecimal.TEN,
                                "increment_by",
                                5L,
                                "cache_size",
                                BigDecimal.valueOf(20)))
                        : List.of());
        assertThat(findings.findings(VendorFindingKinds.POSTGRES_SEQUENCES))
                .singleElement()
                .satisfies(sequence -> {
                    assertThat(sequence.lastValue()).isNull();
                    assertThat(sequence.startValue()).isEqualTo(BigInteger.TEN);
                    assertThat(sequence.incrementBy()).isEqualTo(5L);
                    assertThat(sequence.percentUsed()).isEqualTo(-1);
                });
        assertThat(queries.stream()
                        .filter(sql -> sql.contains("from pg_sequences"))
                        .findFirst()
                        .orElseThrow())
                .doesNotContain("s.last_value is not null");
    }

    @Test
    void postgresIndexPartsKeepTrailingExpressionPayloadAndComparisonMetadata() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("schema_name", "Sales");
        row.put("table_name", "Orders");
        row.put("index_name", "idx");
        row.put("key_column_count", 2);
        row.put("key_columns", new String[] {"tenant", null});
        row.put("key_expressions", new String[] {null, "lower(email)"});
        row.put("key_semantics", new String[] {"0:0:1978", "1:100:3126"});
        row.put("included_columns", new String[] {"payload"});
        row.put("is_valid", true);
        row.put("is_ready", true);
        row.put("is_live", true);
        row.put("is_unique", true);
        row.put("is_primary", false);
        row.put("is_partial", false);
        row.put("nulls_not_distinct", false);
        row.put("has_expression", true);
        row.put("method", "btree");
        VendorFindings findings =
                postgres(18, new ArrayList<>(), sql -> sql.contains("as key_columns") ? List.of(row) : List.of());
        assertThat(findings.findings(VendorFindingKinds.POSTGRES_INDEX_DETAILS))
                .singleElement()
                .satisfies(index -> {
                    assertThat(index.definitionComplete()).isTrue();
                    assertThat(index.keyParts()).hasSize(2);
                    assertThat(index.keyParts().get(0).columnName()).isEqualTo("tenant");
                    assertThat(index.keyParts().get(1).expression()).isEqualTo("lower(email)");
                    assertThat(index.keyParts().get(1).ascending()).isFalse();
                    assertThat(index.includedColumns()).containsExactly("payload");
                    assertThat(index.comparisonSemantics()).containsExactly("0:0:1978", "1:100:3126");
                });
        row.remove("is_ready");
        VendorFindings unknownState =
                postgres(18, new ArrayList<>(), sql -> sql.contains("as key_columns") ? List.of(row) : List.of());
        assertThat(unknownState.findings(VendorFindingKinds.POSTGRES_INDEX_DETAILS))
                .singleElement()
                .satisfies(index -> {
                    assertThat(index.definitionComplete()).isFalse();
                    assertThat(index.ready()).isNull();
                    assertThat(index.keyParts().get(0).columnName()).isEqualTo("tenant");
                });
    }

    @Test
    void postgresCatalogColumnsUseServerVersionGates() throws Exception {
        List<String> queries = new ArrayList<>();
        postgres(10, queries, sql -> List.of());
        assertThat(String.join("\n", queries))
                .contains("i.indnatts as key_column_count")
                .doesNotContain(
                        "i.indnkeyatts", "pg_stat_progress_create_index", "i.indnullsnotdistinct", "c.conenforced");
        queries.clear();
        postgres(18, queries, sql -> List.of());
        assertThat(String.join("\n", queries))
                .contains("i.indnkeyatts", "pg_stat_progress_create_index", "i.indnullsnotdistinct", "c.conenforced");
    }

    @Test
    void mysqlAndMariaDbKeepDistinctVisibilityAndExpressionGates() throws Exception {
        List<String> mysql = mysql(Dialect.MYSQL, new DatabaseVersion(8, 0, 13, "8.0.13"));
        assertThat(String.join("\n", mysql))
                .contains("s.is_visible", "s.expression", "c.extra as column_extra")
                .doesNotContain("s.ignored");
        List<String> maria = mysql(Dialect.MARIADB, new DatabaseVersion(10, 6, 0, "10.6.0"));
        assertThat(String.join("\n", maria)).contains("s.ignored").doesNotContain("s.is_visible", "s.expression");
        List<String> old = mysql(Dialect.MARIADB, new DatabaseVersion(10, 5, 0, "10.5.0"));
        assertThat(String.join("\n", old)).doesNotContain("s.ignored", "s.is_visible", "s.expression");
    }

    @Test
    void mysqlUnknownUniquenessAndVisibilityStayUnknownAndGeneratedKeysRemainDistinct() throws Exception {
        Map<String, Object> row = Map.of(
                "table_schema",
                "app",
                "table_name",
                "t",
                "index_name",
                "idx",
                "seq_in_index",
                1,
                "column_name",
                "generated_id",
                "column_extra",
                "STORED GENERATED",
                "is_visible",
                "UNRECOGNIZED");
        Connection connection = connection(
                new ArrayList<>(),
                new ArrayList<>(),
                sql -> sql.contains("from information_schema.statistics") ? List.of(row) : List.of());
        DatabaseVersion version = new DatabaseVersion(8, 0, 13, "8.0.13");
        VendorFindings.Builder findings = VendorFindings.builder();
        MySqlCatalogReader.read(
                connection,
                Dialect.MYSQL,
                DialectCapabilities.of(Dialect.MYSQL, version),
                budget(),
                DatabaseAdvisorLimits.DEFAULTS,
                findings);
        assertThat(findings.build().findings(VendorFindingKinds.MYSQL_INDEX_DETAILS))
                .singleElement()
                .satisfies(index -> {
                    assertThat(index.visible()).isNull();
                    assertThat(index.generatedColumn()).isTrue();
                    assertThat(index.definitionComplete()).isFalse();
                });
    }

    @Test
    void mysqlContradictoryUniquenessWithinAnIndexMakesTheWholeDefinitionUnknown() throws Exception {
        List<Map<String, Object>> rows = List.of(
                Map.of(
                        "table_schema",
                        "app",
                        "table_name",
                        "t",
                        "index_name",
                        "idx",
                        "seq_in_index",
                        1,
                        "column_name",
                        "a",
                        "non_unique",
                        0,
                        "is_visible",
                        "YES",
                        "index_type",
                        "BTREE"),
                Map.of(
                        "table_schema",
                        "app",
                        "table_name",
                        "t",
                        "index_name",
                        "idx",
                        "seq_in_index",
                        2,
                        "column_name",
                        "b",
                        "non_unique",
                        1,
                        "is_visible",
                        "YES",
                        "index_type",
                        "BTREE"));
        Connection connection = connection(
                new ArrayList<>(),
                new ArrayList<>(),
                sql -> sql.contains("from information_schema.statistics") ? rows : List.of());
        DatabaseVersion version = new DatabaseVersion(8, 0, 13, "8.0.13");
        VendorFindings.Builder findings = VendorFindings.builder();
        MySqlCatalogReader.read(
                connection,
                Dialect.MYSQL,
                DialectCapabilities.of(Dialect.MYSQL, version),
                budget(),
                DatabaseAdvisorLimits.DEFAULTS,
                findings);
        assertThat(findings.build().findings(VendorFindingKinds.MYSQL_INDEX_DETAILS))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.definitionComplete()).isFalse());
    }

    @Test
    void postgresSqlNullIndexFlagsAreNotConvertedToExplicitInvalidState() throws Exception {
        VendorFindings findings = postgres(
                18,
                new ArrayList<>(),
                sql -> sql.contains("where (i.indisvalid")
                        ? List.of(Map.of("schema_name", "public", "table_name", "t", "index_name", "idx"))
                        : List.of());
        assertThat(findings.findings(VendorFindingKinds.POSTGRES_INVALID_INDEXES))
                .singleElement()
                .satisfies(index -> assertThat(index.explicitlyInvalid()).isFalse());
    }

    @Test
    void oracleUsesExactUnusableStatesQualifiedBackingAndIdentityPrecision() throws Exception {
        List<String> queries = new ArrayList<>();
        List<PreparedStatement> statements = new ArrayList<>();
        Connection connection = connection(
                queries,
                statements,
                sql -> sql.contains("from all_tab_identity_cols")
                        ? List.of(Map.of(
                                "schema_name",
                                "APP",
                                "table_name",
                                "T",
                                "column_name",
                                "ID",
                                "sequence_name",
                                "S",
                                "data_type",
                                "NUMBER",
                                "data_precision",
                                5,
                                "data_scale",
                                0))
                        : List.of());
        VendorFindings.Builder findings = VendorFindings.builder();
        DatabaseVersion version = new DatabaseVersion(19, 0, 0, "19");
        OracleCatalogReader.read(
                connection,
                "APP",
                version,
                DialectCapabilities.of(Dialect.ORACLE, version),
                budget(),
                DatabaseAdvisorLimits.DEFAULTS,
                findings);
        String sql = String.join("\n", queries);
        assertThat(sql)
                .contains(
                        "p.status = 'UNUSABLE'",
                        "sp.status = 'UNUSABLE'",
                        "i.index_type <> 'DOMAIN'",
                        "c.index_owner",
                        "c.index_name",
                        "i.table_owner = ?",
                        "c.data_precision",
                        "c.data_scale");
        assertThat(sql).doesNotContain("status <> 'USABLE'", "c.status <> 'ENABLED'", "dba_", "nextval");
        for (PreparedStatement statement : statements) {
            verify(statement).setObject(1, "APP");
            verify(statement).setMaxRows(DatabaseAdvisorLimits.DEFAULTS.maxVendorFindings() + 1);
        }
        assertThat(findings.build().findings(VendorFindingKinds.ORACLE_IDENTITY_COLUMNS))
                .singleElement()
                .satisfies(identity -> assertThat(identity.capacity()).isEqualTo(BigInteger.valueOf(99999)));
    }

    private static VendorFindings postgres(
            int major, List<String> queries, Function<String, List<Map<String, Object>>> rows) throws Exception {
        Connection connection = connection(queries, new ArrayList<>(), rows);
        DatabaseVersion version = new DatabaseVersion(major, 0, 0, Integer.toString(major));
        VendorFindings.Builder findings = VendorFindings.builder();
        PostgresCatalogReader.read(
                connection,
                version,
                DialectCapabilities.of(Dialect.POSTGRESQL, version),
                budget(),
                DatabaseAdvisorLimits.DEFAULTS,
                findings);
        return findings.build();
    }

    private static List<String> mysql(Dialect dialect, DatabaseVersion version) throws Exception {
        List<String> queries = new ArrayList<>();
        Connection connection = connection(queries, new ArrayList<>(), sql -> List.of());
        MySqlCatalogReader.read(
                connection,
                dialect,
                DialectCapabilities.of(dialect, version),
                budget(),
                DatabaseAdvisorLimits.DEFAULTS,
                VendorFindings.builder());
        return queries;
    }

    private static ScanBudget budget() {
        return ScanBudget.of(Duration.ofSeconds(30));
    }

    private static Connection connection(
            List<String> queries, List<PreparedStatement> statements, Function<String, List<Map<String, Object>>> rows)
            throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            assertThat(sql.stripLeading()).startsWith("select ");
            assertThat(sql).contains("?");
            queries.add(sql);
            PreparedStatement statement = mock(PreparedStatement.class);
            statements.add(statement);
            when(statement.executeQuery()).thenReturn(resultSet(rows.apply(sql)));
            return statement;
        });
        return connection;
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        AtomicInteger position = new AtomicInteger(-1);
        AtomicBoolean wasNull = new AtomicBoolean();
        return mock(ResultSet.class, call -> {
            String method = call.getMethod().getName();
            if ("next".equals(method)) {
                return position.incrementAndGet() < rows.size();
            }
            if ("wasNull".equals(method)) {
                return wasNull.get();
            }
            if (method.startsWith("get") && call.getArguments().length == 1) {
                Object value = rows.get(position.get()).get(call.getArgument(0));
                wasNull.set(value == null);
                return switch (method) {
                    case "getString" -> value == null ? null : value.toString();
                    case "getBoolean" -> Boolean.TRUE.equals(value);
                    case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                    case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                    case "getBigDecimal" -> value;
                    case "getArray" -> {
                        if (value == null) {
                            yield null;
                        }
                        Array array = mock(Array.class);
                        when(array.getArray()).thenReturn(value);
                        yield array;
                    }
                    default -> null;
                };
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
    }
}
