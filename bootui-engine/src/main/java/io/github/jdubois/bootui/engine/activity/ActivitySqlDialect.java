package io.github.jdubois.bootui.engine.activity;

import java.util.Locale;

/**
 * The two places where {@link JdbcActivityStore}'s SQL has to differ between databases, chosen once per store from
 * the JDBC driver's {@code DatabaseMetaData.getDatabaseProductName()}. Everything else the store issues (DDL shape,
 * inserts, keyset predicates, deletes) is plain SQL every supported database accepts.
 *
 * <ul>
 *   <li>The 64-bit integer column type: Oracle has no {@code BIGINT} (it fails with ORA-00902), so it gets
 *       {@code NUMBER(19)}.</li>
 *   <li>The row-limit clause: MySQL has no SQL-standard {@code OFFSET ... FETCH FIRST} form in any version, and
 *       MariaDB only since 10.6, so both get {@code LIMIT}.</li>
 * </ul>
 */
enum ActivitySqlDialect {

    /**
     * MySQL and MariaDB. MariaDB's own driver reports {@code "MariaDB"}; MySQL Connector/J reports {@code "MySQL"}
     * for both, and so do MySQL-compatible servers such as TiDB.
     */
    MYSQL_FAMILY("BIGINT", " LIMIT ?"),

    /** Oracle Database 12c or later. */
    ORACLE("NUMBER(19)", " OFFSET 0 ROWS FETCH FIRST ? ROWS ONLY"),

    /** Every other database, including H2, PostgreSQL and SQL Server: the SQL-standard forms. */
    STANDARD("BIGINT", " OFFSET 0 ROWS FETCH FIRST ? ROWS ONLY");

    private final String bigIntType;
    private final String rowLimitClause;

    ActivitySqlDialect(String bigIntType, String rowLimitClause) {
        this.bigIntType = bigIntType;
        this.rowLimitClause = rowLimitClause;
    }

    /** The column type for a signed 64-bit integer. */
    String bigIntType() {
        return bigIntType;
    }

    /**
     * The clause appended after {@code ORDER BY} to cap the rows returned, with a single {@code ?} parameter for the
     * row count.
     */
    String rowLimitClause() {
        return rowLimitClause;
    }

    static ActivitySqlDialect detect(String productName) {
        String product = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (product.contains("mysql") || product.contains("mariadb")) {
            return MYSQL_FAMILY;
        }
        if (product.contains("oracle")) {
            return ORACLE;
        }
        return STANDARD;
    }
}
