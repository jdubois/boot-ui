package io.github.jdubois.bootui.engine.flyway;

import io.github.jdubois.bootui.core.dto.FlywayActionRequest;
import io.github.jdubois.bootui.core.dto.FlywayActionResult;
import io.github.jdubois.bootui.core.dto.FlywayDatabaseDto;
import io.github.jdubois.bootui.core.dto.FlywayMigrationDto;
import io.github.jdubois.bootui.core.dto.FlywayReport;
import io.github.jdubois.bootui.spi.FlywayCleanOutcome;
import io.github.jdubois.bootui.spi.FlywayDatabaseSnapshot;
import io.github.jdubois.bootui.spi.FlywayMigrateOutcome;
import io.github.jdubois.bootui.spi.FlywayMigrationSnapshot;
import io.github.jdubois.bootui.spi.FlywayProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Framework-neutral logic behind the Flyway panel, shared by the Spring Boot and Quarkus adapters.
 *
 * <p>It reads the host application's Flyway-managed databases and performs the {@code migrate}/{@code clean}
 * primitives through a {@link FlywayProvider} (the framework-specific seam, the sole holder of
 * {@code org.flywaydb.*} types), while owning everything neutral: counting applied/pending migrations and the
 * current version, sorting and totalling databases, and orchestrating the actions — the Spring-Modulith block,
 * target resolution, confirmation gating, clean-disabled gating and {@link FlywayActionResult} shaping.</p>
 *
 * <p>The behaviour is a byte-identical extraction of the former Spring {@code FlywayController}: the report
 * assembly and the action gate order/messages are reproduced exactly, so the wire contract is unchanged. The
 * Spring-specific concerns (bean discovery, Spring-Modulith module-aware views, the framework-worded
 * clean-disabled / actions-blocked messages) live in {@code SpringFlywayProvider}; Quarkus supplies its own
 * provider with no Modulith behaviour.</p>
 */
public final class FlywayService {

    private static final String CONFIRMATION_REQUIRED =
            "Action requires confirm=true because it mutates the application database.";

    private static final String NO_TARGET_MATCH = "No Flyway bean matched the requested datasource.";

    private final FlywayProvider provider;

    public FlywayService(FlywayProvider provider) {
        this.provider = provider;
    }

    /** The Flyway migration report: one entry per managed database, sorted by name, with totals. */
    public FlywayReport report() {
        if (provider == null || !provider.available()) {
            return new FlywayReport(false, 0, List.of());
        }
        List<FlywayDatabaseDto> databases = provider.report().stream()
                .map(this::toDatabaseDto)
                .sorted(Comparator.comparing(FlywayDatabaseDto::name, Comparator.nullsLast(String::compareTo)))
                .toList();
        int total = databases.stream().mapToInt(FlywayDatabaseDto::total).sum();
        return new FlywayReport(true, total, databases);
    }

    /** Runs {@code migrate} against the resolved target, owning all block/target/confirmation orchestration. */
    public FlywayActionResponse migrate(FlywayActionRequest request) {
        if (provider == null) {
            return action(404, "unavailable", NO_TARGET_MATCH, null);
        }
        Optional<String> blocked = provider.actionsBlockedReason();
        if (blocked.isPresent()) {
            return action(403, "blocked", blocked.get(), requestedBeanName(request));
        }
        String target = resolveTarget(request);
        if (target == null) {
            return action(404, "unavailable", NO_TARGET_MATCH, null);
        }
        if (!confirmed(request)) {
            return action(400, "blocked", CONFIRMATION_REQUIRED, target);
        }
        FlywayMigrateOutcome outcome = provider.migrate(target);
        if (outcome.failed()) {
            return action(500, "failed", outcome.errorMessage(), target);
        }
        String message = outcome.migrationsExecuted() == 0
                ? "Flyway schema is already up to date."
                : "Flyway applied " + outcome.migrationsExecuted() + " migration(s).";
        return new FlywayActionResponse(
                200,
                new FlywayActionResult(
                        outcome.success() ? "success" : "failed",
                        message,
                        target,
                        outcome.migrationsExecuted(),
                        List.of(),
                        List.of(),
                        null,
                        outcome.warnings()));
    }

    /** Runs {@code clean} against the resolved target; clean-disabled is checked before the confirmation gate. */
    public FlywayActionResponse clean(FlywayActionRequest request) {
        if (provider == null) {
            return action(404, "unavailable", NO_TARGET_MATCH, null);
        }
        Optional<String> blocked = provider.actionsBlockedReason();
        if (blocked.isPresent()) {
            return action(403, "blocked", blocked.get(), requestedBeanName(request));
        }
        String target = resolveTarget(request);
        if (target == null) {
            return action(404, "unavailable", NO_TARGET_MATCH, null);
        }
        Optional<String> disabled = provider.cleanDisabledReason(target);
        if (disabled.isPresent()) {
            return action(403, "blocked", disabled.get(), target);
        }
        if (!confirmed(request)) {
            return action(400, "blocked", CONFIRMATION_REQUIRED, target);
        }
        FlywayCleanOutcome outcome = provider.clean(target);
        if (outcome.failed()) {
            return action(500, "failed", outcome.errorMessage(), target);
        }
        return new FlywayActionResponse(
                200,
                new FlywayActionResult(
                        "success",
                        "Flyway cleaned schema(s) for " + target + ".",
                        target,
                        null,
                        outcome.schemasCleaned(),
                        outcome.schemasDropped(),
                        null,
                        outcome.warnings()));
    }

    private FlywayDatabaseDto toDatabaseDto(FlywayDatabaseSnapshot snapshot) {
        List<FlywayMigrationDto> migrations =
                new ArrayList<>(snapshot.migrations().size());
        String currentVersion = null;
        int applied = 0;
        int pending = 0;
        for (FlywayMigrationSnapshot migration : snapshot.migrations()) {
            if (migration.applied()) {
                applied++;
                String version = migration.migration().version();
                if (version != null) {
                    currentVersion = version;
                }
            } else if (migration.pending()) {
                pending++;
            }
            migrations.add(migration.migration());
        }
        return new FlywayDatabaseDto(
                snapshot.name(),
                currentVersion,
                applied,
                pending,
                migrations.size(),
                migrations,
                snapshot.migrateEnabled(),
                snapshot.migrateDisabledReason(),
                snapshot.cleanEnabled(),
                snapshot.cleanDisabledReason());
    }

    private String resolveTarget(FlywayActionRequest request) {
        List<String> targets = provider.actionTargets();
        String requested = request == null ? null : request.beanName();
        if (requested == null || requested.isBlank()) {
            return targets.size() == 1 ? targets.get(0) : null;
        }
        return targets.contains(requested) ? requested : null;
    }

    private boolean confirmed(FlywayActionRequest request) {
        return request != null && Boolean.TRUE.equals(request.confirm());
    }

    private String requestedBeanName(FlywayActionRequest request) {
        String beanName = request == null ? null : request.beanName();
        return beanName == null || beanName.isBlank() ? null : beanName;
    }

    private FlywayActionResponse action(int status, String result, String message, String beanName) {
        return new FlywayActionResponse(
                status, new FlywayActionResult(result, message, beanName, null, List.of(), List.of(), null, List.of()));
    }
}
