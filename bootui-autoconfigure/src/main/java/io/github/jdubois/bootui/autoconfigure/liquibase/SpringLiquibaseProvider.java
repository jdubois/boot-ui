package io.github.jdubois.bootui.autoconfigure.liquibase;

import io.github.jdubois.bootui.core.dto.LiquibaseActionResult;
import io.github.jdubois.bootui.core.dto.LiquibaseChangeSetDto;
import io.github.jdubois.bootui.spi.LiquibaseDatabaseSnapshot;
import io.github.jdubois.bootui.spi.LiquibaseProvider;
import io.github.jdubois.bootui.spi.LiquibaseTarget;
import jakarta.annotation.Nullable;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.analytics.configuration.AnalyticsArgs;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.RanChangeSet;
import liquibase.changelog.StandardChangeLogHistoryService;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.integration.IntegrationDetails;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.ui.UIServiceEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

/**
 * Spring {@link LiquibaseProvider}: the Liquibase-specific seam behind the shared engine
 * {@code LiquibaseService}, backed by the {@code liquibase.integration.spring.SpringLiquibase} beans declared
 * in the current application context. It is the <strong>sole</strong> importer of the {@code liquibase.*} API
 * on the Spring side; the engine stays liquibase-free.
 *
 * <p>This is the byte-identical extraction of the former {@code LiquibaseController}'s discovery, change-set
 * reading and update logic: {@link #databases()} reproduces the controller's {@code changeSets()} per-bean
 * loop (applied change sets read via {@code StandardChangeLogHistoryService} over a {@link JdbcConnection},
 * pending change sets read via {@code listUnrunChangeSets} inside a Liquibase {@link Scope}); {@link #targets()}
 * reproduces the controller's cheap {@code discover()} + {@code updateDisabledReason} target resolution (no
 * JDBC); and {@link #update(String)} reproduces the {@code DefaultLiquibaseActionExecutor} primitive.</p>
 */
