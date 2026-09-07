package io.github.jdubois.bootui.engine.databaseadvisor;

import java.math.BigInteger;

/**
 * Maps a {@code GENERATED ... AS IDENTITY} column ({@code all_tab_identity_cols}) to the internally-named
 * sequence Oracle created to back it (typically {@code ISEQ$$_<object id>}), purely so a finding about that
 * sequence can name the column it actually serves instead of an opaque system-generated sequence name.
 */
record OracleIdentityColumn(
        String schema,
        String table,
        String column,
        String sequenceName,
        String dataType,
        Integer precision,
        Integer scale) {

    OracleIdentityColumn(String schema, String table, String column, String sequenceName) {
        this(schema, table, column, sequenceName, null, null, null);
    }

    BigInteger capacity() {
        return "NUMBER".equalsIgnoreCase(dataType)
                        && precision != null
                        && precision > 0
                        && precision <= 38
                        && scale != null
                        && scale == 0
                ? BigInteger.TEN.pow(precision).subtract(BigInteger.ONE)
                : null;
    }

    String qualifiedColumn() {
        String qualifiedTable = schema == null || schema.isBlank() ? table : schema + "." + table;
        return qualifiedTable + "." + column;
    }
}
