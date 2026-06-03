package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.LiquibaseChangeSetDto;
import io.github.jdubois.bootui.core.dto.LiquibaseDatabaseDto;
import io.github.jdubois.bootui.core.dto.LiquibaseReport;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.sql.DataSource;
import liquibase.changelog.RanChangeSet;
import liquibase.changelog.StandardChangeLogHistoryService;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes read-only Liquibase change-log history for the {@link SpringLiquibase}
 * beans declared in the current application context.
 *
 * <p>This controller is strictly read-only: it only reads the recorded change
 * sets from the Liquibase history table (mirroring the Actuator {@code liquibase}
 * endpoint). It never runs {@code update}, {@code rollback}, {@code dropAll}, or
 * any other mutating Liquibase command.</p>
 */
@RestController
@ConditionalOnClass(SpringLiquibase.class)
@RequestMapping("/bootui/api/liquibase")
public class LiquibaseController {

    private final ObjectProvider<ListableBeanFactory> beanFactoryProvider;

    public LiquibaseController(ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
        this.beanFactoryProvider = beanFactoryProvider;
    }

    @GetMapping("/changesets")
    public LiquibaseReport changeSets() {
        DatabaseFactory factory = DatabaseFactory.getInstance();
        List<LiquibaseDatabaseDto> databases = new ArrayList<>();
        for (LiquibaseEntry entry : discover()) {
            List<LiquibaseChangeSetDto> changeSets = readChangeSets(entry.liquibase(), factory);
            databases.add(new LiquibaseDatabaseDto(entry.beanName(), changeSets.size(), changeSets));
        }
        databases.sort(Comparator.comparing(LiquibaseDatabaseDto::name, Comparator.nullsLast(String::compareTo)));
        int total = databases.stream().mapToInt(LiquibaseDatabaseDto::total).sum();
        return new LiquibaseReport(true, total, databases);
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

    private List<LiquibaseChangeSetDto> readChangeSets(SpringLiquibase liquibase, DatabaseFactory factory) {
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

    @Nullable
    private String nullSafeToInstant(@Nullable java.util.Date date) {
        return date == null ? null : Instant.ofEpochMilli(date.getTime()).toString();
    }

    private String strip(String beanName) {
        return beanName.startsWith("&") ? beanName.substring(1) : beanName;
    }

    private record LiquibaseEntry(String beanName, SpringLiquibase liquibase) {}
}
