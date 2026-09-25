package io.github.jdubois.bootui.engine.databaseadvisor;

import java.sql.Types;
import java.util.Locale;
import java.util.Set;

/**
 * A coarse type family used to compare a physical column against another physical column or against a mapped
 * Java attribute type, without requiring an exact dialect-specific type match.
 *
 * <p>Classification is driven by the JDBC {@code DATA_TYPE} code first and only falls back to the reported
 * type name — matched as a whole normalized token, never as a substring. The substring approach the previous
 * implementation used classified PostgreSQL's {@code interval} as numeric (it contains "int") and
 * {@code point} as numeric too, producing foreign-key type mismatches that did not exist.</p>
 *
 * <p>{@link #OTHER} never participates in a mismatch finding, which keeps every rule built on this
 * conservative: an unclassifiable type is a reason to stay quiet, not to guess.</p>
 */
enum JdbcTypeFamily {
    STRING,
    NUMERIC,
    BOOLEAN,
    DATE_TIME,
    BINARY,
    UUID,
    OTHER;

    private static final Set<String> STRING_TYPE_NAMES = Set.of(
            "char",
            "character",
            "bpchar",
            "varchar",
            "varchar2",
            "character varying",
            "nchar",
            "nvarchar",
            "nvarchar2",
            "national character varying",
            "text",
            "citext",
            "tinytext",
            "mediumtext",
            "longtext",
            "clob",
            "nclob",
            "name",
            "enum",
            "json",
            "jsonb");
    private static final Set<String> NUMERIC_TYPE_NAMES = Set.of(
            "tinyint",
            "smallint",
            "mediumint",
            "int",
            "int2",
            "int4",
            "int8",
            "integer",
            "bigint",
            "serial",
            "serial2",
            "serial4",
            "serial8",
            "smallserial",
            "bigserial",
            "decimal",
            "dec",
            "numeric",
            "number",
            "float",
            "float4",
            "float8",
            "double",
            "double precision",
            "real",
            "money",
            "smallmoney");
    private static final Set<String> BOOLEAN_TYPE_NAMES = Set.of("bool", "boolean");
    private static final Set<String> DATE_TIME_TYPE_NAMES = Set.of(
            "date",
            "time",
            "timetz",
            "time with time zone",
            "time without time zone",
            "timestamp",
            "timestamptz",
            "timestamp with time zone",
            "timestamp without time zone",
            "datetime",
            "datetime2",
            "smalldatetime",
            "datetimeoffset",
            "year");
    private static final Set<String> BINARY_TYPE_NAMES = Set.of(
            "binary",
            "varbinary",
            "bytea",
            "blob",
            "tinyblob",
            "mediumblob",
            "longblob",
            "image",
            "raw",
            "bit varying");
    private static final Set<String> UUID_TYPE_NAMES = Set.of("uuid", "uniqueidentifier");

    /** Classifies a physical column from its JDBC type code, falling back to the reported type name. */
    static JdbcTypeFamily of(ColumnModel column) {
        if (column == null) {
            return OTHER;
        }
        JdbcTypeFamily byCode = ofJdbcTypeCode(column.jdbcType());
        return byCode != OTHER ? byCode : ofTypeName(column.typeName());
    }

    /** Classifies a {@link java.sql.Types} constant. */
    static JdbcTypeFamily ofJdbcTypeCode(int jdbcType) {
        return switch (jdbcType) {
            case Types.CHAR,
                    Types.VARCHAR,
                    Types.LONGVARCHAR,
                    Types.NCHAR,
                    Types.NVARCHAR,
                    Types.LONGNVARCHAR,
                    Types.CLOB,
                    Types.NCLOB -> STRING;
            case Types.TINYINT,
                    Types.SMALLINT,
                    Types.INTEGER,
                    Types.BIGINT,
                    Types.DECIMAL,
                    Types.NUMERIC,
                    Types.FLOAT,
                    Types.REAL,
                    Types.DOUBLE -> NUMERIC;
            case Types.BOOLEAN, Types.BIT -> BOOLEAN;
            case Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
                DATE_TIME;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> BINARY;
            default -> OTHER;
        };
    }

    /** Classifies a JDBC-reported type name (e.g. {@code varchar}, {@code int4}, {@code interval}). */
    static JdbcTypeFamily ofTypeName(String typeName) {
        String normalized = normalize(typeName);
        if (normalized.isEmpty()) {
            return OTHER;
        }
        if (UUID_TYPE_NAMES.contains(normalized)) {
            return UUID;
        }
        if (BOOLEAN_TYPE_NAMES.contains(normalized)) {
            return BOOLEAN;
        }
        if (DATE_TIME_TYPE_NAMES.contains(normalized)) {
            return DATE_TIME;
        }
        if (BINARY_TYPE_NAMES.contains(normalized)) {
            return BINARY;
        }
        if (STRING_TYPE_NAMES.contains(normalized)) {
            return STRING;
        }
        if (NUMERIC_TYPE_NAMES.contains(normalized)) {
            return NUMERIC;
        }
        return OTHER;
    }

    /**
     * Lowercases, drops any declared precision/length and MySQL's {@code unsigned}/{@code zerofill} modifiers,
     * and collapses whitespace, so {@code "VARCHAR(255)"}, {@code "int(10) unsigned"} and
     * {@code "TIMESTAMP WITH TIME ZONE"} all reduce to a single comparable token.
     */
    private static String normalize(String typeName) {
        if (typeName == null) {
            return "";
        }
        String normalized = typeName.toLowerCase(Locale.ROOT).trim();
        int parenthesis = normalized.indexOf('(');
        if (parenthesis >= 0) {
            String tail = normalized.substring(normalized.indexOf(')') + 1);
            normalized = (normalized.substring(0, parenthesis) + " " + tail).trim();
        }
        normalized = normalized.replace("unsigned", " ").replace("zerofill", " ");
        normalized = normalized.replaceAll("\\[]", "").replaceAll("\\s+", " ").trim();
        return normalized;
    }
}
