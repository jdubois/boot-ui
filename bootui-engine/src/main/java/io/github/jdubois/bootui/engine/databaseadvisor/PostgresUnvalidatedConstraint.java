package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * Current unvalidated state; it does not establish migration history or whether existing rows violate it.
 *
 * @param type {@code f} for a foreign key, {@code c} for a check constraint
 */
record PostgresUnvalidatedConstraint(
        String schema, String table, String constraint, String type, String definition, Boolean enforced) {

    PostgresUnvalidatedConstraint(String schema, String table, String constraint, String type, String definition) {
        this(schema, table, constraint, type, definition, null);
    }

    String qualifiedTable() {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }

    String describeType() {
        return switch (type == null ? "" : type) {
            case "f" -> "foreign key";
            case "n" -> "not-null";
            default -> "check";
        };
    }
}
