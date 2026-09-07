package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral seam behind the Database Advisor panel: it reports the host application's JDBC
 * {@code DataSource} beans (directly, and through proxied/wrapped {@code DataSource} beans — Spring's
 * delegating/routing wrappers are resolved and de-duplicated by the adapter) so the
 * engine {@code DatabaseAdvisorScanner} can open a short-lived, read-only connection against each one
 * and introspect its physical schema through {@code java.sql.DatabaseMetaData}.
 *
 * <p>This is strictly read-only: the scanner never executes DDL, never queries application data, and
 * never mutates a datasource. A successfully empty inventory is distinct from failed discovery.</p>
 */
public interface DatabaseAdvisorDataSourceProvider {

    /** The application's JDBC datasources, unsorted and de-duplicated by the adapter. */
    List<NamedDataSource> dataSources();

    /** Rich discovery for scanners that can retain successful candidates while reporting failures. */
    default DatabaseAdvisorDataSourceDiscovery discover() {
        return new DatabaseAdvisorDataSourceDiscovery(dataSources(), List.of());
    }
}
