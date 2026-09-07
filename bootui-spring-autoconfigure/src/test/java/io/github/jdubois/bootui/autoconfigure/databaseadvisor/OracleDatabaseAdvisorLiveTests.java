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
import org.testcontainers.oracle.OracleContainer;

/**
 * Live Oracle coverage for the Database Advisor, against a real 19c+ server rather than a hand-built model.
 *
 * <p>Four things can only be proven against a real Oracle data dictionary, and none of them existed before
 * this change: the dialect is confirmed genuine Oracle (not merely a driver-reported product name) by reading
 * {@code v$version}; an unusable index is visible through {@code all_indexes.status}; a disabled foreign key
 * constraint is visible through {@code all_constraints.status}; and a composite foreign key is recognized as
 * supported by an index whose leading columns are the constraint's columns in <em>any</em> order — Oracle's
 * own documented guidance, and a deliberately different rule from every other dialect this advisor covers.</p>
 *
 * <p>Uses {@code gvenzl/oracle-free} (Oracle Database Free, a 19c+ superset the engine's {@code ALL_*}/
 * {@code SYS_CONTEXT} SQL is written and verified against) through the test-scope-only
 * {@code testcontainers-oracle-free} module and {@code ojdbc11} driver — neither exists outside this test's
 * dependency scope; the engine itself never imports an {@code oracle.jdbc.*} class. Skips cleanly without
 * Docker.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class OracleDatabaseAdvisorLiveTests {

    @Container
    static OracleContainer oracle = new OracleContainer("gvenzl/oracle-free:slim-faststart");

    private static DataSource dataSource;

    @BeforeAll
    static void createSchema() throws SQLException {
        DriverManagerDataSource source =
                new DriverManagerDataSource(oracle.getJdbcUrl(), oracle.getUsername(), oracle.getPassword());
        source.setDriverClassName(oracle.getDriverClassName());
        dataSource = source;
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            // An index left UNUSABLE, as a failed/aborted maintenance operation would leave it.
            statement.execute("create table orders (id number(19) primary key, customer_id number(19) not null)");
            statement.execute("create index ix_orders_customer on orders(customer_id)");
            statement.execute("alter index ix_orders_customer unusable");

            // A foreign key constraint disabled after creation: enforces nothing until re-enabled.
            statement.execute("create table customers (id number(19) primary key)");
            statement.execute("alter table orders add constraint fk_orders_customer "
                    + "foreign key (customer_id) references customers(id)");
            statement.execute("alter table orders disable constraint fk_orders_customer");

            // A non-cycling sequence well past 80% of its own MAXVALUE.
            statement.execute("create sequence tickets_seq start with 990 maxvalue 1000 nocache nocycle");
            statement.execute("select tickets_seq.nextval from dual");
            statement.execute("create table narrow_identity (id number(5,0) generated always as identity "
                    + "(start with 90000 nocache) primary key, label varchar2(10))");
            statement.execute("insert into narrow_identity(label) values ('fixture')");

            // A composite foreign key supported by an index whose leading columns are in the opposite order
            // from the constraint's own declaration - Oracle's own documented guidance still counts this as
            // supporting the constraint, unlike every other dialect this advisor covers.
            statement.execute("create table parents (tenant_id number(19) not null, id number(19) not null, "
                    + "primary key (tenant_id, id))");
            statement.execute("create table order_lines (tenant_id number(19) not null, "
                    + "parent_id number(19) not null, "
                    + "constraint fk_order_lines_parent foreign key (tenant_id, parent_id) "
                    + "references parents(tenant_id, id))");
            statement.execute("create index ix_order_lines_reversed on order_lines(parent_id, tenant_id)");
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
    void scansTheLiveOracleSchemaAndConfirmsTheDialect() {
        DatabaseAdvisorReport report = scan();

        assertThat(report.dataSources()).singleElement().satisfies(status -> {
            assertThat(status.status()).isEqualTo("AVAILABLE");
            assertThat(status.dialect()).isEqualTo("Oracle");
            assertThat(status.product()).containsIgnoringCase("Oracle");
        });
        assertThat(report.rulesErrored()).isZero();
    }

    @Test
    void reportsAnUnusableIndex() {
        assertThat(finding(scan(), "DB-ORACLE-001"))
                .hasValueSatisfying(result -> assertThat(result.sampleViolations())
                        .anyMatch(detail -> detail.contains("IX_ORDERS_CUSTOMER") && detail.contains("UNUSABLE")));
    }

    @Test
    void reportsADisabledForeignKeyConstraint() {
        assertThat(finding(scan(), "DB-ORACLE-002"))
                .hasValueSatisfying(result -> assertThat(result.sampleViolations())
                        .anyMatch(detail -> detail.contains("FK_ORDERS_CUSTOMER") && detail.contains("DISABLED")));
    }

    @Test
    void reportsANonCyclingSequenceNearingExhaustion() {
        assertThat(finding(scan(), "DB-ORACLE-003"))
                .hasValueSatisfying(result ->
                        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("TICKETS_SEQ")));
    }

    @Test
    void identityHeadroomUsesPhysicalNumberPrecisionRatherThanSequenceMaximum() {
        assertThat(finding(scan(), "DB-ORACLE-003"))
                .hasValueSatisfying(result -> assertThat(result.sampleViolations())
                        .anyMatch(detail ->
                                detail.contains("NARROW_IDENTITY.ID") && detail.contains("effective bound 99999")));
    }

    @Test
    void acceptsAnyColumnOrderForACompositeForeignKeyIndexOnOracle() {
        // DB-SCHEMA-002 must not fire for fk_order_lines_parent: the supporting index exists, just with its
        // leading columns in the opposite order from the constraint's own declaration.
        Optional<DatabaseAdvisorRuleResultDto> result = finding(scan(), "DB-SCHEMA-002");
        assertThat(result)
                .satisfiesAnyOf(
                        value -> assertThat(value).isEmpty(),
                        value -> assertThat(value.get().sampleViolations())
                                .noneMatch(detail -> detail.contains("ORDER_LINES")));
    }

    @Test
    void skipsThePostgresAndMySqlRulesWithAnExplicitReasonInsteadOfReportingThemClean() {
        DatabaseAdvisorReport report = scan();

        assertThat(report.results()).noneMatch(result -> result.id().startsWith("DB-PG"));
        assertThat(report.results()).noneMatch(result -> result.id().startsWith("DB-MYSQL"));
        assertThat(report.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.source()).isEqualTo("DB-PG-001"));
    }
}
