package io.github.jdubois.bootui.engine.databaseadvisor;

import java.sql.Types;
import java.util.Locale;

/**
 * One physical column read from {@code DatabaseMetaData.getColumns}.
 *
 * <p>Nullability is a tri-state ({@link Nullability#UNKNOWN} when the driver reports
 * {@code columnNullableUnknown}) and {@code size}/{@code decimalDigits} are boxed so "the driver did not
 * report this" is never confused with "zero" — both matter because the rules must skip rather than guess
 * when the catalog cannot answer.</p>
 *
 * @param name the column name
 * @param typeName the JDBC-reported type name (e.g. {@code varchar}, {@code int4})
 * @param jdbcType the {@link java.sql.Types} constant reported in {@code DATA_TYPE}
 * @param nullability whether the column allows {@code NULL}, or {@link Nullability#UNKNOWN}
 * @param size the column size/precision, or {@code null} when the driver reported none
 * @param decimalDigits the numeric scale, or {@code null} when the driver reported none
 * @param autoIncrement whether {@code IS_AUTOINCREMENT} reported {@code YES}
 * @param autoIncrementReported {@code IS_AUTOINCREMENT} as reported: {@code TRUE} for {@code YES}, {@code FALSE}
 *     only for an explicit {@code NO}, {@code null} when the driver reported nothing or the column was not read
 * @param defaultValue {@code COLUMN_DEF}; meaningful only when {@code autoIncrementReported} is non-null, because
 *     both come from the same {@code getColumns} row
 * @param generatedColumn {@code IS_GENERATEDCOLUMN} as reported, or {@code null} when unknown
 */
record ColumnModel(
        String name,
        String typeName,
        int jdbcType,
        Nullability nullability,
        Integer size,
        Integer decimalDigits,
        boolean autoIncrement,
        Boolean autoIncrementReported,
        String defaultValue,
        Boolean generatedColumn) {

    /** A column whose generation metadata ({@code IS_AUTOINCREMENT}/{@code COLUMN_DEF}) is unknown beyond {@code YES}. */
    ColumnModel(
            String name,
            String typeName,
            int jdbcType,
            Nullability nullability,
            Integer size,
            Integer decimalDigits,
            boolean autoIncrement) {
        this(
                name,
                typeName,
                jdbcType,
                nullability,
                size,
                decimalDigits,
                autoIncrement,
                autoIncrement ? Boolean.TRUE : null,
                null,
                null);
    }

    /**
     * True only when the driver explicitly reported that the database neither auto-increments this column, nor
     * gives it a default, nor computes it as a generated column.
     */
    boolean knownWithoutDatabaseGeneration() {
        return Boolean.FALSE.equals(autoIncrementReported)
                && (defaultValue == null || defaultValue.isBlank() || "NULL".equalsIgnoreCase(defaultValue.trim()))
                && !Boolean.TRUE.equals(generatedColumn);
    }

    enum Nullability {
        NOT_NULL,
        NULLABLE,
        UNKNOWN
    }

    boolean nullable() {
        return nullability == Nullability.NULLABLE;
    }

    boolean notNull() {
        return nullability == Nullability.NOT_NULL;
    }

    /** {@code varchar(255)} / {@code numeric(10,2)} / {@code int8}, for finding details. */
    String describeType() {
        String base = typeName == null ? "unknown" : typeName;
        if (decimalDigits != null && decimalDigits > 0 && size != null && isDecimalType()) {
            return base + "(" + size + "," + decimalDigits + ")";
        }
        if (size != null && size > 0 && hasMeaningfulSize()) {
            return base + "(" + size + ")";
        }
        return base;
    }

    private boolean isDecimalType() {
        return jdbcType == Types.DECIMAL || jdbcType == Types.NUMERIC;
    }

    /** True for the type families where {@code COLUMN_SIZE} describes a declared width, not a fixed one. */
    private boolean hasMeaningfulSize() {
        return switch (jdbcType) {
            case Types.CHAR,
                    Types.VARCHAR,
                    Types.LONGVARCHAR,
                    Types.NCHAR,
                    Types.NVARCHAR,
                    Types.LONGNVARCHAR,
                    Types.BINARY,
                    Types.VARBINARY,
                    Types.LONGVARBINARY,
                    Types.DECIMAL,
                    Types.NUMERIC -> true;
            default -> false;
        };
    }

    /** True when the driver reports an unsigned integer type (MySQL/MariaDB {@code int unsigned}). */
    boolean unsigned() {
        return typeName != null && typeName.toLowerCase(Locale.ROOT).contains("unsigned");
    }
}
