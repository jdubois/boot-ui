package io.github.jdubois.bootui.autoconfigure.flyway;

import io.github.jdubois.bootui.core.dto.FlywayMigrationDto;
import io.github.jdubois.bootui.spi.FlywayCleanOutcome;
import io.github.jdubois.bootui.spi.FlywayDatabaseSnapshot;
import io.github.jdubois.bootui.spi.FlywayMigrateOutcome;
import io.github.jdubois.bootui.spi.FlywayMigrationSnapshot;
import io.github.jdubois.bootui.spi.FlywayProvider;
import jakarta.annotation.Nullable;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.CleanResult;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.ClassUtils;

/**
 * Spring implementation of the framework-neutral {@link FlywayProvider} seam: the sole holder of the optional
 * {@code org.flywaydb.*} types on the Spring side. It discovers the {@link Flyway} beans declared in the
 * current application context, maps their {@code MigrationInfo[]} to neutral {@link FlywayDatabaseSnapshot}s,
 * runs the {@code migrate}/{@code clean} primitives, and keeps the Spring-Modulith module-aware behaviour
 * (read-only per-module views, the actions-blocked reason) that has no Quarkus analogue.
 *
 * <p>This is a byte-identical extraction of the former {@code FlywayController}'s Flyway-typed code; the
 * engine {@code FlywayService} owns the neutral assembly (counting, sorting, totals) and the action
 * orchestration (target resolution, confirmation gating, {@code FlywayActionResult} shaping). It is wired only
 * inside a {@code @ConditionalOnClass(Flyway.class)} backend configuration, so the Flyway types are never
 * linked in a Flyway-absent application.</p>
 */
