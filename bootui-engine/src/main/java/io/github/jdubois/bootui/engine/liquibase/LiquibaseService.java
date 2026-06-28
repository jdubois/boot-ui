package io.github.jdubois.bootui.engine.liquibase;

import io.github.jdubois.bootui.core.dto.LiquibaseActionRequest;
import io.github.jdubois.bootui.core.dto.LiquibaseActionResult;
import io.github.jdubois.bootui.core.dto.LiquibaseChangeSetDto;
import io.github.jdubois.bootui.core.dto.LiquibaseDatabaseDto;
import io.github.jdubois.bootui.core.dto.LiquibaseReport;
import io.github.jdubois.bootui.spi.LiquibaseDatabaseSnapshot;
import io.github.jdubois.bootui.spi.LiquibaseProvider;
import io.github.jdubois.bootui.spi.LiquibaseTarget;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Framework-neutral logic behind the Liquibase panel, shared by the Spring Boot and Quarkus adapters.
 *
 * <p>It reads the host application's Liquibase change-log history and performs the {@code update} action
 * through a {@link LiquibaseProvider} (the framework-specific seam) while owning everything neutral:
 * combining each database's applied and pending change sets, counting them, ordering the databases, and
 * orchestrating the update action (target resolution, confirmation, disabled/not-found handling and
 * {@link LiquibaseActionResponse} shaping).</p>
 *
 * <p>The split is byte-identical to the pre-extraction Spring {@code LiquibaseController}: the provider owns
 * the {@code liquibase.*}-typed discovery, change-set reading and the update primitive; this service owns the
 * assembly and orchestration that read identically on both frameworks.</p>
 */
public final class LiquibaseService {

    static final String CONFIRMATION_REQUIRED =
            "Action requires confirm=true because it mutates the application database.";

    private final LiquibaseProvider provider;

    public LiquibaseService(LiquibaseProvider provider) {
        this.provider = provider;
    }

    /** The Liquibase report: each managed database's applied + pending change sets, sorted and counted. */
    public LiquibaseReport report() {
        if (provider == null || !provider.available()) {
            return new LiquibaseReport(false, 0, List.of());
        }
        List<LiquibaseDatabaseDto> databases = new ArrayList<>();
        for (LiquibaseDatabaseSnapshot snapshot : provider.databases()) {
            databases.add(toDatabaseDto(snapshot));
        }
        databases.sort(Comparator.comparing(LiquibaseDatabaseDto::name, Comparator.nullsLast(String::compareTo)));
        int total = databases.stream().mapToInt(LiquibaseDatabaseDto::total).sum();
        return new LiquibaseReport(true, total, databases);
    }

    /** Runs {@code update} on one managed database, owning all target/confirmation/result orchestration. */
    public LiquibaseActionResponse update(LiquibaseActionRequest request) {
        if (provider == null || !provider.available()) {
            return action(404, "unavailable", "No Liquibase integration is available.", null);
        }
        LiquibaseTarget target = findTarget(request).orElse(null);
        if (target == null) {
            return action(404, "unavailable", "No Liquibase bean matched the requested datasource.", null);
        }
        if (target.updateDisabledReason() != null) {
            return action(403, "blocked", target.updateDisabledReason(), target.name());
        }
        if (!confirmed(request)) {
            return action(400, "blocked", CONFIRMATION_REQUIRED, target.name());
        }
        try {
            return new LiquibaseActionResponse(200, provider.update(target.name()));
        } catch (Exception ex) {
            return action(500, "failed", ex.getMessage(), target.name());
        }
    }

    private LiquibaseDatabaseDto toDatabaseDto(LiquibaseDatabaseSnapshot snapshot) {
        List<LiquibaseChangeSetDto> applied = snapshot.appliedChangeSets();
        List<LiquibaseChangeSetDto> pending = snapshot.pendingChangeSets();
        List<LiquibaseChangeSetDto> changeSets = new ArrayList<>(applied.size() + pending.size());
        changeSets.addAll(applied);
        changeSets.addAll(pending);
        return new LiquibaseDatabaseDto(
                snapshot.name(),
                applied.size(),
                pending.size(),
                changeSets.size(),
                changeSets,
                snapshot.updateDisabledReason() == null,
                snapshot.updateDisabledReason());
    }

    private java.util.Optional<LiquibaseTarget> findTarget(LiquibaseActionRequest request) {
        List<LiquibaseTarget> targets = provider.targets();
        String requested = request == null ? null : request.beanName();
        if (requested == null || requested.isBlank()) {
            return targets.size() == 1 ? java.util.Optional.of(targets.get(0)) : java.util.Optional.empty();
        }
        return targets.stream()
                .filter(target -> target.name().equals(requested))
                .findFirst();
    }

    private boolean confirmed(LiquibaseActionRequest request) {
        return request != null && Boolean.TRUE.equals(request.confirm());
    }

    private LiquibaseActionResponse action(int status, String result, String message, String beanName) {
        return new LiquibaseActionResponse(
                status, new LiquibaseActionResult(result, message, beanName, null, null, null, List.of()));
    }
}
