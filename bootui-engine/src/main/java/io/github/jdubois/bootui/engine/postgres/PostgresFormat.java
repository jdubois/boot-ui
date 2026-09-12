package io.github.jdubois.bootui.engine.postgres;

import java.util.Locale;

/** Deterministic, locale-independent rendering of the evidence strings the rules produce. */
final class PostgresFormat {

    private PostgresFormat() {}

    static String percent(Double ratio) {
        return ratio == null ? "unknown" : String.format(Locale.ROOT, "%.1f%%", ratio * 100d);
    }

    static String millis(Double value) {
        return value == null ? "unknown" : String.format(Locale.ROOT, "%.1f ms", value);
    }

    static String seconds(Double value) {
        return value == null ? "unknown" : String.format(Locale.ROOT, "%.1f s", value);
    }

    static String bytes(Long value) {
        if (value == null) {
            return "unknown";
        }
        double size = value;
        String[] units = {"B", "kB", "MB", "GB", "TB"};
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return unit == 0 ? value + " B" : String.format(Locale.ROOT, "%.1f %s", size, units[unit]);
    }

    static String count(Long value) {
        return value == null ? "unknown" : Long.toString(value);
    }

    static String relation(String schema, String table) {
        return schema == null || schema.isBlank() ? String.valueOf(table) : schema + "." + table;
    }
}
