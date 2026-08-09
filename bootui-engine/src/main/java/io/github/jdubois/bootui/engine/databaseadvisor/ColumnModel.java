package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * One physical column read from {@code DatabaseMetaData.getColumns}.
 *
 * @param name the column name
 * @param typeName the JDBC-reported type name (e.g. {@code varchar}, {@code int4})
 * @param nullable whether the column allows {@code NULL}
 * @param size the column size/precision as reported by the driver, or {@code -1} when unknown
 */
record ColumnModel(String name, String typeName, boolean nullable, int size) {}
