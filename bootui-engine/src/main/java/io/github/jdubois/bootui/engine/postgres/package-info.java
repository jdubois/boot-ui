/**
 * The PostgreSQL panel: a bounded, read-only, on-demand read of the application's own PostgreSQL database
 * through its {@code pg_stat_*} and {@code pg_catalog} views.
 *
 * <p>It deliberately does not overlap the Database Advisor (structural schema checks) or SQL Trace (what this
 * JVM executed): it reports what the database itself says about its health, including work done by every
 * other client. Nothing here writes, cancels a backend, persists a baseline, or runs on page load.</p>
 */
package io.github.jdubois.bootui.engine.postgres;
