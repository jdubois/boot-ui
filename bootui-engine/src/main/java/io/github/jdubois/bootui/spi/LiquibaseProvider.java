package io.github.jdubois.bootui.spi;

import io.github.jdubois.bootui.core.dto.LiquibaseActionResult;
import java.util.List;

/**
 * Framework-neutral seam behind the Liquibase panel: it discovers the host application's Liquibase-managed
 * databases (their applied/pending change sets and update availability) and performs the {@code update}
 * mutation, while the engine {@code LiquibaseService} owns the framework-neutral concerns — combining and
 * counting change sets, ordering the databases, and orchestrating the update action (target resolution,
 * confirmation, disabled/not-found handling and result shaping).
 *
 * <p>The Spring adapter implements this over {@code liquibase.integration.spring.SpringLiquibase} beans (the
 * applied read via {@code StandardChangeLogHistoryService}, the pending read via {@code listUnrunChangeSets}
 * inside a Liquibase {@code Scope}); the Quarkus adapter over the {@code io.quarkus.liquibase.LiquibaseFactory}
 * instances of the application's active datasources. Both keep all {@code liquibase.*} types behind this seam,
 * so the engine stays liquibase-free (enforced by the ArchUnit boundary rules).</p>
 */
public interface LiquibaseProvider {

    /**
     * Whether the Liquibase integration is present and usable. {@code false} means no Liquibase infrastructure
     * is available (the engine then serves an empty {@code liquibasePresent=false} report and refuses to
     * update). Note this is distinct from "databases exist": the integration can be present with zero
     * Liquibase-managed databases configured.
     */
    boolean available();

    /**
     * The Liquibase-managed databases as framework-neutral, <em>unsorted</em> snapshots carrying their applied
     * and pending change sets and update-disabled reason. The engine sorts the databases by name and combines
     * the change sets. This is the expensive path (it reads each change-log history table); the update action
     * uses {@link #targets()} instead. Returns an empty list when no databases are configured.
     */
    List<LiquibaseDatabaseSnapshot> databases();

    /**
     * The Liquibase-managed databases as <em>cheap</em> targets (name + update-disabled reason only), used by
     * the engine to resolve the update action's target without triggering the change-log history read. Returns
     * an empty list when no databases are configured.
     */
    List<LiquibaseTarget> targets();

    /**
     * Runs {@code update} on the named database, applying its pending change sets, and returns the resulting
     * {@link LiquibaseActionResult}. The engine only calls this after resolving the target via {@link
     * #targets()} and checking its {@code updateDisabledReason} and the caller's confirmation, so
     * implementations may assume the named database exists and is update-enabled. Implementations should let
     * genuine failures propagate as an {@link Exception}; the engine catches it and reports a {@code failed}
     * status carrying the exception message — byte-identical to the pre-extraction Spring behavior.
     *
     * @param name the database to update (a name returned by {@link #targets()})
     * @return the success result (status {@code success}) describing how many change sets were applied
     * @throws Exception when the update fails
     */
    LiquibaseActionResult update(String name) throws Exception;
}
