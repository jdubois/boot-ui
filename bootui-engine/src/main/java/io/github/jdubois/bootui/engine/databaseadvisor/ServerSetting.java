package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * One database-server setting as the scan connection's own session sees it: {@code pg_settings} on PostgreSQL,
 * {@code SHOW SESSION VARIABLES} on MySQL and MariaDB. A pool's sessions can differ when it runs an init statement or
 * passes driver options, so the value describes the scan connection, not necessarily every pooled one.
 *
 * @param name the setting name, as the server reports it
 * @param value the raw value, or {@code null} when the server reports none
 * @param unit the unit {@code value} is expressed in ({@code ms}, {@code s}, {@code min}, {@code 8kB}, ...), or
 *     {@code null} when the setting has no unit; MySQL and MariaDB report none
 */
record ServerSetting(String name, String value, String unit) {}
