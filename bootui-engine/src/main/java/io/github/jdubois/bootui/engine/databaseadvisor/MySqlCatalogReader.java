package io.github.jdubois.bootui.engine.databaseadvisor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The MySQL/MariaDB-only, read-only {@code information_schema} augmentation the generic JDBC metadata API
 * cannot answer: storage engines, table/column character sets, {@code AUTO_INCREMENT} counters and the
 * signedness of the columns they feed, and index semantics (prefix length, access method, functional key
 * parts, and whether the optimizer may use the index at all).
 *
 * <p>MariaDB is handled explicitly rather than as "MySQL with a different name": index visibility is
 * {@code IS_VISIBLE} on MySQL 8.0 but {@code IGNORED} on MariaDB 10.6, and MariaDB has no {@code EXPRESSION}
 * column at all. Selecting a column that does not exist fails the whole statement, so the SQL is assembled
 * from {@link DialectCapabilities} instead of being written once and hoped for.</p>
 */
final class MySqlCatalogReader {

    private static final String TABLES_SQL = """
            select t.table_schema, t.table_name, t.engine, t.table_collation, t.auto_increment
            from information_schema.tables t
            where t.table_schema = database()
              and t.table_type = 'BASE TABLE'
            order by t.table_name
            limit ?
            """;

    private static final String COLUMN_CHARSETS_SQL = """
            select c.table_schema, c.table_name, c.column_name, c.character_set_name, c.collation_name
            from information_schema.columns c
            where c.table_schema = database()
              and c.character_set_name is not null
            order by c.table_name, c.ordinal_position
            limit ?
            """;

    private static final String AUTO_INCREMENT_COLUMNS_SQL = """
            select c.table_schema, c.table_name, c.column_name, c.data_type, c.column_type
            from information_schema.columns c
            where c.table_schema = database()
              and c.extra like '%auto_increment%'
            order by c.table_name
            limit ?
            """;

    private MySqlCatalogReader() {}

