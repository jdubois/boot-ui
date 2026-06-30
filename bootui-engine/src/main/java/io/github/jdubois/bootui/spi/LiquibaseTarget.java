package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral, <em>cheap</em> descriptor of one Liquibase-managed database for the update action's target
 * resolution: just its name and whether (and why) its update is disabled — deliberately <em>without</em> the
 * expensive applied/pending change-set read that {@link LiquibaseDatabaseSnapshot} carries.
 *
 * <p>The engine {@code LiquibaseService} resolves the update target from {@link LiquibaseProvider#targets()}
 * (not {@link LiquibaseProvider#databases()}), so triggering a mutation never performs the JDBC change-log
 * history read — exactly matching the Spring controller, which resolves its target via a bean lookup with no
 * database access before running the update.</p>
 *
 * @param name the database / change-log identifier (a {@code SpringLiquibase} bean name on Spring, the
 *     datasource name on Quarkus)
 * @param updateDisabledReason the reason the update action cannot run, or {@code null} when it can
 */
public record LiquibaseTarget(String name, String updateDisabledReason) {}
