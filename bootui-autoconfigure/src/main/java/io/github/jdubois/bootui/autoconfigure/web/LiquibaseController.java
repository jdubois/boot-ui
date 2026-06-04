package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.LiquibaseActionRequest;
import io.github.jdubois.bootui.core.dto.LiquibaseActionResult;
import io.github.jdubois.bootui.core.dto.LiquibaseChangeSetDto;
import io.github.jdubois.bootui.core.dto.LiquibaseDatabaseDto;
import io.github.jdubois.bootui.core.dto.LiquibaseReport;
import jakarta.annotation.Nullable;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes Liquibase change-log history and update actions for the
 * {@link SpringLiquibase} beans declared in the current application context.
 *
 * <p>Mutating commands require an explicit confirmation payload and remain
 * subject to BootUI's global/per-panel read-only filter.</p>
 */
@RestController
@ConditionalOnClass(SpringLiquibase.class)
@RequestMapping("/bootui/api/liquibase")
public class LiquibaseController {

    private static final String CONFIRMATION_REQUIRED =
            "Action requires confirm=true because it mutates the application database.";
    private static final Logger log = LoggerFactory.getLogger(LiquibaseController.class);

    private final ObjectProvider<ListableBeanFactory> beanFactoryProvider;
    private final LiquibaseActionExecutor actionExecutor;

    @Autowired
    public LiquibaseController(ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
        this(beanFactoryProvider, new DefaultLiquibaseActionExecutor());
    }

    LiquibaseController(
            ObjectProvider<ListableBeanFactory> beanFactoryProvider, LiquibaseActionExecutor actionExecutor) {
        this.beanFactoryProvider = beanFactoryProvider;
        this.actionExecutor = actionExecutor;
    }

    @GetMapping("/changesets")
    public LiquibaseReport changeSets() {
        DatabaseFactory factory = DatabaseFactory.getInstance();
        List<LiquibaseDatabaseDto> databases = new ArrayList<>();
        for (LiquibaseEntry entry : discover()) {
            List<LiquibaseChangeSetDto> appliedChangeSets = readAppliedChangeSets(entry.liquibase(), factory);
            List<LiquibaseChangeSetDto> pendingChangeSets = readPendingChangeSets(entry.beanName(), entry.liquibase());
            List<LiquibaseChangeSetDto> changeSets =
                    new ArrayList<>(appliedChangeSets.size() + pendingChangeSets.size());
            changeSets.addAll(appliedChangeSets);
            changeSets.addAll(pendingChangeSets);
            String updateDisabledReason = updateDisabledReason(entry.liquibase());
            databases.add(new LiquibaseDatabaseDto(
                    entry.beanName(),
                    appliedChangeSets.size(),
                    pendingChangeSets.size(),
                    changeSets.size(),
                    changeSets,
                    updateDisabledReason == null,
                    updateDisabledReason));
        }
        databases.sort(Comparator.comparing(LiquibaseDatabaseDto::name, Comparator.nullsLast(String::compareTo)));
        int total = databases.stream().mapToInt(LiquibaseDatabaseDto::total).sum();
        return new LiquibaseReport(true, total, databases);
    }

    @PostMapping("/update")
    public ResponseEntity<LiquibaseActionResult> update(@RequestBody(required = false) LiquibaseActionRequest request) {
        LiquibaseEntry entry = findTarget(request).orElse(null);
        if (entry == null) {
            return action(
                    HttpStatus.NOT_FOUND, "unavailable", "No Liquibase bean matched the requested datasource.", null);
        }
        String disabledReason = updateDisabledReason(entry.liquibase());
        if (disabledReason != null) {
            return action(HttpStatus.FORBIDDEN, "blocked", disabledReason, entry.beanName());
        }
        if (!confirmed(request)) {
            return action(HttpStatus.BAD_REQUEST, "blocked", CONFIRMATION_REQUIRED, entry.beanName());
        }
        try {
            return ResponseEntity.ok(actionExecutor.update(entry.beanName(), entry.liquibase()));
        } catch (Exception ex) {
            return action(HttpStatus.INTERNAL_SERVER_ERROR, "failed", ex.getMessage(), entry.beanName());
        }
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

    private java.util.Optional<LiquibaseEntry> findTarget(@Nullable LiquibaseActionRequest request) {
        List<LiquibaseEntry> entries = discover();
        String requested = request == null ? null : request.beanName();
        if (requested == null || requested.isBlank()) {
            return entries.size() == 1 ? java.util.Optional.of(entries.get(0)) : java.util.Optional.empty();
        }
        return entries.stream()
                .filter(entry -> entry.beanName().equals(requested))
                .findFirst();
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

    private boolean confirmed(@Nullable LiquibaseActionRequest request) {
        return request != null && Boolean.TRUE.equals(request.confirm());
    }

    private ResponseEntity<LiquibaseActionResult> action(
            HttpStatus status, String result, String message, @Nullable String beanName) {
        return ResponseEntity.status(status)
                .body(new LiquibaseActionResult(result, message, beanName, null, null, null, List.of()));
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
            Liquibase liquibase = null;
            try {
                Connection connection = source.getDataSource().getConnection();
                BootUiSpringLiquibase runner = BootUiSpringLiquibase.from(source, changeLogParameters(source));
                liquibase = runner.create(connection);
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
            } finally {
                if (liquibase != null) {
                    liquibase.close();
                }
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