    static void read(
            Connection connection,
            Dialect dialect,
            DialectCapabilities capabilities,
            ScanBudget budget,
            DatabaseAdvisorLimits limits,
            VendorFindings.Builder findings) {
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.MYSQL_TABLES,
                TABLES_SQL,
                budget,
                limits,
                MySqlCatalogReader::readTable));
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.MYSQL_COLUMN_CHARSETS,
                COLUMN_CHARSETS_SQL,
                budget,
                limits,
                MySqlCatalogReader::readColumnCharset));
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS,
                AUTO_INCREMENT_COLUMNS_SQL,
                budget,
                limits,
                MySqlCatalogReader::readAutoIncrementColumn));
        findings.add(consistentIndexRows(CatalogQuery.read(
                connection,
                VendorFindingKinds.MYSQL_INDEX_DETAILS,
                indexDetailsSql(dialect, capabilities),
                budget,
                limits,
                resultSet -> readIndexDetail(resultSet, dialect, capabilities))));
    }

    private static VendorAugmentation<MySqlIndexDetail> consistentIndexRows(
            VendorAugmentation<MySqlIndexDetail> augmentation) {
        if (!augmentation.available()) {
            return augmentation;
        }
        record Identity(String schema, String table, String index) {}
        Map<Identity, List<MySqlIndexDetail>> groups = new LinkedHashMap<>();
        for (MySqlIndexDetail row : augmentation.findings()) {
            groups.computeIfAbsent(new Identity(row.schema(), row.table(), row.index()), ignored -> new ArrayList<>())
                    .add(row);
        }
        List<MySqlIndexDetail> rows = new ArrayList<>();
        int groupNumber = 0;
        for (List<MySqlIndexDetail> group : groups.values()) {
            MySqlIndexDetail first = group.get(0);
            groupNumber++;
            boolean complete = !(augmentation.truncated() && groupNumber == groups.size());
            int expected = 1;
            for (MySqlIndexDetail row : group) {
                complete &= row.definitionComplete()
                        && row.position() == expected++
                        && row.unique() == first.unique()
                        && Objects.equals(row.visible(), first.visible())
                        && Objects.equals(row.indexType(), first.indexType());
            }
            for (MySqlIndexDetail row : group) {
                rows.add(new MySqlIndexDetail(
                        row.schema(),
                        row.table(),
                        row.index(),
                        row.position(),
                        row.column(),
                        row.subPart(),
                        row.collation(),
                        row.indexType(),
                        row.unique(),
                        row.visible(),
                        row.expression(),
                        row.generatedColumn(),
                        complete));
            }
        }
        return VendorAugmentation.available(VendorFindingKinds.MYSQL_INDEX_DETAILS, rows, augmentation.truncated());
    }

    /** Selects only the catalog columns this server version actually has. */
    private static String indexDetailsSql(Dialect dialect, DialectCapabilities capabilities) {
        StringBuilder sql = new StringBuilder("""
                select s.table_schema, s.table_name, s.index_name, s.seq_in_index, s.column_name,
                       s.sub_part, s.collation, s.index_type, s.non_unique, c.extra as column_extra""");
        if (capabilities.indexVisibility()) {
            sql.append(dialect == Dialect.MARIADB ? ", s.ignored as index_ignored" : ", s.is_visible as is_visible");
        }
        if (capabilities.indexExpression()) {
            sql.append(", s.expression as key_expression");
        }
        sql.append("""

                from information_schema.statistics s
                left join information_schema.columns c on c.table_schema = s.table_schema
                     and c.table_name = s.table_name and c.column_name = s.column_name
                where s.table_schema = database()
                order by s.table_name, s.index_name, s.seq_in_index
                limit ?
                """);
        return sql.toString();
    }

    private static MySqlTableInfo readTable(ResultSet rs) throws SQLException {
        BigDecimal autoIncrement = rs.getBigDecimal("auto_increment");
        return new MySqlTableInfo(
                rs.getString("table_schema"),
                rs.getString("table_name"),
                rs.getString("engine"),
                rs.getString("table_collation"),
                autoIncrement == null ? null : autoIncrement.toBigInteger());
    }

    private static MySqlColumnCharset readColumnCharset(ResultSet rs) throws SQLException {
        return new MySqlColumnCharset(
                rs.getString("table_schema"),
                rs.getString("table_name"),
                rs.getString("column_name"),
                rs.getString("character_set_name"),
                rs.getString("collation_name"));
    }

    private static MySqlAutoIncrementColumn readAutoIncrementColumn(ResultSet rs) throws SQLException {
        return new MySqlAutoIncrementColumn(
                rs.getString("table_schema"),
                rs.getString("table_name"),
                rs.getString("column_name"),
                rs.getString("data_type"),
                rs.getString("column_type"));
    }

    private static MySqlIndexDetail readIndexDetail(ResultSet rs, Dialect dialect, DialectCapabilities capabilities)
            throws SQLException {
        Boolean visible = null;
        if (capabilities.indexVisibility()) {
            if (dialect == Dialect.MARIADB) {
                String ignored = rs.getString("index_ignored");
                visible = "YES".equalsIgnoreCase(ignored)
                        ? Boolean.FALSE
                        : "NO".equalsIgnoreCase(ignored) ? Boolean.TRUE : null;
            } else {
                String isVisible = rs.getString("is_visible");
                visible = "YES".equalsIgnoreCase(isVisible)
                        ? Boolean.TRUE
                        : "NO".equalsIgnoreCase(isVisible) ? Boolean.FALSE : null;
            }
        }
        Integer subPart = rs.getInt("sub_part");
        if (rs.wasNull()) {
            subPart = null;
        }
        int position = rs.getInt("seq_in_index");
        boolean knownPosition = !rs.wasNull() && position > 0;
        int nonUnique = rs.getInt("non_unique");
        boolean knownUniqueness = !rs.wasNull() && (nonUnique == 0 || nonUnique == 1);
        String columnExtra = rs.getString("column_extra");
        boolean generated = columnExtra != null
                && columnExtra.toUpperCase(java.util.Locale.ROOT).contains("GENERATED");
        return new MySqlIndexDetail(
                rs.getString("table_schema"),
                rs.getString("table_name"),
                rs.getString("index_name"),
                position,
                rs.getString("column_name"),
                subPart,
                rs.getString("collation"),
                rs.getString("index_type"),
                knownUniqueness && nonUnique == 0,
                visible,
                capabilities.indexExpression() ? rs.getString("key_expression") : null,
                generated,
                knownPosition && knownUniqueness);
    }

    /** The {@code AUTO_INCREMENT} counter for one table, or {@code null} when the server did not report it. */
    static BigInteger nextAutoIncrement(SchemaSnapshot schema, String tableSchema, String tableName) {
        for (MySqlTableInfo table : schema.vendorFindings().findings(VendorFindingKinds.MYSQL_TABLES)) {
            if (matches(table.schema(), tableSchema) && matches(table.table(), tableName)) {
                return table.nextAutoIncrement();
            }
        }
        return null;
    }

    private static boolean matches(String left, String right) {
        return java.util.Objects.equals(left, right);
    }
}
