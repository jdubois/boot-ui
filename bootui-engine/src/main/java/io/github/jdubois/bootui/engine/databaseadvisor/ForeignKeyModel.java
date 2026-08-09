package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.List;

/**
 * One physical foreign key read from {@code DatabaseMetaData.getImportedKeys}, with its columns in key
 * sequence order.
 */
record ForeignKeyModel(String name, List<String> columns, String referencedTable) {

    ForeignKeyModel {
        columns = List.copyOf(columns);
    }
}