public class SpringFlywayProvider implements FlywayProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringFlywayProvider.class);

    private static final String CLEAN_DISABLED_BY_FLYWAY =
            "Flyway clean is disabled by Flyway configuration. Set spring.flyway.clean-disabled=false to allow it.";
    private static final String MODULITH_ACTIONS_DISABLED =
            "Spring Modulith module-aware Flyway migrations use module-specific history tables and are read-only in BootUI.";
    private static final String MODULITH_STRATEGY_CLASS =
            "org.springframework.modulith.runtime.flyway.SpringModulithFlywayMigrationStrategy";
    private static final String MODULITH_IDENTIFIERS_CLASS =
            "org.springframework.modulith.core.ApplicationModuleIdentifiers";
    private static final String MODULITH_ROOT_IDENTIFIER = "__root";

    private final ObjectProvider<ListableBeanFactory> beanFactoryProvider;

    public SpringFlywayProvider(ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
        this.beanFactoryProvider = beanFactoryProvider;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<FlywayDatabaseSnapshot> report() {
        return discoverReportEntries().stream().map(this::toSnapshot).toList();
    }

    @Override
    public Optional<String> actionsBlockedReason() {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        return moduleAwareFlywayActive(factory) ? Optional.of(MODULITH_ACTIONS_DISABLED) : Optional.empty();
    }

    @Override
    public List<String> actionTargets() {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null) {
            return List.of();
        }
        return discoverFlywayBeans(factory).stream().map(FlywayEntry::beanName).toList();
    }

    @Override
    public Optional<String> cleanDisabledReason(String name) {
        return findWritable(name).map(entry -> cleanDisabledReason(entry.flyway()));
    }

    @Override
    public FlywayMigrateOutcome migrate(String name) {
        Flyway flyway = findWritable(name).map(FlywayEntry::flyway).orElse(null);
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
        Flyway flyway = findWritable(name).map(FlywayEntry::flyway).orElse(null);
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

    private Optional<FlywayEntry> findWritable(String name) {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null) {
            return Optional.empty();
        }
        return discoverFlywayBeans(factory).stream()
                .filter(entry -> entry.beanName().equals(name))
                .findFirst();
    }

    private List<FlywayEntry> discoverReportEntries() {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null) {
            return List.of();
        }
        List<FlywayEntry> entries = discoverFlywayBeans(factory);
        if (entries.isEmpty() || !moduleAwareFlywayActive(factory)) {
            return entries;
        }
        List<String> moduleIdentifiers = moduleIdentifiers(factory);
        List<FlywayEntry> moduleAwareEntries = new ArrayList<>();
        for (FlywayEntry entry : entries) {
            moduleAwareEntries.addAll(toModuleAwareEntries(entry, moduleIdentifiers));
        }
        return moduleAwareEntries.isEmpty() ? readOnlyEntries(entries) : moduleAwareEntries;
    }

    private List<FlywayEntry> discoverFlywayBeans(ListableBeanFactory factory) {
        String[] beanNames = factory.getBeanNamesForType(Flyway.class);
        List<FlywayEntry> entries = new ArrayList<>(beanNames.length);
        for (String beanName : beanNames) {
            Flyway flyway;
            try {
                flyway = factory.getBean(beanName, Flyway.class);
            } catch (Exception ex) {
                continue;
            }
            entries.add(FlywayEntry.writable(strip(beanName), flyway));
        }
        return entries;
    }

    private FlywayDatabaseSnapshot toSnapshot(FlywayEntry entry) {
        MigrationInfo[] all;
        try {
            all = entry.flyway().info().all();
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
        String cleanDisabledReason =
                entry.actionsEnabled() ? cleanDisabledReason(entry.flyway()) : entry.disabledReason();
        return new FlywayDatabaseSnapshot(
                entry.beanName(),
                migrations,
                entry.actionsEnabled(),
                entry.actionsEnabled() ? null : entry.disabledReason(),
                entry.actionsEnabled() && cleanDisabledReason == null,
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

    @Nullable
    private String cleanDisabledReason(Flyway flyway) {
        Configuration configuration = flyway.getConfiguration();
        if (configuration != null && configuration.isCleanDisabled()) {
            return CLEAN_DISABLED_BY_FLYWAY;
        }
        return null;
    }

    private List<String> nullSafeList(@Nullable List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private List<FlywayEntry> readOnlyEntries(List<FlywayEntry> entries) {
        return entries.stream()
                .map(entry -> FlywayEntry.readOnly(entry.beanName(), entry.flyway(), MODULITH_ACTIONS_DISABLED))
                .toList();
    }

    private boolean moduleAwareFlywayActive(@Nullable ListableBeanFactory factory) {
        return factory != null && beanPresent(factory, MODULITH_STRATEGY_CLASS);
    }

    private boolean beanPresent(ListableBeanFactory factory, String className) {
        Class<?> type = classForName(className);
        if (type == null) {
            return false;
        }
        String[] beanNames = factory.getBeanNamesForType(type, false, false);
        return beanNames != null && beanNames.length > 0;
    }

    @Nullable
    private Class<?> classForName(String className) {
        try {
            return ClassUtils.forName(className, getClass().getClassLoader());
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }

    private List<String> moduleIdentifiers(ListableBeanFactory factory) {
        Class<?> identifiersType = classForName(MODULITH_IDENTIFIERS_CLASS);
        if (identifiersType == null) {
            return List.of();
        }
        String[] beanNames = factory.getBeanNamesForType(identifiersType, false, false);
        if (beanNames == null || beanNames.length == 0) {
            return List.of();
        }
        try {
            Object identifiers = factory.getBean(beanNames[0], identifiersType);
            Method streamMethod = identifiersType.getMethod("stream");
            Object stream = streamMethod.invoke(identifiers);
            if (stream instanceof Stream<?> identifierStream) {
                try (identifierStream) {
                    return identifierStream
                            .map(Object::toString)
                            .filter(identifier -> !identifier.isBlank())
                            .toList();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            log.debug("Could not inspect Spring Modulith application module identifiers", ex);
        }
        return List.of();
    }

    private List<FlywayEntry> toModuleAwareEntries(FlywayEntry entry, List<String> moduleIdentifiers) {
        Set<String> identifiers = new LinkedHashSet<>();
        identifiers.add(MODULITH_ROOT_IDENTIFIER);
        identifiers.addAll(moduleIdentifiers);

        List<FlywayEntry> entries = new ArrayList<>(identifiers.size());
        for (String identifier : identifiers) {
            try {
                entries.add(FlywayEntry.readOnly(
                        moduleAwareName(entry.beanName(), identifier),
                        moduleAwareFlyway(entry.flyway(), identifier),
                        MODULITH_ACTIONS_DISABLED));
            } catch (RuntimeException ex) {
                log.debug("Could not create Spring Modulith Flyway view for module {}", identifier, ex);
            }
        }
        return entries;
    }

    private String moduleAwareName(String beanName, String identifier) {
        return beanName + ":" + identifier;
    }

    private Flyway moduleAwareFlyway(Flyway flyway, String identifier) {
        Configuration configuration = flyway.getConfiguration();
        List<String> locations = Stream.of(configuration.getLocations())
                .map(Location::toString)
                .map(location -> moduleAwareLocation(location, identifier))
                .toList();

        return Flyway.configure()
                .configuration(configuration)
                .locations(locations.toArray(String[]::new))
                .table(moduleAwareTable(configuration.getTable(), identifier))
                .baselineVersion("0")
                .baselineOnMigrate(true)
                .load();
    }

    private String moduleAwareLocation(String location, String identifier) {
        if (location.endsWith("*")) {
            return location;
        }
        return location + "/" + identifier.replace('.', '/');
    }

    private String moduleAwareTable(String table, String identifier) {
        return MODULITH_ROOT_IDENTIFIER.equals(identifier) ? table : table + "_" + identifier;
    }

    private record FlywayEntry(
            String beanName,
            Flyway flyway,
            boolean actionsEnabled,
            @Nullable String disabledReason) {

        static FlywayEntry writable(String beanName, Flyway flyway) {
            return new FlywayEntry(beanName, flyway, true, null);
        }

        static FlywayEntry readOnly(String beanName, Flyway flyway, String disabledReason) {
            return new FlywayEntry(beanName, flyway, false, disabledReason);
        }
    }
}
