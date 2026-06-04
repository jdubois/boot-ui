package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.FlywayActionRequest;
import io.github.jdubois.bootui.core.dto.FlywayActionResult;
import io.github.jdubois.bootui.core.dto.FlywayDatabaseDto;
import io.github.jdubois.bootui.core.dto.FlywayMigrationDto;
import io.github.jdubois.bootui.core.dto.FlywayReport;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.CleanResult;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes Flyway schema-migration state and Flyway actions for the {@link Flyway}
 * beans declared in the current application context.
 *
 * <p>Mutating commands require an explicit confirmation payload and remain
 * subject to BootUI's global/per-panel read-only filter.</p>
 */
@RestController
@ConditionalOnClass(Flyway.class)
@RequestMapping("/bootui/api/flyway")
public class FlywayController {

    private static final String CLEAN_DISABLED_BY_FLYWAY =
            "Flyway clean is disabled by Flyway configuration. Set spring.flyway.clean-disabled=false to allow it.";
    private static final String CONFIRMATION_REQUIRED =
            "Action requires confirm=true because it mutates the application database.";

    private final ObjectProvider<ListableBeanFactory> beanFactoryProvider;

    public FlywayController(ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
        this.beanFactoryProvider = beanFactoryProvider;
    }

    @GetMapping("/migrations")
    public FlywayReport migrations() {
        List<FlywayDatabaseDto> databases = discover().stream()
                .map(this::toDatabaseDto)
                .sorted(Comparator.comparing(FlywayDatabaseDto::name, Comparator.nullsLast(String::compareTo)))
                .toList();
        int total = databases.stream().mapToInt(FlywayDatabaseDto::total).sum();
        return new FlywayReport(true, total, databases);
    }

    @PostMapping("/migrate")
    public ResponseEntity<FlywayActionResult> migrate(@RequestBody(required = false) FlywayActionRequest request) {
        FlywayEntry entry = findTarget(request).orElse(null);
        if (entry == null) {
            return action(
                    HttpStatus.NOT_FOUND, "unavailable", "No Flyway bean matched the requested datasource.", null);
        }
        if (!confirmed(request)) {
            return action(HttpStatus.BAD_REQUEST, "blocked", CONFIRMATION_REQUIRED, entry.beanName());
        }
        try {
            MigrateResult result = entry.flyway().migrate();
            String message = result.migrationsExecuted == 0
                    ? "Flyway schema is already up to date."
                    : "Flyway applied " + result.migrationsExecuted + " migration(s).";
            return ResponseEntity.ok(new FlywayActionResult(
                    result.success ? "success" : "failed",
                    message,
                    entry.beanName(),
                    result.migrationsExecuted,
                    List.of(),
                    List.of(),
                    null,
                    nullSafeList(result.warnings)));
        } catch (FlywayException ex) {
            return action(HttpStatus.INTERNAL_SERVER_ERROR, "failed", ex.getMessage(), entry.beanName());
        }
    }

    @PostMapping("/clean")
    public ResponseEntity<FlywayActionResult> clean(@RequestBody(required = false) FlywayActionRequest request) {
        FlywayEntry entry = findTarget(request).orElse(null);
        if (entry == null) {
            return action(
                    HttpStatus.NOT_FOUND, "unavailable", "No Flyway bean matched the requested datasource.", null);
        }
        String disabledReason = cleanDisabledReason(entry.flyway());
        if (disabledReason != null) {
            return action(HttpStatus.FORBIDDEN, "blocked", disabledReason, entry.beanName());
        }
        if (!confirmed(request)) {
            return action(HttpStatus.BAD_REQUEST, "blocked", CONFIRMATION_REQUIRED, entry.beanName());
        }
        try {
            CleanResult result = entry.flyway().clean();
            return ResponseEntity.ok(new FlywayActionResult(
                    "success",
                    "Flyway cleaned schema(s) for " + entry.beanName() + ".",
                    entry.beanName(),
                    null,
                    nullSafeList(result.schemasCleaned),
                    nullSafeList(result.schemasDropped),
                    null,
                    nullSafeList(result.warnings)));
        } catch (FlywayException ex) {
            return action(HttpStatus.INTERNAL_SERVER_ERROR, "failed", ex.getMessage(), entry.beanName());
        }
    }

    private List<FlywayEntry> discover() {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null) {
            return List.of();
        }
        String[] beanNames = factory.getBeanNamesForType(Flyway.class);
        List<FlywayEntry> entries = new ArrayList<>(beanNames.length);
        for (String beanName : beanNames) {
            Flyway flyway;
            try {
                flyway = factory.getBean(beanName, Flyway.class);
            } catch (Exception ex) {
                continue;
            }
            entries.add(new FlywayEntry(strip(beanName), flyway));
        }
        return entries;
    }

    private FlywayDatabaseDto toDatabaseDto(FlywayEntry entry) {
        MigrationInfo[] all;
        try {
            all = entry.flyway().info().all();
        } catch (Exception ex) {
            all = new MigrationInfo[0];
        }
        List<FlywayMigrationDto> migrations = new ArrayList<>(all.length);
        String currentVersion = null;
        int applied = 0;
        int pending = 0;
        for (MigrationInfo info : all) {
            MigrationState state = info.getState();
            if (state != null && state.isApplied()) {
                applied++;
                String version = nullSafeToString(info.getVersion());
                if (version != null) {
                    currentVersion = version;
                }
            } else if (state == MigrationState.PENDING) {
                pending++;
            }
            migrations.add(toMigrationDto(info));
        }
        String cleanDisabledReason = cleanDisabledReason(entry.flyway());
        return new FlywayDatabaseDto(
                entry.beanName(),
                currentVersion,
                applied,
                pending,
                migrations.size(),
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

    @Nullable
    private String nullSafeToString(@Nullable Object value) {
        return value == null ? null : value.toString();
    }

    @Nullable
    private String nullSafeToInstant(@Nullable Date date) {
        return date == null ? null : Instant.ofEpochMilli(date.getTime()).toString();
    }

    private String strip(String beanName) {
        return beanName.startsWith("&") ? beanName.substring(1) : beanName;
    }

    private java.util.Optional<FlywayEntry> findTarget(@Nullable FlywayActionRequest request) {
        List<FlywayEntry> entries = discover();
        String requested = request == null ? null : request.beanName();
        if (requested == null || requested.isBlank()) {
            return entries.size() == 1 ? java.util.Optional.of(entries.get(0)) : java.util.Optional.empty();
        }
        return entries.stream()
                .filter(entry -> entry.beanName().equals(requested))
                .findFirst();
    }

    @Nullable
    private String cleanDisabledReason(Flyway flyway) {
        Configuration configuration = flyway.getConfiguration();
        if (configuration != null && configuration.isCleanDisabled()) {
            return CLEAN_DISABLED_BY_FLYWAY;
        }
        return null;
    }

    private boolean confirmed(@Nullable FlywayActionRequest request) {
        return request != null && Boolean.TRUE.equals(request.confirm());
    }

    private ResponseEntity<FlywayActionResult> action(
            HttpStatus status, String result, String message, @Nullable String beanName) {
        return ResponseEntity.status(status)
                .body(new FlywayActionResult(result, message, beanName, null, List.of(), List.of(), null, List.of()));
    }

    private List<String> nullSafeList(@Nullable List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private record FlywayEntry(String beanName, Flyway flyway) {}
}
