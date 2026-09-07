package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Live PostgreSQL coverage for the Database Advisor, against a real server rather than a hand-built model.
 *
 * <p>Three things can only be proven against a real PostgreSQL catalog, and all three were previously wrong:
 * a declaratively partitioned table must be reported once on its parent instead of once per child partition,
 * a sequence's headroom must be measured against its owning column's capacity rather than the sequence's own
 * maximum, and a {@code NOT VALID} constraint must be visible at all — {@code getImportedKeys()} reports it as
 * if it were fully enforced.</p>
 *
 * <p>It reuses the Testcontainers PostgreSQL setup already used by {@code SpringFlywayProviderTests} and skips
 * cleanly when Docker is unavailable.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresDatabaseAdvisorLiveTests {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    private static DataSource dataSource;

    @BeforeAll
    static void createSchema() throws SQLException {
        DriverManagerDataSource source =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        source.setDriverClassName("org.postgresql.Driver");
        dataSource = source;
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            // A partitioned parent with two child partitions, none of which declares a primary key.
            statement.execute(
                    "create table events (id bigint, occurred_on date not null) partition by range (occurred_on)");
            statement.execute("create table events_2024_01 partition of events "
                    + "for values from ('2024-01-01') to ('2024-02-01')");
            statement.execute("create table events_2024_02 partition of events "
                    + "for values from ('2024-02-01') to ('2024-03-01')");

            // An int4 column fed by an explicitly bigint sequence: the sequence's own range is barely touched
            // (0%), but the column it feeds dies at 2,147,483,647 — the case the previous implementation missed.
            statement.execute("create table tickets (id integer primary key, label varchar(64) not null)");
            statement.execute("create sequence tickets_id_seq as bigint owned by tickets.id");
            statement.execute("alter table tickets alter column id set default nextval('tickets_id_seq')");
            statement.execute("select setval('tickets_id_seq', 2100000000)");

            // A foreign key added NOT VALID and never validated: enforced for new rows only.
            statement.execute("create table customers (id bigint primary key)");
            statement.execute("create table orders (id bigint primary key, customer_id bigint not null)");
            statement.execute("alter table orders add constraint fk_orders_customer "
                    + "foreign key (customer_id) references customers(id) not valid");

            statement.execute("create table insert_only_log (id bigint)");
            statement.execute("create publication inserts_only for table insert_only_log with (publish = 'insert')");
            statement.execute("create table published_log (id bigint)");
            statement.execute("create publication changes for table published_log with (publish = 'update, delete')");
            statement.execute("create schema published_schema");
            statement.execute("create table published_schema.schema_log (id bigint)");
            statement.execute("create publication schema_changes for tables in schema published_schema "
                    + "with (publish = 'update')");
            statement.execute("create publication root_changes for table events "
                    + "with (publish = 'delete', publish_via_partition_root = true)");
            statement.execute("create publication all_inserts for all tables with (publish = 'insert')");

            statement.execute(
                    "create sequence descending_seq increment by -1 minvalue -1000 maxvalue -1 start with -1");
            statement.execute("select setval('descending_seq', -900)");
        }
    }

    private static DatabaseAdvisorReport scan() {
        return DatabaseAdvisorScanner.using(
                        () -> List.of(new NamedDataSource("primary", dataSource)),
                        () -> EntityDiscovery.empty(null),
                        Clock.systemUTC())
                .scan();
    }

    private static Optional<DatabaseAdvisorRuleResultDto> finding(DatabaseAdvisorReport report, String ruleId) {
        return report.results().stream()
                .filter(result -> result.id().equals(ruleId))
                .findFirst();
    }

    @Test
    void scansTheLivePostgresSchemaWithoutTruncationOrErrors() {
        DatabaseAdvisorReport report = scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.truncated()).isFalse();
        assertThat(report.rulesErrored()).isZero();
        assertThat(report.dataSources()).singleElement().satisfies(status -> {
            assertThat(status.status()).isEqualTo("AVAILABLE");
            assertThat(status.dialect()).isEqualTo("PostgreSQL");
            assertThat(status.product()).containsIgnoringCase("PostgreSQL");
        });
    }

    @Test
    void reportsAPartitionedTableOnceOnItsParentInsteadOfOncePerPartition() {
        DatabaseAdvisorReport report = scan();

        assertThat(finding(report, "DB-SCHEMA-001")).hasValueSatisfying(result -> {
            assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("public.events"));
            assertThat(result.sampleViolations()).noneMatch(detail -> detail.contains("events_2024_01"));
            assertThat(result.sampleViolations()).noneMatch(detail -> detail.contains("events_2024_02"));
        });
    }

    @Test
    void measuresSequenceHeadroomAgainstTheOwningColumnCapacity() {
        DatabaseAdvisorReport report = scan();

        // tickets_id_seq is a bigint sequence (0% of its own range) feeding an int4 column at ~97%.
        assertThat(finding(report, "DB-PG-002")).hasValueSatisfying(result -> {
            assertThat(result.sampleViolations())
                    .anyMatch(detail -> detail.contains("tickets_id_seq")
                            && detail.contains("public.tickets.id")
                            && detail.contains("limited by its owning column type"));
        });
    }

    @Test
    void reportsCurrentUnvalidatedConstraintStateWithoutInferringHistory() {
        DatabaseAdvisorReport report = scan();

        assertThat(finding(report, "DB-PG-003"))
                .hasValueSatisfying(result -> assertThat(result.sampleViolations())
                        .anyMatch(detail -> detail.contains("fk_orders_customer") && detail.contains("public.orders")));
    }

    @Test
    void publicationActionsExpandedSchemaMembershipAndPartitionRootAreRespected() {
        assertThat(finding(scan(), "DB-PG-004")).hasValueSatisfying(result -> {
            assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("public.published_log"));
            assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("published_schema.schema_log"));
            assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("public.events "));
            assertThat(result.sampleViolations()).noneMatch(detail -> detail.contains("insert_only_log"));
            assertThat(result.sampleViolations()).noneMatch(detail -> detail.contains("events_2024_"));
        });
    }

    @Test
    void descendingSequenceUsesItsMinimumRatherThanPositiveMaximum() {
        assertThat(finding(scan(), "DB-PG-002"))
                .hasValueSatisfying(result -> assertThat(result.sampleViolations())
                        .anyMatch(detail ->
                                detail.contains("descending_seq") && detail.contains("effective bound -1000")));
    }

    @Test
    void skipsTheMySqlRulesWithAnExplicitReasonInsteadOfReportingThemClean() {
        DatabaseAdvisorReport report = scan();

        assertThat(report.rulesSkipped()).isGreaterThan(0);
        assertThat(report.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.source()).isEqualTo("DB-MYSQL-001"));
        assertThat(report.results()).noneMatch(result -> result.id().startsWith("DB-MYSQL"));
    }
}
