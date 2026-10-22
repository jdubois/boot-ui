package io.github.jdubois.bootui.engine.hibernate;

/**
 * The connection pool behind one persistence unit, reduced to what the Hibernate advisor may state: no URL, user
 * name or credential. A {@code null} {@code autoCommit} means the pool did not report it, never a default.
 *
 * @param dataSourceName the Spring bean name or Quarkus datasource name of the pool
 * @param implementation the pool library, such as {@code HikariCP} or {@code Agroal}
 * @param autoCommit whether the pool hands out auto-commit connections, or {@code null} when unknown
 */
public record HibernatePoolObservation(String dataSourceName, String implementation, Boolean autoCommit) {}
