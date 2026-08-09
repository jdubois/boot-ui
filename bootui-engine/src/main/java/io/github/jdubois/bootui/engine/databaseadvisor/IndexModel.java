package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.List;

/**
 * One physical index read from {@code DatabaseMetaData.getIndexInfo}, with its columns in ordinal
 * position order.
 */
record IndexModel(String name, List<String> columns, boolean unique) {

    IndexModel {
        columns = List.copyOf(columns);
    }

    /** The index's leading (first) column, or {@code null} for a column-less index entry. */
    String leadingColumn() {
        return columns.isEmpty() ? null : columns.get(0);
    }
}
