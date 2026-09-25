package io.github.jdubois.bootui.engine.databaseadvisor;

import java.math.BigInteger;

/**
 * One MySQL/MariaDB base table's {@code information_schema.tables} row: its storage engine, its default
 * character set (derived from {@code TABLE_COLLATION}), and the next value its {@code AUTO_INCREMENT} counter
 * will hand out.
 *
 * @param nextAutoIncrement {@code AUTO_INCREMENT}, or {@code null} when the server did not report it (no
 *     auto-increment column, or statistics the server declines to compute) — never treated as zero
 */
record MySqlTableInfo(String schema, String table, String engine, String collation, BigInteger nextAutoIncrement) {

    String qualifiedName() {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }

    /** The character set implied by the collation (MySQL collations are {@code <charset>_<...>}). */
    String characterSet() {
        if (collation == null || collation.isBlank()) {
            return null;
        }
        int separator = collation.indexOf('_');
        return separator <= 0 ? collation : collation.substring(0, separator);
    }
}
