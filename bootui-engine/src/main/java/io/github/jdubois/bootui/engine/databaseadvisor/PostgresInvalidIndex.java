package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * A PostgreSQL index whose {@code pg_index} catalog entry reports it as unusable: {@code indisvalid = false}
 * (typically a failed {@code CREATE INDEX CONCURRENTLY}), {@code indisready = false} (not yet accepting
 * inserts), or {@code indislive = false} (being dropped concurrently).
 *
 * @param schema the index's table schema, always qualified
 * @param table the indexed table
 * @param index the index name
 * @param valid {@code pg_index.indisvalid}
 * @param ready {@code pg_index.indisready}
 * @param live {@code pg_index.indislive}
 * @param unique {@code pg_index.indisunique}; invalid unique indexes can still reject conflicting writes
 */
record PostgresInvalidIndex(
        String schema, String table, String index, Boolean valid, Boolean ready, Boolean live, Boolean unique) {

    PostgresInvalidIndex(
            String schema, String table, String index, boolean valid, boolean ready, boolean live, boolean unique) {
        this(
                schema,
                table,
                index,
                Boolean.valueOf(valid),
                Boolean.valueOf(ready),
                Boolean.valueOf(live),
                Boolean.valueOf(unique));
    }

    boolean explicitlyInvalid() {
        return Boolean.FALSE.equals(valid) || Boolean.FALSE.equals(ready) || Boolean.FALSE.equals(live);
    }

    String qualifiedTable() {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }

    String describeFlags() {
        StringBuilder flags = new StringBuilder();
        if (Boolean.FALSE.equals(valid)) {
            flags.append("indisvalid=false");
        }
        if (Boolean.FALSE.equals(ready)) {
            flags.append(flags.isEmpty() ? "" : ", ").append("indisready=false");
        }
        if (Boolean.FALSE.equals(live)) {
            flags.append(flags.isEmpty() ? "" : ", ").append("indislive=false");
        }
        return flags.toString();
    }
}