public class SpringLiquibaseProvider implements LiquibaseProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringLiquibaseProvider.class);

    private final ObjectProvider<ListableBeanFactory> beanFactoryProvider;
    private final LiquibaseActionExecutor actionExecutor;

    public SpringLiquibaseProvider(ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
        this(beanFactoryProvider, new DefaultLiquibaseActionExecutor());
    }

    SpringLiquibaseProvider(
            ObjectProvider<ListableBeanFactory> beanFactoryProvider, LiquibaseActionExecutor actionExecutor) {
        this.beanFactoryProvider = beanFactoryProvider;
        this.actionExecutor = actionExecutor;
    }

    @Override
    public boolean available() {
        // The Spring adapter only constructs this provider when SpringLiquibase is on the classpath (the
        // backend configuration is @ConditionalOnClass), so the Liquibase integration is always present here.
        return true;
    }

    @Override
    public List<LiquibaseDatabaseSnapshot> databases() {
        DatabaseFactory factory = DatabaseFactory.getInstance();
        List<LiquibaseDatabaseSnapshot> databases = new ArrayList<>();
        for (LiquibaseEntry entry : discover()) {
            List<LiquibaseChangeSetDto> appliedChangeSets = readAppliedChangeSets(entry.liquibase(), factory);
            List<LiquibaseChangeSetDto> pendingChangeSets = readPendingChangeSets(entry.beanName(), entry.liquibase());
            String updateDisabledReason = updateDisabledReason(entry.liquibase());
            databases.add(new LiquibaseDatabaseSnapshot(
                    entry.beanName(), appliedChangeSets, pendingChangeSets, updateDisabledReason));
        }
        return databases;
    }

    @Override
    public List<LiquibaseTarget> targets() {
        List<LiquibaseTarget> targets = new ArrayList<>();
        for (LiquibaseEntry entry : discover()) {
            targets.add(new LiquibaseTarget(entry.beanName(), updateDisabledReason(entry.liquibase())));
        }
        return targets;
    }

    @Override
    public LiquibaseActionResult update(String name) throws Exception {
        LiquibaseEntry entry = discover().stream()
                .filter(candidate -> candidate.beanName().equals(name))
                .findFirst()
                .orElseThrow(() -> new LiquibaseException("No Liquibase bean named '" + name + "' is available."));
        return actionExecutor.update(entry.beanName(), entry.liquibase());
    }

    private List<LiquibaseEntry> discover() {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null) {
            return List.of();
        }
        String[] beanNames = factory.getBeanNamesForType(SpringLiquibase.class);
        List<LiquibaseEntry> entries = new ArrayList<>(beanNames.length);
        for (String beanName : beanNames) {
            SpringLiquibase liquibase;
            try {
                liquibase = factory.getBean(beanName, SpringLiquibase.class);
            } catch (Exception ex) {
                continue;
            }
            entries.add(new LiquibaseEntry(strip(beanName), liquibase));
        }
        return entries;
    }

    private List<LiquibaseChangeSetDto> readAppliedChangeSets(SpringLiquibase liquibase, DatabaseFactory factory) {
        try {
            DataSource dataSource = liquibase.getDataSource();
            if (dataSource == null) {
                return List.of();
            }
            JdbcConnection connection = new JdbcConnection(dataSource.getConnection());
            Database database = null;
            try {
                database = factory.findCorrectDatabaseImplementation(connection);
                String schema = StringUtils.hasText(liquibase.getLiquibaseSchema())
                        ? liquibase.getLiquibaseSchema()
                        : liquibase.getDefaultSchema();
                if (StringUtils.hasText(schema)) {
                    database.setDefaultSchemaName(schema);
                }
                database.setDatabaseChangeLogTableName(liquibase.getDatabaseChangeLogTable());
                database.setDatabaseChangeLogLockTableName(liquibase.getDatabaseChangeLogLockTable());
                StandardChangeLogHistoryService service = new StandardChangeLogHistoryService();
                service.setDatabase(database);
                List<LiquibaseChangeSetDto> changeSets = new ArrayList<>();
                for (RanChangeSet ranChangeSet : service.getRanChangeSets()) {
                    changeSets.add(toChangeSetDto(ranChangeSet));
                }
                return changeSets;
            } finally {
                if (database != null) {
                    database.close();
                } else {
                    connection.close();
                }
            }
        } catch (Exception ex) {
            // Fail closed for this bean: an inaccessible history table yields an empty list.
            return List.of();
        }
    }

    private List<LiquibaseChangeSetDto> readPendingChangeSets(String beanName, SpringLiquibase source) {
        if (updateDisabledReason(source) != null) {
            return List.of();
        }
        try {
            return Scope.child(
                    DefaultLiquibaseActionExecutor.scopeVars(source),
                    () -> readPendingChangeSetsInScope(beanName, source));
        } catch (Exception ex) {
            log.debug("Could not read pending Liquibase change sets for bean '{}'.", beanName, ex);
            return List.of();
        }
    }

    private List<LiquibaseChangeSetDto> readPendingChangeSetsInScope(String beanName, SpringLiquibase source)
            throws Exception {
        Connection connection = null;
        Liquibase liquibase = null;
        try {
            connection = source.getDataSource().getConnection();
            BootUiSpringLiquibase runner =
                    BootUiSpringLiquibase.from(source, DefaultLiquibaseActionExecutor.changeLogParameters(source));
            liquibase = runner.create(connection);
            Contexts contexts = new Contexts(source.getContexts());
            LabelExpression labelExpression = new LabelExpression(source.getLabelFilter());
            List<LiquibaseChangeSetDto> changeSets = new ArrayList<>();
            for (ChangeSet changeSet : liquibase.listUnrunChangeSets(contexts, labelExpression)) {
                changeSets.add(toPendingChangeSetDto(changeSet));
            }
            return changeSets;
        } finally {
            if (liquibase != null) {
                try {
                    liquibase.close();
                } catch (LiquibaseException ex) {
                    log.debug("Could not close Liquibase after reading pending change sets.", ex);
                }
            } else if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ex) {
                    log.debug(
                            "Could not close JDBC connection after reading pending Liquibase change sets for bean '{}'.",
                            beanName,
                            ex);
                }
            }
        }
    }

    @Nullable
    private String updateDisabledReason(SpringLiquibase liquibase) {
        if (liquibase.getDataSource() == null) {
            return "Liquibase update cannot run because this bean has no DataSource.";
        }
        if (!StringUtils.hasText(liquibase.getChangeLog())) {
            return "Liquibase update cannot run because this bean has no change log.";
        }
        return null;
    }

    private LiquibaseChangeSetDto toChangeSetDto(RanChangeSet changeSet) {
        return new LiquibaseChangeSetDto(
                changeSet.getId(),
                changeSet.getAuthor(),
                changeSet.getChangeLog(),
                changeSet.getDescription(),
                changeSet.getComments(),
                changeSet.getExecType() == null ? null : changeSet.getExecType().name(),
                nullSafeToInstant(changeSet.getDateExecuted()),
                changeSet.getOrderExecuted(),
                changeSet.getLastCheckSum() == null
                        ? null
                        : changeSet.getLastCheckSum().toString(),
                changeSet.getTag(),
                changeSet.getDeploymentId(),
                changeSet.getContextExpression() == null
                        ? List.of()
                        : List.copyOf(changeSet.getContextExpression().getContexts()),
                changeSet.getLabels() == null
                        ? List.of()
                        : List.copyOf(changeSet.getLabels().getLabels()));
    }

    private LiquibaseChangeSetDto toPendingChangeSetDto(ChangeSet changeSet) {
        return new LiquibaseChangeSetDto(
                changeSet.getId(),
                changeSet.getAuthor(),
                changeSet.getFilePath(),
                changeSet.getDescription(),
                changeSet.getComments(),
                "PENDING",
                null,
                null,
                null,
                null,
                null,
                changeSet.getContextFilter() == null
                        ? List.of()
                        : List.copyOf(changeSet.getContextFilter().getContexts()),
                changeSet.getLabels() == null
                        ? List.of()
                        : List.copyOf(changeSet.getLabels().getLabels()));
    }

    @Nullable
    private String nullSafeToInstant(@Nullable java.util.Date date) {
        return date == null ? null : Instant.ofEpochMilli(date.getTime()).toString();
    }

    private String strip(String beanName) {
        return beanName.startsWith("&") ? beanName.substring(1) : beanName;
    }

    interface LiquibaseActionExecutor {
        LiquibaseActionResult update(String beanName, SpringLiquibase liquibase) throws Exception;
    }

    private static final class DefaultLiquibaseActionExecutor implements LiquibaseActionExecutor {

        @Override
        public LiquibaseActionResult update(String beanName, SpringLiquibase source) throws Exception {
            return Scope.child(scopeVars(source), () -> updateInScope(beanName, source));
        }

        private LiquibaseActionResult updateInScope(String beanName, SpringLiquibase source) throws Exception {
            BootUiSpringLiquibase runner = BootUiSpringLiquibase.from(source, changeLogParameters(source));
            try (Connection connection = source.getDataSource().getConnection();
                    Liquibase liquibase = runner.create(connection)) {
                Contexts contexts = new Contexts(source.getContexts());
                LabelExpression labelExpression = new LabelExpression(source.getLabelFilter());
                int before =
                        liquibase.listUnrunChangeSets(contexts, labelExpression).size();
                if (StringUtils.hasText(source.getTag())) {
                    liquibase.update(source.getTag(), contexts, labelExpression);
                } else {
                    liquibase.update(contexts, labelExpression);
                }
                int after =
                        liquibase.listUnrunChangeSets(contexts, labelExpression).size();
                int applied = Math.max(0, before - after);
                String message = applied == 0
                        ? "Liquibase database is already up to date."
                        : "Liquibase applied " + applied + " change set(s).";
                return new LiquibaseActionResult("success", message, beanName, before, after, applied, List.of());
            } catch (SQLException ex) {
                throw new DatabaseException(ex);
            }
        }

        private static Map<String, Object> scopeVars(SpringLiquibase source) throws Exception {
            Map<String, Object> scopeVars = new HashMap<>();
            UIServiceEnum uiService = source.getUiService() == null ? UIServiceEnum.LOGGER : source.getUiService();
            scopeVars.put(
                    Scope.Attr.ui.name(),
                    uiService.getUiServiceClass().getDeclaredConstructor().newInstance());
            scopeVars.put(Scope.Attr.integrationDetails.name(), new IntegrationDetails("spring"));
            if (source.getAnalyticsEnabled() != null) {
                scopeVars.put(AnalyticsArgs.ENABLED.getKey(), source.getAnalyticsEnabled());
            }
            scopeVars.put(Scope.Attr.maxAnalyticsCacheSize.name(), 10);
            if (source.getLicenseKey() != null) {
                scopeVars.put("liquibase.licenseKey", source.getLicenseKey());
            }
            return scopeVars;
        }

        private static Map<String, String> changeLogParameters(SpringLiquibase source) throws LiquibaseException {
            try {
                Field field = SpringLiquibase.class.getDeclaredField("parameters");
                field.setAccessible(true);
                Object value = field.get(source);
                if (value == null) {
                    return Map.of();
                }
                if (!(value instanceof Map<?, ?> rawParameters)) {
                    throw new LiquibaseException("SpringLiquibase changelog parameters are not a map.");
                }
                Map<String, String> parameters = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : rawParameters.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new LiquibaseException("SpringLiquibase changelog parameters must be strings.");
                    }
                    Object rawValue = entry.getValue();
                    if (rawValue != null && !(rawValue instanceof String)) {
                        throw new LiquibaseException("SpringLiquibase changelog parameters must be strings.");
                    }
                    String parameterValue = (String) rawValue;
                    parameters.put(key, parameterValue);
                }
                return parameters;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                throw new LiquibaseException(
                        "Liquibase update cannot run because BootUI could not read SpringLiquibase changelog parameters.",
                        ex);
            }
        }
    }

    private static final class BootUiSpringLiquibase extends SpringLiquibase {

        static BootUiSpringLiquibase from(SpringLiquibase source, Map<String, String> parameters) {
            BootUiSpringLiquibase runner = new BootUiSpringLiquibase();
            runner.setDataSource(source.getDataSource());
            runner.setChangeLog(source.getChangeLog());
            runner.setResourceLoader(source.getResourceLoader());
            runner.setContexts(source.getContexts());
            runner.setLabelFilter(source.getLabelFilter());
            runner.setTag(source.getTag());
            runner.setDefaultSchema(source.getDefaultSchema());
            runner.setLiquibaseSchema(source.getLiquibaseSchema());
            runner.setDatabaseChangeLogTable(source.getDatabaseChangeLogTable());
            runner.setDatabaseChangeLogLockTable(source.getDatabaseChangeLogLockTable());
            runner.setLiquibaseTablespace(source.getLiquibaseTablespace());
            runner.setLicenseKey(source.getLicenseKey());
            runner.setAnalyticsEnabled(source.getAnalyticsEnabled());
            runner.setUiService(source.getUiService());
            runner.setCustomizer(source.getCustomizer());
            runner.setChangeLogParameters(parameters);
            runner.setDropFirst(false);
            runner.setClearCheckSums(false);
            runner.setTestRollbackOnUpdate(false);
            return runner;
        }

        Liquibase create(Connection connection) throws LiquibaseException {
            return createLiquibase(connection);
        }
    }

    private record LiquibaseEntry(String beanName, SpringLiquibase liquibase) {}
}
