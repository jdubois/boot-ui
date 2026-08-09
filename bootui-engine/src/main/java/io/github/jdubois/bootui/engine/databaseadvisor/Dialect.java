package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * The JDBC dialect family detected for one datasource, used only to decide which read-only catalog
 * augmentation queries (if any) the {@link SchemaIntrospector} may run in addition to the generic
 * {@code java.sql.DatabaseMetaData} introspection. Every other dialect (H2, SQL Server, Oracle,
 * MariaDB, ...) still gets a full schema scan through {@link #GENERIC}; it is never a reason to fail
 * closed.
 */
enum Dialect {
    POSTGRESQL,
    MYSQL,
    GENERIC;

    static Dialect detect(String productName, String jdbcUrl) {
        String normalizedProduct = productName == null ? "" : productName.toLowerCase(java.util.Locale.ROOT);
        String normalizedUrl = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(java.util.Locale.ROOT);
        if (normalizedProduct.contains("postgresql") || normalizedUrl.startsWith("jdbc:postgresql")) {
            return POSTGRESQL;
        }
        if (normalizedProduct.contains("mysql") || normalizedUrl.startsWith("jdbc:mysql")) {
            return MYSQL;
        }
        return GENERIC;
    }
}
