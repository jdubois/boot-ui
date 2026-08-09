/**
 * Framework-neutral Database Advisor: a bounded, on-demand scanner that introspects the host
 * application's physical schema through plain JDBC {@code java.sql.DatabaseMetaData} — tables, columns,
 * primary/foreign keys, and indexes — with a small amount of PostgreSQL/MySQL-specific catalog
 * augmentation, and cross-references it against the Hibernate metamodel (via
 * {@link io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge}) when both are available.
 *
 * <p>Plain Java (BootUI core DTOs + the neutral {@code javax.sql.DataSource} JDK contract); adapters
 * supply already-resolved {@link io.github.jdubois.bootui.spi.NamedDataSource} handles and wire
 * {@link io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner} via an {@code @Bean}
 * factory / {@code @Produces} method, exactly like the Hibernate Advisor.
 */
package io.github.jdubois.bootui.engine.databaseadvisor;
