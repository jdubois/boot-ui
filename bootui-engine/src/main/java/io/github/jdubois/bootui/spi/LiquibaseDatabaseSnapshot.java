package io.github.jdubois.bootui.spi;

import io.github.jdubois.bootui.core.dto.LiquibaseChangeSetDto;
import java.util.List;

/**
 * Framework-neutral snapshot of one Liquibase-managed database (one change-log table) known to a
 * {@link LiquibaseProvider}: its name, the applied and pending change sets, and the reason its update action
 * is disabled (or {@code null} when the update can run).
 *
 * <p>The applied and pending change sets are returned as already-mapped {@link LiquibaseChangeSetDto} records
 * (the {@code RanChangeSet} / {@code ChangeSet} reading is liquibase-typed and lives in the adapter); the
 * engine {@code LiquibaseService} only combines and counts them, so it never touches a Liquibase type.</p>
 *
 * @param name the database / change-log identifier (a {@code SpringLiquibase} bean name on Spring, the
 *     datasource name on Quarkus)
 * @param appliedChangeSets the change sets recorded as run in the change-log history table
 * @param pendingChangeSets the change sets that have not yet run (empty when the update is disabled)
 * @param updateDisabledReason the reason the update action cannot run, or {@code null} when it can
 */
public record LiquibaseDatabaseSnapshot(
        String name,
        List<LiquibaseChangeSetDto> appliedChangeSets,
        List<LiquibaseChangeSetDto> pendingChangeSets,
        String updateDisabledReason) {

    public LiquibaseDatabaseSnapshot {
        appliedChangeSets = appliedChangeSets == null ? List.of() : List.copyOf(appliedChangeSets);
        pendingChangeSets = pendingChangeSets == null ? List.of() : List.copyOf(pendingChangeSets);
    }
}
