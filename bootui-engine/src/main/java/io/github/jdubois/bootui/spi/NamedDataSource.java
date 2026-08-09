package io.github.jdubois.bootui.spi;

import javax.sql.DataSource;

/**
 * One JDBC {@link DataSource} bean known to the host application, paired with its adapter-reported name
 * (the Spring bean name, or the Quarkus datasource name with the default datasource rendered as
 * {@code "default"}).
 *
 * <p>Unlike {@link ConnectionPoolInfo}, this carries the live {@link DataSource} handle itself (not a
 * pool-library-specific snapshot), because the Database Advisor needs to open a short-lived, read-only
 * JDBC connection to introspect the physical schema through {@code java.sql.DatabaseMetaData}. It never
 * borrows a connection eagerly and never mutates the datasource.</p>
 */
public record NamedDataSource(String name, DataSource dataSource) {}
