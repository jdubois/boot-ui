package io.github.jdubois.bootui.autoconfigure.web;

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
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes read-only Flyway schema-migration state for the {@link Flyway} beans
 * declared in the current application context.
 *
 * <p>This controller is strictly read-only: it only reads the migration metadata
 * that Flyway has already computed through {@code Flyway.info()}. It never runs
 * {@code migrate}, {@code repair}, {@code clean}, {@code baseline}, or any other
 * mutating Flyway command.</p>
 */
@RestController
@ConditionalOnClass(Flyway.class)
@RequestMapping("/bootui/api/flyway")
public class FlywayController {

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
        return new FlywayDatabaseDto(entry.beanName(), currentVersion, applied, pending, migrations.size(), migrations);
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

    private record FlywayEntry(String beanName, Flyway flyway) {}
}
