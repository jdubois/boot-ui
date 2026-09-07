package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * One {@code information_schema.statistics} key part on MySQL/MariaDB: the facts JDBC's {@code getIndexInfo}
 * cannot report — the indexed prefix length ({@code SUB_PART}), the access method ({@code INDEX_TYPE}), the
 * functional key part's expression, and whether the optimizer is allowed to use the index at all
 * ({@code IS_VISIBLE} on MySQL 8.0, {@code IGNORED} on MariaDB 10.6).
 *
 * @param position {@code SEQ_IN_INDEX}, one-based
 * @param visible {@code TRUE}/{@code FALSE} when the server reports visibility, {@code null} when it cannot
 */
record MySqlIndexDetail(
        String schema,
        String table,
        String index,
        int position,
        String column,
        Integer subPart,
        String collation,
        String indexType,
        boolean unique,
        Boolean visible,
        String expression,
        boolean generatedColumn,
        boolean definitionComplete) {

    MySqlIndexDetail(
            String schema,
            String table,
            String index,
            int position,
            String column,
            Integer subPart,
            String collation,
            String indexType,
            boolean unique,
            Boolean visible,
            String expression) {
        this(
                schema,
                table,
                index,
                position,
                column,
                subPart,
                collation,
                indexType,
                unique,
                visible,
                expression,
                false,
                false);
    }
}
