package io.github.jdubois.bootui.engine.databaseadvisor;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Reads the physical schema of one {@code DataSource} through plain {@code java.sql.DatabaseMetaData} —
 * tables, columns, primary keys, foreign keys, and indexes — and, for {@link Dialect#POSTGRESQL} and
 * {@link Dialect#MYSQL}, a small amount of additional read-only catalog augmentation where the generic
 * JDBC metadata API has no equivalent.
 *
 * <p>This is purely read-only: it never executes DDL and never queries application data, only driver
 * catalog metadata and (for the two dialect augmentations) {@code information_schema}/{@code pg_catalog}
 * rows. The scan is bounded ({@link #MAX_TABLES} tables, {@link #MAX_COLUMNS_PER_TABLE} columns per
 * table) so a very large schema cannot make the on-demand scan run unbounded work, mirroring every other
 * BootUI advisor's bounded-scan convention. A failure introspecting one datasource is caught and reported
 * as a {@link SchemaSnapshot#failed(String, String)} rather than propagating, so one bad datasource never
 * fails the whole report.</p>
 */
final class SchemaIntrospector {

    static final int MAX_TABLES = 300;
    static final int MAX_COLUMNS_PER_TABLE = 300;
    static final int MAX_INDEXES_PER_TABLE = 100;
    static final int MAX_DIALECT_FINDINGS = 200;

    private static final Set<String> SYSTEM_SCHEMAS =
            Set.of("information_schema", "pg_catalog", "pg_toast", "mysql", "performance_schema", "sys", "sys_config");

    private SchemaIntrospector() {}

    static SchemaSnapshot introspect(String dataSourceName, DataSource dataSource) {
        if (dataSource == null) {
            return SchemaSnapshot.failed(dataSourceName, "DataSource bean is not available.");
        }
        try (Connection connection = dataSource.getConnection()) {
            return introspect(dataSourceName, connection);
        } catch (RuntimeException | SQLException ex) {
            return SchemaSnapshot.failed(dataSourceName, ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }

    private static SchemaSnapshot introspect(String dataSourceName, Connection connection) throws SQLException {
        trySetReadOnly(connection);
        DatabaseMetaData metaData = connection.getMetaData();
        String productName = safeProductName(metaData);
        String url = safeUrl(metaData);
        Dialect dialect = Dialect.detect(productName, url);

        List<TableModel> tables = readTables(connection, metaData);
        List<PostgresInvalidIndex> postgresInvalidIndexes =
                dialect == Dialect.POSTGRESQL ? readPostgresInvalidIndexes(connection) : List.of();
        List<MySqlNonInnodbTable> mysqlNonInnodbTables =
                dialect == Dialect.MYSQL ? readMySqlNonInnodbTables(connection) : List.of();
        List<MySqlNonUtf8mb4Column> mysqlNonUtf8mb4Columns =
                dialect == Dialect.MYSQL ? readMySqlNonUtf8mb4Columns(connection) : List.of();
        List<PostgresSequenceNearingExhaustion> postgresSequencesNearingExhaustion =
                dialect == Dialect.POSTGRESQL ? readPostgresSequencesNearingExhaustion(connection) : List.of();

        return new SchemaSnapshot(
                dataSourceName,
                dialect,
                productName,
                tables,
                postgresInvalidIndexes,
                mysqlNonInnodbTables,
                mysqlNonUtf8mb4Columns,
                postgresSequencesNearingExhaustion,
                null);
    }

    private static List<TableModel> readTables(Connection connection, DatabaseMetaData metaData) throws SQLException {
        List<TableModel> tables = new ArrayList<>();
        String catalog = safeCatalog(connection);
        try (ResultSet rs = metaData.getTables(catalog, null, "%", new String[] {"TABLE"})) {
            while (rs.next() && tables.size() < MAX_TABLES) {
                String tableCatalog = rs.getString("TABLE_CAT");
                String tableSchema = rs.getString("TABLE_SCHEM");
                String tableName = rs.getString("TABLE_NAME");
                if (isSystemSchema(tableSchema) || tableName == null) {
                    continue;
                }
                tables.add(readTable(metaData, tableCatalog, tableSchema, tableName));
            }
        }
        return tables;
    }

    private static TableModel readTable(DatabaseMetaData metaData, String catalog, String schema, String tableName)
            throws SQLException {
        List<ColumnModel> columns = readColumns(metaData, catalog, schema, tableName);
        List<String> primaryKey = readPrimaryKey(metaData, catalog, schema, tableName);
        List<ForeignKeyModel> foreignKeys = readForeignKeys(metaData, catalog, schema, tableName);
        List<IndexModel> indexes = readIndexes(metaData, catalog, schema, tableName);
        return new TableModel(catalog, schema, tableName, columns, primaryKey, foreignKeys, indexes);
    }

    private static List<ColumnModel> readColumns(DatabaseMetaData metaData, String catalog, String schema, String table)
            throws SQLException {
        List<ColumnModel> columns = new ArrayList<>();
        try (ResultSet rs = metaData.getColumns(catalog, schema, table, "%")) {
            while (rs.next() && columns.size() < MAX_COLUMNS_PER_TABLE) {
                String name = rs.getString("COLUMN_NAME");
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                boolean nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                columns.add(new ColumnModel(name, typeName, nullable, size));
            }
        }
        return columns;
    }

    private static List<String> readPrimaryKey(DatabaseMetaData metaData, String catalog, String schema, String table)
            throws SQLException {
        Map<Short, String> byPosition = new LinkedHashMap<>();
        try (ResultSet rs = metaData.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                byPosition.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return byPosition.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    static List<ForeignKeyModel> readForeignKeys(DatabaseMetaData metaData, String catalog, String schema, String table)
            throws SQLException {
        Map<String, List<String>> columnsByFkName = new LinkedHashMap<>();
        Map<String, String> referencedTableByFkName = new LinkedHashMap<>();
        // JDBC guarantees getImportedKeys() rows are ordered by FKTABLE_CAT/SCHEM/NAME, KEY_SEQ, so all
        // columns belonging to the same constraint are contiguous with an increasing KEY_SEQ starting at
        // 1. Unnamed constraints (FK_NAME null) therefore only need a new synthetic key when KEY_SEQ
        // restarts at 1 — otherwise a composite unnamed foreign key would be split into one fake
        // single-column constraint per row.
        String currentUnnamedKey = null;
        int unnamedCount = 0;
        try (ResultSet rs = metaData.getImportedKeys(catalog, schema, table)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                short keySeq = rs.getShort("KEY_SEQ");
                String key;
                if (fkName != null) {
                    key = fkName;
                } else {
                    if (currentUnnamedKey == null || keySeq <= 1) {
                        key = "fk#" + unnamedCount++;
                        currentUnnamedKey = key;
                    } else {
                        key = currentUnnamedKey;
                    }
                }
                columnsByFkName
                        .computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(rs.getString("FKCOLUMN_NAME"));
                referencedTableByFkName.putIfAbsent(key, rs.getString("PKTABLE_NAME"));
            }
        }
        List<ForeignKeyModel> foreignKeys = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : columnsByFkName.entrySet()) {
            foreignKeys.add(
                    new ForeignKeyModel(entry.getKey(), entry.getValue(), referencedTableByFkName.get(entry.getKey())));
        }
        return foreignKeys;
    }

    private static List<IndexModel> readIndexes(DatabaseMetaData metaData, String catalog, String schema, String table)
            throws SQLException {
        Map<String, List<String>> columnsByIndexName = new LinkedHashMap<>();
        Map<String, Boolean> uniqueByIndexName = new LinkedHashMap<>();
        try (ResultSet rs = metaData.getIndexInfo(catalog, schema, table, false, true)) {
            while (rs.next() && columnsByIndexName.size() < MAX_INDEXES_PER_TABLE) {
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                if (indexName == null || columnName == null) {
                    // tableIndexStatistic rows carry no column; skip them.
                    continue;
                }
                columnsByIndexName
                        .computeIfAbsent(indexName, ignored -> new ArrayList<>())
                        .add(columnName);
                uniqueByIndexName.putIfAbsent(indexName, !rs.getBoolean("NON_UNIQUE"));
            }
        }
        List<IndexModel> indexes = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : columnsByIndexName.entrySet()) {
            indexes.add(new IndexModel(
                    entry.getKey(), entry.getValue(), uniqueByIndexName.getOrDefault(entry.getKey(), false)));
        }
        return indexes;
    }

    private static List<PostgresInvalidIndex> readPostgresInvalidIndexes(Connection connection) {
        String sql = """
                select t.relname as table_name, c.relname as index_name
                from pg_index i
                join pg_class c on c.oid = i.indexrelid
                join pg_class t on t.oid = i.indrelid
                join pg_namespace n on n.oid = t.relnamespace
                where i.indisvalid = false
                  and n.nspname not in ('pg_catalog', 'information_schema', 'pg_toast')
                limit ?
                """;
        List<PostgresInvalidIndex> findings = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, MAX_DIALECT_FINDINGS);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    findings.add(new PostgresInvalidIndex(rs.getString("table_name"), rs.getString("index_name")));
                }
            }
        } catch (SQLException ex) {
            // The catalog augmentation is best-effort: an unreadable pg_index (e.g. insufficient
            // privileges) simply yields no dialect-specific findings, the generic scan still applies.
            return List.of();
        }
        return findings;
    }

    private static List<PostgresSequenceNearingExhaustion> readPostgresSequencesNearingExhaustion(
            Connection connection) {
        String sql = """
                select schemaname, sequencename, last_value, max_value
                from pg_sequences
                where last_value is not null
                  and max_value > 0
                  and schemaname not in ('pg_catalog', 'information_schema', 'pg_toast')
                  and (last_value::numeric / max_value::numeric) * 100 >= ?
                limit ?
                """;
        List<PostgresSequenceNearingExhaustion> findings = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, PostgresSequenceExhaustionRule.WARNING_PERCENT_USED);
            statement.setInt(2, MAX_DIALECT_FINDINGS);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    long lastValue = rs.getLong("last_value");
                    long maxValue = rs.getLong("max_value");
                    int percentUsed = maxValue <= 0 ? 0 : (int) ((lastValue * 100L) / maxValue);
                    findings.add(new PostgresSequenceNearingExhaustion(
                            rs.getString("sequencename"), lastValue, maxValue, percentUsed));
                }
            }
        } catch (SQLException ex) {
            // The catalog augmentation is best-effort: an unreadable pg_sequences (e.g. insufficient
            // privileges) simply yields no dialect-specific findings, the generic scan still applies.
            return List.of();
        }
        return findings;
    }

    private static List<MySqlNonInnodbTable> readMySqlNonInnodbTables(Connection connection) {
        String sql = """
                select table_name, engine
                from information_schema.tables
                where table_schema = database()
                  and engine is not null
                  and upper(engine) <> 'INNODB'
                limit ?
                """;
        List<MySqlNonInnodbTable> findings = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, MAX_DIALECT_FINDINGS);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    findings.add(new MySqlNonInnodbTable(rs.getString("table_name"), rs.getString("engine")));
                }
            }
        } catch (SQLException ex) {
            // Best-effort augmentation; fall back to no dialect-specific findings.
            return List.of();
        }
        return findings;
    }

    private static List<MySqlNonUtf8mb4Column> readMySqlNonUtf8mb4Columns(Connection connection) {
        String sql = """
                select table_name, column_name, character_set_name
                from information_schema.columns
                where table_schema = database()
                  and character_set_name is not null
                  and upper(character_set_name) <> 'UTF8MB4'
                limit ?
                """;
        List<MySqlNonUtf8mb4Column> findings = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, MAX_DIALECT_FINDINGS);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    findings.add(new MySqlNonUtf8mb4Column(
                            rs.getString("table_name"),
                            rs.getString("column_name"),
                            rs.getString("character_set_name")));
                }
            }
        } catch (SQLException ex) {
            // Best-effort augmentation; fall back to no dialect-specific findings.
            return List.of();
        }
        return findings;
    }

    private static boolean isSystemSchema(String schema) {
        return schema != null && SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT));
    }

    private static void trySetReadOnly(Connection connection) {
        try {
            connection.setReadOnly(true);
        } catch (SQLException ex) {
            // Not every driver supports read-only mode; the scanner never issues a write regardless.
        }
    }

    private static String safeProductName(DatabaseMetaData metaData) {
        try {
            return metaData.getDatabaseProductName();
        } catch (SQLException ex) {
            return null;
        }
    }

    private static String safeUrl(DatabaseMetaData metaData) {
        try {
            return metaData.getURL();
        } catch (SQLException ex) {
            return null;
        }
    }

    private static String safeCatalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException ex) {
            return null;
        }
    }
}
