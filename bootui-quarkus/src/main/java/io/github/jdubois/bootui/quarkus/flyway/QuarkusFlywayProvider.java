package io.github.jdubois.bootui.quarkus.flyway;

import io.github.jdubois.bootui.core.dto.FlywayMigrationDto;
import io.github.jdubois.bootui.spi.FlywayCleanOutcome;
import io.github.jdubois.bootui.spi.FlywayDatabaseSnapshot;
import io.github.jdubois.bootui.spi.FlywayMigrateOutcome;
import io.github.jdubois.bootui.spi.FlywayMigrationSnapshot;
import io.github.jdubois.bootui.spi.FlywayProvider;
import io.quarkus.flyway.runtime.FlywayContainer;
import io.quarkus.flyway.runtime.FlywayContainersSupplier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.CleanResult;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Quarkus implementation of the framework-neutral {@link FlywayProvider} seam: the sole holder of the optional
 * {@code org.flywaydb.*} and {@code io.quarkus.flyway.*} types on the Quarkus side. It enumerates the
 * application's active Quarkus {@code FlywayContainer} beans (one per datasource), maps their
 * {@code MigrationInfo[]} to neutral {@link FlywayDatabaseSnapshot}s, and runs the {@code migrate}/{@code clean}
 * primitives. The engine {@code FlywayService} owns everything neutral (counting, sorting, totals, action
 * orchestration); Quarkus has no Spring-Modulith analogue, so {@link #actionsBlockedReason()} is always empty.
 *
 * <p>This class is constructed only by {@link io.github.jdubois.bootui.quarkus.BootUiFlywayProducer}, which the
 * deployment processor excludes from bean discovery unless the {@code FLYWAY} capability is present (R2), so the
 * Flyway types it references are never linked in a Flyway-absent application.</p>
 *
 * <p>Containers are enumerated through {@link FlywayContainersSupplier}, the Quarkus-supported accessor that
 * filters on active datasources and {@code InstanceHandle.isAvailable()} — avoiding the {@code InactiveBeanException}
 * that eager {@code @Any Instance<FlywayContainer>} iteration would throw for inactive/unconfigured datasources.</p>
 */
public class QuarkusFlywayProvider implements FlywayProvider {

    private static final String CLEAN_DISABLED_BY_FLYWAY =
            "Flyway clean is disabled by Flyway configuration. Set quarkus.flyway.clean-disabled=false to allow it.";

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<FlywayDatabaseSnapshot> report() {
        List<FlywayDatabaseSnapshot> snapshots = new ArrayList<>();
        for (FlywayContainer container : activeContainers()) {
            snapshots.add(toSnapshot(container));
        }
        return snapshots;
    }

    @Override
    public Optional<String> actionsBlockedReason() {
        return Optional.empty();
    }

    @Override
    public List<String> actionTargets() {
        List<String> targets = new ArrayList<>();
        for (FlywayContainer container : activeContainers()) {
            targets.add(container.getDataSourceName());
        }
        return targets;
    }

    @Override
    public Optional<String> cleanDisabledReason(String name) {
        return findFlyway(name).map(this::cleanDisabledReason);
    }

    @Override
    public FlywayMigrateOutcome migrate(String name) {
        Flyway flyway = findFlyway(name).orElse(null);
        if (flyway == null) {
            return new FlywayMigrateOutcome(
                    false, 0, List.of(), true, "No Flyway bean matched the requested datasource.");
        }
        try {
            MigrateResult result = flyway.migrate();
            return new FlywayMigrateOutcome(
                    result.success, result.migrationsExecuted, nullSafeList(result.warnings), false, null);
        } catch (FlywayException ex) {
            return new FlywayMigrateOutcome(false, 0, List.of(), true, ex.getMessage());
        }
    }

    @Override
    public FlywayCleanOutcome clean(String name) {
        Flyway flyway = findFlyway(name).orElse(null);
        if (flyway == null) {
            return new FlywayCleanOutcome(
                    List.of(), List.of(), List.of(), true, "No Flyway bean matched the requested datasource.");
        }
        try {
            CleanResult result = flyway.clean();
            return new FlywayCleanOutcome(
                    nullSafeList(result.schemasCleaned),
                    nullSafeList(result.schemasDropped),
                    nullSafeList(result.warnings),
                    false,
                    null);
        } catch (FlywayException ex) {
            return new FlywayCleanOutcome(List.of(), List.of(), List.of(), true, ex.getMessage());
        }
    }

    private Collection<FlywayContainer> activeContainers() {
        return new FlywayContainersSupplier().get();
    }

    private Optional<Flyway> findFlyway(String name) {
        for (FlywayContainer container : activeContainers()) {
            if (container.getDataSourceName().equals(name)) {
                return Optional.of(container.getFlyway());
            }
        }
        return Optional.empty();
    }

    private FlywayDatabaseSnapshot toSnapshot(FlywayContainer container) {
        Flyway flyway = container.getFlyway();
        MigrationInfo[] all;
        try {
            all = flyway.info().all();
        } catch (Exception ex) {
            all = new MigrationInfo[0];
        }
        List<FlywayMigrationSnapshot> migrations = new ArrayList<>(all.length);
        for (MigrationInfo info : all) {
            MigrationState state = info.getState();
            boolean applied = state != null && state.isApplied();
            boolean pending = state == MigrationState.PENDING;
            migrations.add(new FlywayMigrationSnapshot(toMigrationDto(info), applied, pending));
        }
        String cleanDisabledReason = cleanDisabledReason(flyway);
        return new FlywayDatabaseSnapshot(
                container.getDataSourceName(),
                migrations,
                true,
                null,
                cleanDisabledReason == null,
                cleanDisabledReason);
    }

    private FlywayMigrationDto toMigrationDto(MigrationInfo info) {
        MigrationState state = info.getState();
        return new FlywayMigrationDto(
                info.getType() == null ? null : info.getType().name(),
                nullSafeToString(info.getVersion()),
                info.getDescription(),
                info.getScript(),
                state == null ? null : state.getDisplayName(),
                info.getInstalledBy(),
                nullSafeToInstant(info.getInstalledOn()),
                info.getInstalledRank(),
                info.getExecutionTime(),
                info.getChecksum());
    }

    private String cleanDisabledReason(Flyway flyway) {
        Configuration configuration = flyway.getConfiguration();
        if (configuration != null && configuration.isCleanDisabled()) {
            return CLEAN_DISABLED_BY_FLYWAY;
        }
        return null;
    }

    private String nullSafeToString(Object value) {
        return value == null ? null : value.toString();
    }

    private String nullSafeToInstant(Date date) {
        return date == null ? null : Instant.ofEpochMilli(date.getTime()).toString();
    }

    private List<String> nullSafeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
