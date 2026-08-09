package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.Locale;
import java.util.Set;

/**
 * A coarse type family used to detect type mismatches across a JDBC-reported type name and/or a mapped
 * Java attribute type, without requiring an exact dialect-specific type match: {@link #STRING},
 * {@link #NUMERIC}, {@link #BOOLEAN}, {@link #DATE_TIME}, {@link #BINARY}, or {@link #OTHER} for anything
 * not confidently classified — {@link #OTHER} never participates in a mismatch finding, keeping the
 * false-positive rate low.
 */
enum JdbcTypeFamily {
    STRING,
    NUMERIC,
    BOOLEAN,
    DATE_TIME,
    BINARY,
    OTHER;

    private static final Set<String> STRING_JDBC_TYPES = Set.of("char", "clob", "text");
    private static final Set<String> NUMERIC_JDBC_TYPES =
            Set.of("int", "numeric", "decimal", "float", "double", "real", "serial", "money");
    private static final Set<String> BOOLEAN_JDBC_TYPES = Set.of("bool");
    private static final Set<String> DATE_TIME_JDBC_TYPES = Set.of("date", "time", "timestamp");
    private static final Set<String> BINARY_JDBC_TYPES = Set.of("blob", "binary", "bytea");

    private static final Set<String> STRING_JAVA_TYPES = Set.of("String", "Character", "char");
    private static final Set<String> NUMERIC_JAVA_TYPES = Set.of(
            "byte",
            "short",
            "int",
            "long",
            "float",
            "double",
            "Byte",
            "Short",
            "Integer",
            "Long",
            "Float",
            "Double",
            "BigDecimal",
            "BigInteger");
    private static final Set<String> BOOLEAN_JAVA_TYPES = Set.of("boolean", "Boolean");
    private static final Set<String> DATE_TIME_JAVA_TYPES = Set.of(
            "Date",
            "LocalDate",
            "LocalDateTime",
            "LocalTime",
            "Instant",
            "OffsetDateTime",
            "ZonedDateTime",
            "Timestamp",
            "Time");

    /** Classifies a JDBC-reported type name (e.g. {@code varchar}, {@code int4}, {@code bytea}). */
    static JdbcTypeFamily ofJdbcType(String jdbcTypeName) {
        if (jdbcTypeName == null) {
            return OTHER;
        }
        String normalized = jdbcTypeName.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, BOOLEAN_JDBC_TYPES)) {
            return BOOLEAN;
        }
        if (containsAny(normalized, DATE_TIME_JDBC_TYPES)) {
            return DATE_TIME;
        }
        if (containsAny(normalized, BINARY_JDBC_TYPES)) {
            return BINARY;
        }
        if (containsAny(normalized, STRING_JDBC_TYPES)) {
            return STRING;
        }
        if (containsAny(normalized, NUMERIC_JDBC_TYPES)) {
            return NUMERIC;
        }
        return OTHER;
    }

    /** Classifies a mapped Java attribute's raw type simple name (e.g. {@code String}, {@code Integer}). */
    static JdbcTypeFamily ofJavaType(String javaTypeSimpleName) {
        if (javaTypeSimpleName == null) {
            return OTHER;
        }
        if (STRING_JAVA_TYPES.contains(javaTypeSimpleName)) {
            return STRING;
        }
        if (NUMERIC_JAVA_TYPES.contains(javaTypeSimpleName)) {
            return NUMERIC;
        }
        if (BOOLEAN_JAVA_TYPES.contains(javaTypeSimpleName)) {
            return BOOLEAN;
        }
        if (DATE_TIME_JAVA_TYPES.contains(javaTypeSimpleName)) {
            return DATE_TIME;
        }
        return OTHER;
    }

    private static boolean containsAny(String value, Set<String> needles) {
        return needles.stream().anyMatch(value::contains);
    }
}
