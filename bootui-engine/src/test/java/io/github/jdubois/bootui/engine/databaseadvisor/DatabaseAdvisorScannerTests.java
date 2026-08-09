package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseAdvisorScannerTests {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private H2DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource =
                new H2DataSource("jdbc:h2:mem:database-advisor-scanner-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create table customers (id bigint primary key, name varchar(255) not null)");
            statement.execute("create table orders (id bigint primary key, customer_id bigint not null, "
                    + "constraint fk_orders_customer foreign key (customer_id) references customers(id))");
            statement.execute("create table audit_log (message varchar(255))");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("drop all objects");
        }
    }

    @Test
    void initialReportIsNotScannedAndHasNoResults() {
        DatabaseAdvisorScanner scanner =
                DatabaseAdvisorScanner.using(List::of, () -> EntityDiscovery.empty(null), FIXED_CLOCK);
        DatabaseAdvisorReport report = scanner.initialReport();
        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.results()).isEmpty();
    }

    @Test
    void scanReportsDisabledWhenNoDataSourceIsAvailable() {
        DatabaseAdvisorScanner scanner =
                DatabaseAdvisorScanner.using(List::of, () -> EntityDiscovery.empty(null), FIXED_CLOCK);
        DatabaseAdvisorReport report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("DISABLED");
        assertThat(report.dataSourceNames()).isEmpty();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void scanReportsDisabledWhenEveryDataSourceFailsToIntrospect() {
        DataSource brokenDataSource = new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("connection refused");
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLException("connection refused");
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {}

            @Override
            public void setLoginTimeout(int seconds) {}

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() {
                return Logger.getGlobal();
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                return iface.cast(this);
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return iface.isInstance(this);
            }
        };
        DatabaseAdvisorScanner scanner = DatabaseAdvisorScanner.using(
                () -> List.of(new NamedDataSource("broken", brokenDataSource)),
                () -> EntityDiscovery.empty(null),
                FIXED_CLOCK);
        DatabaseAdvisorReport report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("DISABLED");
        assertThat(report.dataSourceNames()).containsExactly("broken");
        assertThat(report.results()).isEmpty();
    }

    @Test
    void scanIntrospectsThePhysicalSchemaAndFlagsAMissingPrimaryKey() {
        DatabaseAdvisorScanner scanner = DatabaseAdvisorScanner.using(
                () -> List.of(new NamedDataSource("primary", dataSource)),
                () -> EntityDiscovery.empty(null),
                FIXED_CLOCK);
        DatabaseAdvisorReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.dataSourceNames()).containsExactly("primary");
        assertThat(report.tablesAnalyzed()).isEqualTo(3);
        assertThat(report.results()).isNotEmpty();

        // H2 automatically creates a supporting index for the "orders.customer_id" foreign key column, so
        // DB-SCHEMA-002 (missing FK index) is exercised directly against synthetic models in
        // DatabaseAdvisorRulesTests instead; this end-to-end scan only asserts the primary-key check, which
        // genuinely requires the JDBC DatabaseMetaData round trip this test exists to cover.
        assertThat(report.results()).anySatisfy(result -> {
            assertThat(result.id()).isEqualTo("DB-SCHEMA-001");
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations().get(0)).containsIgnoringCase("audit_log");
        });
    }

    @Test
    void scanNeverIncludesPassingOrSkippedRulesInTheResultsList() {
        DatabaseAdvisorScanner scanner = DatabaseAdvisorScanner.using(
                () -> List.of(new NamedDataSource("primary", dataSource)),
                () -> EntityDiscovery.empty(null),
                FIXED_CLOCK);
        DatabaseAdvisorReport report = scanner.scan();
        assertThat(report.results())
                .allSatisfy(result -> assertThat(result.status()).isEqualTo("VIOLATION"));
        assertThat(report.rulesEvaluated())
                .isEqualTo(DatabaseAdvisorRuleRegistry.activeRules().size());
    }

    @Test
    void applyDismissalsMarksDismissedResultsAndReducesTheViolationCount() {
        DatabaseAdvisorScanner scanner = DatabaseAdvisorScanner.using(
                () -> List.of(new NamedDataSource("primary", dataSource)),
                () -> EntityDiscovery.empty(null),
                FIXED_CLOCK);
        DatabaseAdvisorReport report = scanner.scan();
        String dismissedId = report.results().get(0).id();

        DatabaseAdvisorReport updated = scanner.applyDismissals(report, Set.of(dismissedId));

        assertThat(updated.results())
                .filteredOn(result -> result.id().equals(dismissedId))
                .allSatisfy(result -> assertThat(result.dismissed()).isTrue());
        assertThat(updated.violationsFound()).isEqualTo(report.violationsFound() - 1);
    }

    @Test
    void scanSurvivesADataSourceSupplierThatThrows() {
        DatabaseAdvisorScanner scanner = DatabaseAdvisorScanner.using(
                () -> {
                    throw new IllegalStateException("bean factory unavailable");
                },
                () -> EntityDiscovery.empty(null),
                FIXED_CLOCK);
        DatabaseAdvisorReport report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("DISABLED");
    }

    /** Minimal H2-backed {@link DataSource} that opens a fresh connection per call, like a real pool. */
    private static final class H2DataSource implements DataSource {

        private final String url;

        private H2DataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return iface.cast(this);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
